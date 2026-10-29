// End-to-End Stress Test for Week 7 (Realistic Traffic Mix)
//
// 70% Cached Reads (GET /api/v1/payments/{id} & GET /api/v1/ledger/balance)
// 30% Transactional Writes (POST /api/v1/payments)
//
// Evaluates the full optimized architecture:
//   - Java 21 Virtual Threads
//   - Redis Read Caching & Write-Through Invalidation
//   - PostgreSQL JDBC Multi-Row Batching & Pool Right-Sizing
//   - Kafka 4-Partition Parallel Consumer Processing

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const RATE = parseInt(__ENV.RATE || '200');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '1000');
const NUM_MERCHANTS = parseInt(__ENV.NUM_MERCHANTS || '20');

const writeSuccess = new Rate('write_success_rate');
const readCacheSuccess = new Rate('read_cache_success_rate');
const writeDuration = new Trend('write_duration_ms');
const readDuration = new Trend('read_duration_ms');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    e2e_stress: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RATE, 200),
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    write_success_rate: ['rate>0.90'],
    read_cache_success_rate: ['rate>0.95'],
    read_duration_ms: ['p(95)<200'],
  },
};

export function setup() {
  const merchants = [];
  const suffix = Date.now();
  console.log(`Setting up ${NUM_MERCHANTS} merchants and seed payments...`);

  for (let i = 0; i < NUM_MERCHANTS; i++) {
    // 1. Create merchant
    const createRes = http.post(
      `${GATEWAY_URL}/api/v1/merchants`,
      JSON.stringify({ name: `e2e-m-${suffix}-${i}` }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '30s' }
    );
    if (createRes.status !== 200 && createRes.status !== 201) {
      throw new Error(`Merchant creation failed: ${createRes.status}`);
    }
    const { merchantId, apiKey } = createRes.json();

    // 2. Obtain token
    const tokenRes = http.post(
      `${GATEWAY_URL}/api/v1/auth/token`,
      JSON.stringify({ merchantId, apiKey }),
      { headers: { 'Content-Type': 'application/json' }, timeout: '30s' }
    );
    if (tokenRes.status !== 200) {
      throw new Error(`Token request failed: ${tokenRes.status}`);
    }
    const { accessToken } = tokenRes.json();

    // 3. Seed initial payment to populate cache
    const payRes = http.post(
      `${GATEWAY_URL}/api/v1/payments`,
      JSON.stringify({
        amount: 5000,
        currency: 'INR',
        method: 'CARD',
        description: 'Seed payment for cache test',
      }),
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
          'Idempotency-Key': `seed-${suffix}-${i}`,
        },
        timeout: '30s',
      }
    );

    let seedPaymentId = null;
    if (payRes.status === 201) {
      seedPaymentId = payRes.json().id;
    }

    merchants.push({
      merchantId,
      accessToken,
      seedPaymentId,
    });
  }

  console.log(`Setup complete. Seeded ${merchants.length} merchants.`);
  return { merchants };
}

export default function (data) {
  const merchant = data.merchants[__VU % data.merchants.length];
  const rand = Math.random();

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${merchant.accessToken}`,
  };

  if (rand < 0.30) {
    // 30% Transactional Writes
    const payload = JSON.stringify({
      amount: Math.floor(Math.random() * 10000) + 100,
      currency: 'INR',
      method: 'CARD',
      description: 'E2E benchmark payment',
    });
    headers['Idempotency-Key'] = `e2e-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;

    const start = Date.now();
    const res = http.post(`${GATEWAY_URL}/api/v1/payments`, payload, { headers, timeout: '15s' });
    writeDuration.add(Date.now() - start);

    const isSuccess = res.status === 201;
    writeSuccess.add(isSuccess);

    check(res, {
      'write status is 201': (r) => r.status === 201,
    });
  } else if (rand < 0.65) {
    // 35% Cached Payment Reads
    if (merchant.seedPaymentId) {
      const start = Date.now();
      const res = http.get(`${GATEWAY_URL}/api/v1/payments/${merchant.seedPaymentId}`, {
        headers,
        timeout: '10s',
      });
      readDuration.add(Date.now() - start);

      const isSuccess = res.status === 200;
      readCacheSuccess.add(isSuccess);

      check(res, {
        'cached payment read is 200': (r) => r.status === 200,
      });
    }
  } else {
    // 35% Cached Ledger Balance Reads
    const start = Date.now();
    const res = http.get(`${GATEWAY_URL}/api/v1/ledger/balance?currency=INR`, {
      headers,
      timeout: '10s',
    });
    readDuration.add(Date.now() - start);

    const isSuccess = res.status === 200;
    readCacheSuccess.add(isSuccess);

    check(res, {
      'cached ledger balance is 200': (r) => r.status === 200,
    });
  }
}
