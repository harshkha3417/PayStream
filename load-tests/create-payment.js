// Usage examples (run from the repo root):
//   k6 run -e RATE=100  -e DURATION=30s load-tests/create-payment.js      # 100 requests/second
//   k6 run -e RATE=1000 -e DURATION=30s -e MAX_VUS=3000 load-tests/create-payment.js
//   k6 run -e VUS=500   -e DURATION=30s load-tests/create-payment.js      # 500 simultaneous users
//
// RATE  = requests per second, no matter how slow the server is (open model; finds the breaking point)
// VUS   = fixed number of users who each send the next request only after the previous one returns (closed model)

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const DURATION = __ENV.DURATION || '30s';
const RATE = parseInt(__ENV.RATE || '100');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '2000');

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
    http_req_failed: ['rate<0.01'],                    // less than 1% errors
    http_req_duration: ['p(95)<500', 'p(99)<1000'],    // ms
  },
};

const METHODS = ['CARD', 'UPI', 'NET_BANKING', 'WALLET'];

export default function () {
  const payload = JSON.stringify({
    amount: Math.floor(Math.random() * 100000) + 100,
    currency: 'INR',
    method: METHODS[Math.floor(Math.random() * METHODS.length)],
    description: 'k6 load test',
  });

  const res = http.post(`${BASE_URL}/api/v1/payments`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Merchant-Id': `merchant_${(__VU % 50) + 1}`,
    },
  });

  check(res, { 'status is 201': (r) => r.status === 201 });
}