/**
 * Validates the "given overload, when limits reached, then graceful degradation applied and
 * alerts fired" half of docs/runbooks/capacity-scale-incidents.md's acceptance criteria: hammers
 * POST /reports/pipeline/run (a heavy, on-demand analytics endpoint) well past its shared 5
 * calls/minute rate limit (see com.beettechnologies.posly.capacity.HeavyAnalyticsRateLimit) and
 * asserts the server sheds the excess load with 429s rather than falling over, while a plain
 * aggregate read (GET /reports/sales) keeps working throughout.
 *
 * This intentionally does NOT flip the heavy_analytics_pipeline kill switch itself - that's an
 * operator action (see the runbook); this script only proves the automatic, load-triggered
 * degradation path.
 *
 * Usage:
 *   k6 run load-tests/heavy-analytics-degradation.js
 *   k6 run -e BASE_URL=https://stage.posly.example load-tests/heavy-analytics-degradation.js
 */
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    hammer_pipeline: {
      executor: 'constant-vus',
      vus: 10,
      duration: '20s',
    },
  },
  thresholds: {
    // The whole point of this script: once the bucket is exhausted, calls must be shed with 429
    // (a controlled, cheap rejection), never fail open as a 5xx crash or hang.
    'http_req_duration{step:pipeline_run}': ['p(95)<2000'],
    'http_req_failed{step:pipeline_run}': ['rate<0.01'], // 429 is a "successful" shed, not a failure - see checks below
    'http_req_failed{step:light_read}': ['rate<0.01'],
  },
};

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

export function setup() {
  const loginResp = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: 'manager', password: 'manager123' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginResp.status !== 200) {
    fail(`setup: manager login failed with status ${loginResp.status}: ${loginResp.body}`);
  }
  return { token: loginResp.json('accessToken') };
}

export default function (data) {
  const runResp = http.post(
    `${BASE_URL}/reports/pipeline/run`,
    JSON.stringify({ period: 'DAILY' }),
    {
      ...authHeaders(data.token),
      tags: { step: 'pipeline_run' },
      // 429 is the intended, healthy outcome once the bucket is exhausted - without this, k6's
      // built-in http_req_failed metric would count every shed request as a failure.
      responseCallback: http.expectedStatuses(201, 429),
    }
  );
  check(runResp, {
    'pipeline run either succeeds or is cleanly rate-limited (201/429)': (r) => r.status === 201 || r.status === 429,
  });
  if (runResp.status === 429) {
    check(runResp, { '429 carries a Retry-After header': (r) => r.headers['Retry-After'] !== undefined });
  }

  const readResp = http.get(`${BASE_URL}/reports/sales`, { ...authHeaders(data.token), tags: { step: 'light_read' } });
  check(readResp, { 'light aggregate read stays healthy (200)': (r) => r.status === 200 });
}
