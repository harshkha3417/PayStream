// k6 load test for payment creation THROUGH THE GATEWAY (Week 2+), with real JWTs.
// Unlike load-tests/create-payment.js (Week 1, talks to payment-service directly,
// one merchant, no auth), this script:
//   1. creates NUM_MERCHANTS real merchants via the gateway (setup phase, runs once)
//   2. gets a real token per merchant
//   3. round-robins (merchant, token) pairs across VUs during the actual load test
//
// Why multiple merchants: the gateway's rate limiter caps each merchant at
// RATE_LIMIT_PER_SECOND (20/s by default). Hammering one merchant's token at
// RATE=1000 would mostly measure the rate limiter firing, not the gateway's
// real overhead. With the default NUM_MERCHANTS=50 and the default 20/s limit,
// the aggregate ceiling is 50 * 20 = 1000 req/s — matching Week 1's RATE=1000
// test, so the two runs are actually comparable.
//
// Usage examples (run from the repo root, full stack already up):
//   k6 run -e RATE=100  -e DURATION=30s load-tests/create-payment-gateway.js
//   k6 run -e RATE=1000 -e DURATION=30s -e MAX_VUS=3000 load-tests/create-payment-gateway.js
//   k6 run -e RATE=1000 -e DURATION=30s -e NUM_MERCHANTS=100 load-tests/create-payment-gateway.js
//
// If you want to isolate gateway overhead from the rate limiter entirely,
// temporarily raise RATE_LIMIT_PER_SECOND in docker-compose.yml's gateway
// service (e.g. to "100000"), restart the gateway, run the test, then put
// it back to 20 — don't leave it raised, that defeats Week 2's point.

import http from 'k6/http';
import { check } from 'k6';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const RATE = parseInt(__ENV.RATE || '100');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000');
const NUM_MERCHANTS = parseInt(__ENV.NUM_MERCHANTS || '50');

const scenario = __ENV.VUS
  ? { executor: 'constant-vus', vus: parseInt(__ENV.VUS), duration: DURATION }
  : {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 500),
      maxVUs: MAX_VUS,
    };

export const options = {
  scenarios: { create_payments: scenario },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
  },
};

// Runs ONCE, before the load test, outside of VU timing — not part of the
// measured results. Creates every merchant and fetches every token up front
// so the actual test only ever calls POST /api/v1/payments.
export function setup() {
  const merchants = [];
  for (let i = 0; i < NUM_MERCHANTS; i++) {
    const createRes = http.post(
      `${GATEWAY_URL}/api/v1/merchants`,
      JSON.stringify({ name: `k6-load-test-merchant-${i}` }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (createRes.status !== 201 && createRes.status !== 200) {
      throw new Error(`setup: failed to create merchant ${i}: ${createRes.status} ${createRes.body}`);
    }
    const { merchantId, apiKey } = createRes.json();

    const tokenRes = http.post(
      `${GATEWAY_URL}/api/v1/auth/token`,
      JSON.stringify({ merchantId, apiKey }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (tokenRes.status !== 200) {
      throw new Error(`setup: failed to get token for merchant ${i}: ${tokenRes.status} ${tokenRes.body}`);
    }
    const { accessToken } = tokenRes.json();

    merchants.push({ merchantId, accessToken });
  }
  console.log(`setup: created ${merchants.length} merchants and tokens`);
  return { merchants };
}

const METHODS = ['CARD', 'UPI', 'NET_BANKING', 'WALLET'];

export default function (data) {
  const merchant = data.merchants[__VU % data.merchants.length];

  const payload = JSON.stringify({
    amount: Math.floor(Math.random() * 100000) + 100,
    currency: 'INR',
    method: METHODS[Math.floor(Math.random() * METHODS.length)],
    description: 'k6 gateway load test',
  });

  const res = http.post(`${GATEWAY_URL}/api/v1/payments`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${merchant.accessToken}`,
    },
  });

  check(res, { 'status is 201': (r) => r.status === 201 });
}