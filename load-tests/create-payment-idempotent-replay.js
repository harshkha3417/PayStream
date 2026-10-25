// k6 load test: every request reuses the SAME Idempotency-Key (one fixed key
// per merchant, pre-warmed in setup()), with the exact same body every time.
// This is the opposite extreme from create-payment-idempotent-cold.js: after
// the very first request per merchant, every subsequent one should hit
// IdempotencyService's Redis fast path and never touch Postgres at all.
//
// This is the test that actually proves (or disproves) architecture.md
// decision #19 — "Redis is the fast path, Postgres is the source of truth" —
// by measuring whether replay really is cheaper than a fresh claim, not just
// asserting that it should be.
//
// Compare this run's p95/p99 against the SAME RATE in
// create-payment-idempotent-cold.js. If replay isn't meaningfully faster,
// that's a real finding worth chasing (e.g. Redis round-trip cost, or the
// fast path isn't actually being hit — check `redis-cli keys 'idem:*'`
// and `redis-cli get <one of those keys>` to confirm the warm keys exist).
//
// Usage: same env vars as the other two gateway load-test scripts
//   k6 run -e RATE=500 -e DURATION=30s load-tests/create-payment-idempotent-replay.js

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

// Fixed, deterministic body per merchant — MUST be byte-for-byte identical
// between the warm-up call in setup() and every replay call in the default
// function, or IdempotencyService will see a different request hash and
// reject the replay with 422 instead of serving it from cache.
function fixedPayloadFor(i) {
  return JSON.stringify({
    amount: 10000 + i,
    currency: 'INR',
    method: 'UPI',
    description: `k6 idempotent-replay warm payment ${i}`,
  });
}

export function setup() {
  const merchants = [];
  for (let i = 0; i < NUM_MERCHANTS; i++) {
    const createRes = http.post(
      `${GATEWAY_URL}/api/v1/merchants`,
      JSON.stringify({ name: `k6-idem-replay-merchant-${i}` }),
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
    const idempotencyKey = `warm-key-${i}`;

    // Warm the key NOW, outside the measured window: first call actually
    // creates the payment and populates both Postgres and Redis. Every call
    // during the real test below should hit Redis only.
    const warmRes = http.post(`${GATEWAY_URL}/api/v1/payments`, fixedPayloadFor(i), {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
        'Idempotency-Key': idempotencyKey,
      },
    });
    if (warmRes.status !== 201) {
      throw new Error(`setup: failed to warm idempotency key for merchant ${i}: ${warmRes.status} ${warmRes.body}`);
    }

    merchants.push({ merchantId, accessToken, idempotencyKey });
  }
  console.log(`setup: created and warmed ${merchants.length} merchants`);
  return { merchants };
}

export default function (data) {
  const i = __VU % data.merchants.length;
  const merchant = data.merchants[i];

  const res = http.post(`${GATEWAY_URL}/api/v1/payments`, fixedPayloadFor(i), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${merchant.accessToken}`,
      'Idempotency-Key': merchant.idempotencyKey, // always the same key -> always a replay
    },
  });

  check(res, { 'status is 201': (r) => r.status === 201 });
}