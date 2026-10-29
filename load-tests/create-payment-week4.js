// k6 load test for Week 4: Asynchronous Processing with Kafka, Transactional Outbox & Processor
//
// In Week 4, payment creation is non-blocking:
//   1. Client sends POST /api/v1/payments through API Gateway (with JWT & Idempotency-Key)
//   2. payment-service atomically persists payment (CREATED) + outbox_events row in PostgreSQL
//   3. Returns 201 Created immediately
//   4. Background OutboxRelay claims unpublished outbox rows via SKIP LOCKED and publishes to Kafka
//   5. processor-service consumes from 'payment.created', simulates latency/bank decision, publishes to 'payment.processed'
//   6. payment-service consumes from 'payment.processed' and transitions status to SUCCEEDED or FAILED
//
// This test evaluates the gateway + payment-service ingestion throughput under concurrent
// background outbox polling, Kafka transmission, and processor-service execution.
//
// Usage:
//   k6 run -e RATE=500  -e DURATION=30s load-tests/create-payment-week4.js
//   k6 run -e RATE=1000 -e DURATION=30s -e MAX_VUS=3000 load-tests/create-payment-week4.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const RATE = parseInt(__ENV.RATE || '100');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000');
const NUM_MERCHANTS = parseInt(__ENV.NUM_MERCHANTS || '50');
const USE_IDEMPOTENCY = __ENV.USE_IDEMPOTENCY !== 'false'; // default true

const paymentsCreated = new Counter('payments_created_total');

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
  scenarios: { create_payments_week4: scenario },
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
      JSON.stringify({ name: `k6-week4-merchant-${i}` }),
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
  console.log(`setup: created ${merchants.length} merchants and tokens for Week 4 load test`);
  return { merchants };
}

const METHODS = ['CARD', 'UPI', 'NET_BANKING', 'WALLET'];

export default function (data) {
  const merchant = data.merchants[__VU % data.merchants.length];

  const payload = JSON.stringify({
    amount: Math.floor(Math.random() * 100000) + 100,
    currency: 'INR',
    method: METHODS[Math.floor(Math.random() * METHODS.length)],
    description: 'k6 week 4 async outbox load test',
  });

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${merchant.accessToken}`,
  };

  if (USE_IDEMPOTENCY) {
    headers['Idempotency-Key'] = `w4-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
  }

  const res = http.post(`${GATEWAY_URL}/api/v1/payments`, payload, { headers });

  const passed = check(res, {
    'status is 201': (r) => r.status === 201,
    'status is CREATED': (r) => {
      try {
        return r.json().status === 'CREATED';
      } catch (e) {
        return false;
      }
    },
  });

  if (passed) {
    paymentsCreated.add(1);
  }
}
