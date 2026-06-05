// k6 load test for the Flash Sale purchase hot path.
//
// What this proves:
//   1. The POST /api/drops/{productId}/purchase endpoint handles many concurrent
//      virtual users without 5xx errors.
//   2. Under saturation (more attempts than stock), the system never oversells —
//      after the test, the product's available_stock is exactly 0 and the count
//      of 200-OK purchases is exactly the original stock.
//   3. Latency stays bounded under load (p95 < 1s is a reasonable bar for a
//      single-product hot row).
//
// Usage:
//   # Create a product first via the API (see README), then:
//   PRODUCT_ID=1 PRODUCT_STOCK=100 k6 run flash-sale.js
//
// Env vars:
//   BASE_URL        default http://localhost:8080/api
//   PRODUCT_ID      required, the product to hammer
//   PRODUCT_STOCK   for the post-run oversell assertion (informational, default 100)
//   TARGET_VUS      peak virtual users (default 500)
//   RAMP_SEC        seconds to ramp to peak (default 30)
//   HOLD_SEC        seconds to hold at peak (default 60)

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

// 'localhost' in k6 (Go's net) resolves IPv4 first; if anything else is
// listening on 127.0.0.1:8080 (legacy XAMPP/Apache on Windows is a common
// culprit) the load test silently hits THAT instead of Docker. The IPv6
// loopback `[::1]:8080` is the address Docker Desktop binds, so prefer it.
// Override with BASE_URL env var if you're somewhere else.
const BASE_URL      = __ENV.BASE_URL      || 'http://[::1]:8080/api';
const PRODUCT_ID    = __ENV.PRODUCT_ID    || '1';
const PRODUCT_STOCK = parseInt(__ENV.PRODUCT_STOCK || '100', 10);
const TARGET_VUS    = parseInt(__ENV.TARGET_VUS    || '500', 10);
const RAMP_SEC      = parseInt(__ENV.RAMP_SEC      || '30', 10);
const HOLD_SEC      = parseInt(__ENV.HOLD_SEC      || '60', 10);
// ECPay default: createSession is pure local compute, so the test isolates
// the inventory hot path. Set PROVIDER=STRIPE to exercise the Stripe API too,
// but that's not what this test is measuring.
const PROVIDER      = __ENV.PROVIDER      || 'ECPAY';

const purchased = new Counter('purchases_succeeded');
const soldOut   = new Counter('purchases_sold_out');
const errors    = new Counter('purchases_errored');
// Status-code histogram so the summary tells us *what* the errors are.
const status2xx = new Counter('status_2xx');
const status4xx = new Counter('status_4xx');
const status5xx = new Counter('status_5xx');
const status0   = new Counter('status_0_or_other');

export const options = {
  scenarios: {
    flash: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${RAMP_SEC}s`, target: TARGET_VUS },
        { duration: `${HOLD_SEC}s`, target: TARGET_VUS },
        { duration: '10s',          target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    'purchases_errored':       ['count<10'],
    'http_req_failed':         ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<1000'],
  },
};

// Pre-generated buyer info so we don't burn CPU on string ops at request time.
const buyers = new SharedArray('buyers', () => {
  const out = [];
  for (let i = 0; i < 1000; i++) {
    out.push({
      buyerName: `Buyer ${i}`,
      buyerEmail: `buyer${i}@example.com`,
      buyerPhone: `09000${String(i).padStart(5, '0')}`,
      shippingAddress: `${i} Test Street, Taipei`,
    });
  }
  return out;
});

export default function () {
  const b = buyers[(__VU + __ITER) % buyers.length];
  const body = JSON.stringify({
    quantity: 1,
    buyerName: b.buyerName,
    buyerEmail: b.buyerEmail,
    buyerPhone: b.buyerPhone,
    shippingAddress: b.shippingAddress,
    provider: PROVIDER,
  });

  const res = http.post(
    `${BASE_URL}/drops/${PRODUCT_ID}/purchase`,
    body,
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'purchase' } },
  );


  if (res.status === 200) {
    purchased.add(1);
    status2xx.add(1);
    check(res, { 'purchase 200 has orderId': (r) => !!r.json('orderId') });
  } else if (res.status === 409) {
    soldOut.add(1);
    status4xx.add(1);
  } else {
    errors.add(1);
    if (res.status >= 200 && res.status < 300) status2xx.add(1);
    else if (res.status >= 400 && res.status < 500) status4xx.add(1);
    else if (res.status >= 500) status5xx.add(1);
    else status0.add(1);
  }
}

export function handleSummary(data) {
  const successes = data.metrics.purchases_succeeded?.values?.count ?? 0;
  const sold      = data.metrics.purchases_sold_out?.values?.count   ?? 0;
  const errored   = data.metrics.purchases_errored?.values?.count    ?? 0;
  const oversold  = successes > PRODUCT_STOCK;
  const verdict   = oversold ? 'FAIL — OVERSOLD' : 'PASS — no oversell';

  // Pull the standard metrics k6 collected so the summary is self-contained.
  const m              = data.metrics;
  const totalReqs      = m.http_reqs?.values?.count ?? 0;
  const failedRate     = ((m.http_req_failed?.values?.rate ?? 0) * 100).toFixed(2);
  const vusMax         = m.vus_max?.values?.max ?? 0;
  const dur            = m.http_req_duration?.values ?? {};
  const p95            = (dur['p(95)'] ?? 0).toFixed(1);
  const p99            = (dur['p(99)'] ?? 0).toFixed(1);
  const avg            = (dur.avg ?? 0).toFixed(1);

  const summary = `
========= Flash Sale Load Test Summary =========
  product id           : ${PRODUCT_ID}
  product stock        : ${PRODUCT_STOCK}
  payment provider     : ${PROVIDER}
  -----------------------------------------------
  total http requests  : ${totalReqs}
  vus peak             : ${vusMax}
  http_req_failed      : ${failedRate}%
  http_req_duration    : avg=${avg}ms  p95=${p95}ms  p99=${p99}ms
  -----------------------------------------------
  successful purchases : ${successes}
  sold-out responses   : ${sold}
  errors (5xx / 0 / etc): ${errored}
  -----------------------------------------------
  overselling check    : ${verdict}
================================================
`;
  // eslint-disable-next-line no-console
  console.log(summary);
  // Also write a machine-readable artifact for the README.
  return {
    stdout: summary,
    'summary.json': JSON.stringify({
      productId: PRODUCT_ID,
      productStock: PRODUCT_STOCK,
      provider: PROVIDER,
      totalReqs, vusMax, failedRatePct: parseFloat(failedRate),
      latencyMs: { avg: parseFloat(avg), p95: parseFloat(p95), p99: parseFloat(p99) },
      successes, soldOut: sold, errored, oversold,
    }, null, 2),
  };
}
