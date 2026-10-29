// k6 load test for Week 7: Performance & Observability
//
// Tests the full PayStream cluster across all 12 services + Prometheus/Grafana:
//   1. API Gateway (port 8080) with Netty & Redis rate limiting
//   2. payment-service with outbox relay & PostgreSQL
//   3. Apache Kafka (KRaft) broadcasting events
//   4. processor-service simulating bank outcomes
//   5. refund-service maintaining local read model
//   6. webhook-service signing HMAC payloads
//   7. ledger-service double-entry accounting engine
//   8. audit-service append-only immutable audit trail
//
// Usage:
//   k6 run -e RATE=500  -e DURATION=30s load-tests/create-payment-week7.js
//   k6 run -e RATE=1000 -e DURATION=30s -e MAX_VUS=3000 load-tests/create-payment-week7.js

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const RATE = parseInt(__ENV.RATE || '1000');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '3000');
const NUM_MERCHANTS = parseInt(__ENV.NUM_MERCHANTS || '20');
const USE_IDEMPOTENCY = __ENV.USE_IDEMPOTENCY !== 'false';

const paymentsCreated = new Counter('payments_created_total');
const status201 = new Counter('status_201_total');
const status429 = new Counter('status_429_total');
const status500 = new Counter('status_500_total');
const status504 = new Counter('status_504_total');
const statusOther = new Counter('status_other_total');

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
  setupTimeout: '180s',
  scenarios: { create_payments_week7: scenario },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
};

export function setup() {
  const merchants = [];
  const suffix = Date.now();
  for (let i = 0; i < NUM_MERCHANTS; i++) {
    let createRes;
    for (let retry = 0; retry < 3; retry++) {
      createRes = http.post(
        `${GATEWAY_URL}/api/v1/merchants`,
        JSON.stringify({ name: `k6-w7-${suffix}-${i}` }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '30s' },
      );
      if (createRes.status === 201 || createRes.status === 200) break;
    }
    if (createRes.status !== 201 && createRes.status !== 200) {
      throw new Error(`setup: failed to create merchant ${i}: ${createRes.status} ${createRes.body}`);
    }
    const { merchantId, apiKey } = createRes.json();

    let tokenRes;
    for (let retry = 0; retry < 3; retry++) {
      tokenRes = http.post(
        `${GATEWAY_URL}/api/v1/auth/token`,
        JSON.stringify({ merchantId, apiKey }),
        { headers: { 'Content-Type': 'application/json' }, timeout: '30s' },
      );
      if (tokenRes.status === 200) break;
    }
    if (tokenRes.status !== 200) {
      throw new Error(`setup: failed to get token for merchant ${i}: ${tokenRes.status} ${tokenRes.body}`);
    }
    const { accessToken } = tokenRes.json();
    merchants.push({ merchantId, accessToken });
  }
  console.log(`setup: successfully created ${merchants.length} merchants and tokens for Week 7 load test`);
  return { merchants };
}

const METHODS = ['CARD', 'UPI', 'NET_BANKING', 'WALLET'];

export default function (data) {
  const merchant = data.merchants[__VU % data.merchants.length];

  const payload = JSON.stringify({
    amount: Math.floor(Math.random() * 100000) + 100,
    currency: 'INR',
    method: METHODS[Math.floor(Math.random() * METHODS.length)],
    description: 'k6 week 7 cluster benchmark',
  });

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${merchant.accessToken}`,
  };

  if (USE_IDEMPOTENCY) {
    headers['Idempotency-Key'] = `w7-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
  }

  const res = http.post(`${GATEWAY_URL}/api/v1/payments`, payload, { headers });

  if (res.status === 201) status201.add(1);
  else if (res.status === 429) status429.add(1);
  else if (res.status === 500) status500.add(1);
  else if (res.status === 504) status504.add(1);
  else statusOther.add(1);

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
