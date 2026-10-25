// k6 load test: a FRESH, UNIQUE Idempotency-Key on every single request.
// This measures the worst case of Week 3's idempotency feature — every
// request takes the full "cold claim" path: Redis miss, Postgres
// INSERT ... ON CONFLICT (a real row gets created every time, never a
// conflict), run the action, write the result back to both Postgres and
// Redis. Nothing ever gets replayed in this test — that's deliberate, it's
// what isolates "the cost of claiming a key" from "the cost of replaying one"
// (see create-payment-idempotent-replay.js for the other half of that story).
//
// Compare this run's p95/p99 directly against the SAME RATE in
// load-tests/create-payment-gateway.js (Week 2, no Idempotency-Key at all)
// — the gap between them is what idempotency-key handling actually costs
// when nothing is ever reused.
//
// Usage: same env vars as create-payment-gateway.js
//   k6 run -e RATE=500 -e DURATION=30s load-tests/create-payment-idempotent-cold.js

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

export function setup() {
  const merchants = [];
  for (let i = 0; i < NUM_MERCHANTS; i++) {
    const createRes = http.post(
      `${GATEWAY_URL}/api/v1/merchants`,
      JSON.stringify({ name: `k6-idem-cold-merchant-${i}` }),
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
    description: 'k6 idempotent-cold load test',
  });

  const res = http.post(`${GATEWAY_URL}/api/v1/payments`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${merchant.accessToken}`,
      // A fresh UUID every single call — never reused, so every request
      // takes the full claim-and-create path, never the replay path.
      'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}-${Math.random()}`,
    },
  });

  check(res, { 'status is 201': (r) => r.status === 201 });
}