/**
 * Simulates peak checkout traffic: many concurrent cashiers each logging in, opening a cart,
 * adding an item, and checking out. Validates the golden path holds throughput/latency SLOs
 * under load - the "given load increases, service scales within thresholds and maintains SLOs"
 * half of docs/runbooks/capacity-scale-incidents.md's acceptance criteria.
 *
 * Usage:
 *   k6 run load-tests/checkout-flow.js
 *   k6 run -e BASE_URL=https://stage.posly.example load-tests/checkout-flow.js
 *   k6 run -e VUS=100 -e DURATION=5m load-tests/checkout-flow.js
 *
 * Requires a server with the default seeded users (admin/manager/cashier - see
 * UserService's init block) - true for a fresh in-memory dev instance, NOT for a real
 * environment where those demo accounts should have been rotated or removed.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '2m';

const checkoutFailures = new Counter('checkout_failures');

export const options = {
  scenarios: {
    peak_checkout: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: VUS },       // ramp up to simulated peak
        { duration: DURATION, target: VUS },     // hold at peak
        { duration: '30s', target: 0 },          // ramp down
      ],
    },
  },
  thresholds: {
    // The checkout path (login -> cart -> item -> checkout) end to end, p95 under 1.5s.
    'http_req_duration{step:checkout}': ['p(95)<1500'],
    'http_req_duration{step:login}': ['p(95)<500'],
    'http_req_failed': ['rate<0.01'],
    'checkout_failures': ['count<1'],
  },
};

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

export function setup() {
  const loginResp = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: 'admin', password: 'admin123' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (loginResp.status !== 200) {
    fail(`setup: admin login failed with status ${loginResp.status}: ${loginResp.body}`);
  }
  const adminToken = loginResp.json('accessToken');

  const storeResp = http.post(
    `${BASE_URL}/stores`,
    JSON.stringify({
      name: 'Load Test Store',
      address: { line1: '1 Main St', city: 'NY', postalCode: '10001', country: 'US' },
      timezone: 'America/New_York',
      currency: 'USD',
    }),
    authHeaders(adminToken)
  );
  if (storeResp.status !== 201) {
    fail(`setup: store creation failed with status ${storeResp.status}: ${storeResp.body}`);
  }
  const storeId = storeResp.json('id');

  const productResp = http.post(
    `${BASE_URL}/products`,
    JSON.stringify({ sku: `LOAD-TEST-${Date.now()}`, name: 'Load Test Widget', price: 9.99 }),
    authHeaders(adminToken)
  );
  if (productResp.status !== 201) {
    fail(`setup: product creation failed with status ${productResp.status}: ${productResp.body}`);
  }
  const productId = productResp.json('id');

  return { storeId, productId };
}

export default function (data) {
  const loginResp = http.post(
    `${BASE_URL}/auth/login`,
    JSON.stringify({ username: 'cashier', password: 'cashier123' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { step: 'login' } }
  );
  if (!check(loginResp, { 'login succeeded': (r) => r.status === 200 })) {
    checkoutFailures.add(1);
    return;
  }
  const token = loginResp.json('accessToken');

  const cartResp = http.post(
    `${BASE_URL}/carts`,
    JSON.stringify({ storeId: data.storeId }),
    { ...authHeaders(token), tags: { step: 'create_cart' } }
  );
  if (!check(cartResp, { 'cart created': (r) => r.status === 201 })) {
    checkoutFailures.add(1);
    return;
  }
  const cartId = cartResp.json('id');

  const itemResp = http.post(
    `${BASE_URL}/carts/${cartId}/items`,
    JSON.stringify({ productId: data.productId, quantity: 1 }),
    { ...authHeaders(token), tags: { step: 'add_item' } }
  );
  if (!check(itemResp, { 'item added': (r) => r.status === 200 || r.status === 201 })) {
    checkoutFailures.add(1);
    return;
  }

  const checkoutResp = http.post(
    `${BASE_URL}/carts/${cartId}/checkout`,
    JSON.stringify({ idempotencyKey: `${__VU}-${__ITER}-${Date.now()}` }),
    { ...authHeaders(token), tags: { step: 'checkout' } }
  );
  if (!check(checkoutResp, { 'checkout succeeded': (r) => r.status === 201 })) {
    checkoutFailures.add(1);
  }
}
