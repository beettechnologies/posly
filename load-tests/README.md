# Load tests

[k6](https://k6.io) scripts validating the acceptance criteria in
[docs/runbooks/capacity-scale-incidents.md](../docs/runbooks/capacity-scale-incidents.md):
the service scales/maintains SLOs under peak traffic, and heavy analytics endpoints degrade
gracefully (rather than falling over) once overloaded.

## Install k6

```bash
brew install k6
```

(See [k6.io/docs/get-started/installation](https://k6.io/docs/get-started/installation/) for other platforms.)

## Scripts

### `checkout-flow.js`

Simulates peak checkout traffic: ramps up to `VUS` concurrent cashiers, each logging in, opening
a cart, adding an item, and checking out, holds at peak, then ramps down. Asserts p95 latency and
error-rate thresholds on the checkout path.

```bash
k6 run load-tests/checkout-flow.js
k6 run -e BASE_URL=https://stage.posly.example -e VUS=100 -e DURATION=5m load-tests/checkout-flow.js
```

| Env var    | Default                 | Meaning                              |
|------------|--------------------------|---------------------------------------|
| `BASE_URL` | `http://localhost:8080` | Target server                         |
| `VUS`      | `20`                     | Peak concurrent virtual users         |
| `DURATION` | `2m`                     | How long to hold at peak (after a 30s ramp-up, before a 30s ramp-down) |

### `heavy-analytics-degradation.js`

Hammers `POST /reports/pipeline/run` - a heavy, on-demand reporting endpoint - well past its
shared rate limit (5 calls/minute across all callers, see
`server/src/main/kotlin/com/beettechnologies/posly/capacity/HeavyAnalyticsGuard.kt`) and asserts
the server sheds excess load with `429 Too Many Requests` (never a 5xx or a hang), while a plain
aggregate read (`GET /reports/sales`) keeps returning `200` throughout.

```bash
k6 run load-tests/heavy-analytics-degradation.js
```

This script does **not** flip the `heavy_analytics_pipeline` kill switch itself - that's a manual
operator action during a real incident (see the runbook). It only proves the automatic,
load-triggered rate-limit path.

## Running against a local instance

Both scripts assume the target server's default seeded demo users exist (`admin`/`admin123`,
`manager`/`manager123`, `cashier`/`cashier123` - seeded once, on an empty user table, by
`UserService`'s init block). That's true for a fresh local/dev instance:

```bash
./gradlew :server:run
# in another terminal:
k6 run load-tests/checkout-flow.js
```

**Do not point these scripts at a real staging/prod environment unless those demo accounts still
exist there** (they shouldn't, in a properly hardened environment - see
`SECURITY_COMPLIANCE.md`-style precedent elsewhere in this repo). Point-in-time validated against
a local instance during development; not run against a live AWS deployment as part of this change
(no live environment was available to test against).

## Interpreting results

- `checkout-flow.js` failing its `http_req_duration{step:checkout}` p95 threshold means the
  checkout path is too slow under the simulated load - check whether ECS has scaled out (the
  `posly-<env>-ecs-cpu-approaching-ceiling` / `-ecs-at-max-capacity` CloudWatch alarms, see
  `infra/terraform/modules/alerting`) or whether the database is the bottleneck.
- `heavy-analytics-degradation.js` failing its `pipeline run either succeeds or is cleanly
  rate-limited` check (a non-201/429 status appearing) means the rate limiter itself failed open
  under load - a bug, not an overload symptom - and should be treated as a regression, not tuned
  around by raising thresholds.
