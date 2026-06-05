# Flash Sale System

Single-tenant limited-drop / flash-sale system. Real buyers, guest checkout, sandbox PayUni. Built around correct behavior under concurrent stock contention + safe payment flow.

The interesting parts: the atomic stock-decrement under concurrent load, the order state machine with reservation TTL + expiry sweeper, and idempotent payment-callback handling.

## What's here / what isn't

In scope:
- Buyer drop page → 搶購 → guest checkout → PayUni payment → result page.
- Backend: concurrent stock decrement (no oversell), order state machine, idempotent webhook.
- PayUni sandbox only (no real money).
- Docker Compose deploy; k6 load test.

Out of scope (deliberately — keeps the surface small enough to verify):
- Multi-tenant / multi-merchant.
- Merchant dashboard / auth.
- Buyer accounts / login (guest checkout only).
- Real logistics (no 超商取貨 / 宅配 API). Just a shipping address + a manual SHIPPED flag flip.

## Stack

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.3, Spring Web/JPA/Validation, Flyway, Maven |
| DB / cache | PostgreSQL 16, Redis 7 |
| Tests | JUnit 5 + Testcontainers (real Postgres — not H2 or mocks) |
| Frontend | Next.js 14 (App Router) + HeroUI + Tailwind |
| Load test | k6 |
| Infra | Docker + Docker Compose |

## Repo layout

```
backend/        Spring Boot (Maven) — entities, services, controllers, tests
frontend/       Next.js + HeroUI — 3 pages
loadtest/       k6 script + run helper
docker-compose.yml
.env.example    (committed; real .env is gitignored)
```

## Running it locally

### 1. Configure env

```sh
cp .env.example .env
# edit .env — fill in PayUni test credentials when you have them
```

### 2. Bring up the stack

```sh
docker-compose up --build
```

This starts Postgres, Redis, the Spring Boot backend (Flyway migrations run on boot), and the Next.js frontend. Ports: backend `:8080`, frontend `:3000`, postgres `:5432`, redis `:6379`.

### 3. Seed a product (single-tenant — no admin UI by design)

```sh
curl -X POST http://localhost:8080/api/internal/products \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Demo Limited Drop",
    "price": 1000,
    "totalStock": 100,
    "dropStartsAt": "2026-06-05T00:00:00Z"
  }'
```

The response includes the product `id`. Use it in the URL below.

### 4. Open the buyer flow

Visit [http://localhost:3000/drop/1](http://localhost:3000/drop/1) (replace `1` with the seeded id). Click 搶購 to start checkout.

> **PayUni note:** until you paste PayUni's official test-environment docs and `PayUniProvider` is filled in (see [PayUni status](#payuni-status) below), the checkout submit creates the order + reserves stock, but no real PayUni redirect happens — the page falls through to the result view in a CREATED state. The rest of the flow (timeout/expiry, manual ship, callback dedup) is wired and testable.

## Concurrency — the bit that matters

The hot path is `POST /api/drops/{productId}/purchase`. Under a flash drop, thousands of buyers hit it simultaneously for the same product row.

The reservation is a **single atomic conditional UPDATE** in Postgres:

```sql
UPDATE product
   SET available_stock = available_stock - :qty
 WHERE id = :id AND available_stock >= :qty;
```

- If rows affected = 1 → reservation succeeded.
- If rows affected = 0 → sold out, reject (HTTP 409).
- No application-side lock; no read-modify-write race; no retry storm.

Why this and not the alternatives:

| Strategy | Tradeoff |
|---|---|
| Atomic conditional UPDATE (chosen) | Correct under any concurrency; throughput bounded by row-level lock contention in Postgres. Good enough for a single-product drop in the low thousands/sec. |
| Optimistic lock (`@Version` + retry) | Every loser retries — cascading failures under heavy contention. Kept on `Product` for *other* updates (name/price edits) but the hot stock path bypasses it. |
| Pessimistic lock (`SELECT … FOR UPDATE`) | Serializes all writers on the hot row; throughput collapses. |
| Redis pre-deduction (phase 2) | DECRBY via Lua = much higher throughput, but Redis/Postgres can diverge → outbox / reconciliation needed. `InventoryService` interface is the seam; not implemented in v1. |

The tradeoff comment block lives at the top of [`InventoryService.java`](backend/src/main/java/com/flashsale/inventory/InventoryService.java).

## Order state machine

```
CREATED ──pay──▶ PAID ──ship──▶ SHIPPED ──▶ COMPLETED
   │
   ├── payment timeout (15 min) ──▶ EXPIRED  (release reserved stock)
   └── buyer cancels (before pay) ─▶ CANCELLED (release reserved stock)
```

- `CREATED` is set with `expires_at = now + 15 min`; stock is reserved.
- [`OrderExpiryJob`](backend/src/main/java/com/flashsale/order/OrderExpiryJob.java) runs every 60s, flips overdue CREATED orders to EXPIRED, and returns stock via `InventoryService.release`.
- `SHIPPED` and `COMPLETED` are manual transitions (no real logistics — see scope above).

## Payment — idempotent webhook handling

PayUni's notify callback can arrive more than once (network retries, gateway retries on slow response). Idempotency comes from the database, not application logic:

- `payment_event` has `UNIQUE(provider, provider_txn_id)`.
- On every callback we `INSERT` an event row; a duplicate fails fast with `DataIntegrityViolationException`, which the controller absorbs.
- Only the first successful insert triggers `OrderService.markPaid`.
- We always return the ack PayUni expects, even on duplicate — otherwise the gateway keeps retrying.

State-transition `markPaid` is also internally idempotent (a second call on an already-PAID order is a no-op), as belt-and-braces.

The integration test [`PaymentIdempotencyTest`](backend/src/test/java/com/flashsale/payment/PaymentIdempotencyTest.java) covers both pieces against a real Postgres.

## PayUni status

> The `PayUniProvider` class is a **skeleton**. PayUni is a Taiwan-specific gateway with no published SDK; the request/response formats, the AES mode/padding/encoding, the checksum algorithm, and the callback field names all need to come from PayUni's official test-environment docs. The interface, properties config, idempotency machinery, and webhook controller are all wired and ready — only the encrypt/decrypt-and-sign body remains.

To finish PayUni:

1. Register a PayUni test merchant; collect `MerchantID`, `HashKey`, `HashIV`.
2. Paste PayUni's official docs (create-order endpoint, callback format, checksum spec, test URLs) into the project.
3. Implement [`PayUniProvider.createSession`](backend/src/main/java/com/flashsale/payment/payuni/PayUniProvider.java) and `verifyCallback` per those docs. The class Javadoc lists exactly what each method needs to do.

Until then, the rest of the system is fully runnable — order creation, stock decrement, expiry, manual ship/complete, callback dedup logic (a `curl`-simulated callback works once the provider is implemented).

## Exposing the callback to PayUni locally

PayUni cannot reach `localhost`. For end-to-end tests against the PayUni test environment, expose your backend with a tunnel:

```sh
# Option 1: ngrok
ngrok http 8080

# Option 2: cloudflared
cloudflared tunnel --url http://localhost:8080
```

Then set `PAYUNI_NOTIFY_URL` and `PAYUNI_RETURN_URL` in `.env` to the public tunnel URL + `/api/payments/payuni/callback`.

## Testing

```sh
cd backend
./mvnw test                                  # all tests, including concurrency
./mvnw test -Dtest=InventoryConcurrencyTest  # just the no-oversell gate
./mvnw test -Dtest=PaymentIdempotencyTest    # just the webhook idempotency check
```

Tests use Testcontainers — Docker must be running. They start a real Postgres and exercise the real Flyway migration; no H2 substitution.

The concurrency test fires 500 reservation attempts at a 50-stock product and asserts: exactly 50 succeed, `available_stock` ends at 0, never goes negative.

## Load testing — measured "no oversell" results

The k6 script ([`loadtest/flash-sale.js`](loadtest/flash-sale.js)) hammers `POST /api/drops/{id}/purchase` with hundreds of concurrent virtual users (default ECPay provider so `createSession` is pure local compute — the test isolates the atomic-UPDATE hot path, not Stripe API latency).

Two runs against a freshly-seeded product, both on Docker Desktop / Win11 / Postgres 16, both ran on 2026-06-06:

### Run A — stress test (oversaturate the inventory hot row)

Same VU shape (500 peak, 30s ramp + 60s hold + 10s rampdown, 100 stock), three configurations to compare. The third column is the headline: **adding a Redis Lua throttle in front of the DB drops 5xx errors from 610 to 0 while pushing throughput up ≈112×** — and no oversell in any column.

| | DB-only | + Redis throttle | delta |
|---|---|---|---|
| Total requests | 5,761 | **648,348** | **×112** |
| Successful purchases | 100 | 100 | same — no oversell |
| Sold-out (409) | 5,051 | 648,248 | absorbed at Redis layer |
| **5xx / 0-status errors** | **610** | **0** | **610 → 0** |
| Latency (avg) | 5,732ms | **61.5ms** | ÷93 |
| Latency (p95) | 59,997ms (DB pool saturated) | **143.4ms** | ÷419 |
| Final stock (DB) | 0 | 0 | both end consistent |
| Final stock (Redis) | n/a | 0 | matches DB |
| Oversell check | **PASS** | **PASS** | |

The DB-only column is the failure mode the Redis layer is built to absorb. Under 500 VU, HikariCP at `maximum-pool-size: 30` runs out of connections; the inventory guarantee survives (still exactly 100 succeed), but 610 unlucky requests die with `Could not open JPA EntityManager for transaction`.

With the Redis throttle in front, 648,248 of 648,348 attempts get a clean 409 from the Lua `check-and-DECR` script without ever asking Spring for a DB connection. The hot path only crosses into Postgres for the actual 100 winners. Connection pool stays cold, latency collapses, error count goes to literal zero.

### Run B — clean-pool throughput test

| | |
|---|---|
| VUs peak | **100** |
| Product stock | 200 |
| Duration | 10s ramp + 30s hold + 10s ramp-down |
| Total requests | 24,933 |
| **Successful purchases** | **200** |
| Sold-out (409) | 24,693 |
| 5xx / 0 status | 40 (0.16%) |
| Latency (purchase) | avg **96.3ms**, p95 **10.8ms** |
| **Oversell check** | **PASS — exactly 200 succeed, no oversell** |

Run B keeps the connection pool from saturating so the latency numbers are real. Sustained ≈ 500 req/s on a single-product hot row with the entire purchase path (`UPDATE product`, `INSERT orders`, build ECPay form) takes <100ms avg.

(`http_req_failed` reads close to 99% in both runs because k6 counts any non-2xx — including the expected 409 sold-out responses — as "failed." The meaningful number is `successful purchases` vs `product stock`.)

### Run it yourself

```sh
cd loadtest
PRODUCT_ID=<some-id> PRODUCT_STOCK=<n> k6 run flash-sale.js
# defaults: TARGET_VUS=500, RAMP_SEC=30, HOLD_SEC=60, PROVIDER=ECPAY
# override e.g.: TARGET_VUS=200 HOLD_SEC=30 k6 run flash-sale.js
```

Each run writes [`loadtest/summary.json`](loadtest/summary.json) for programmatic checks. Verdict line in stdout: `PASS — no oversell` or `FAIL — OVERSOLD`.

> **Windows gotcha**: the default `BASE_URL` is `http://[::1]:8080/api`. On Win11, k6/Go resolve `localhost` to IPv4 first; if anything else (legacy XAMPP/Apache is the typical culprit) is on `127.0.0.1:8080`, the load test silently hits THAT instead of Docker and every request returns 404. Pinning IPv6 sidesteps this.

## API

| Method | Path | Notes |
|---|---|---|
| `POST`   | `/api/internal/products` | Seed a product. No auth — internal only by deployment. |
| `DELETE` | `/api/internal/products/{id}` | Hard-delete if no order references it; soft-archive (status=ARCHIVED) otherwise. |
| `GET`    | `/api/drops` | List every non-archived drop. Home page uses this. |
| `GET`    | `/api/drops/{productId}` | Public drop info. |
| `POST`   | `/api/drops/{productId}/purchase` | The hot path. Atomic stock reservation + CREATED order + payment session. Provider in body picks the gateway. |
| `POST`   | `/api/payments/stripe/callback` | Stripe webhook. Raw-body HMAC via Stripe SDK. |
| `POST`   | `/api/payments/ecpay/callback` | ECPay ReturnURL. Form-encoded; CheckMacValue recomputed. Acks `"1\|OK"`. |
| `POST`   | `/api/payments/payuni/callback` | PayUni notify/return webhook. Currently skeleton-only (awaiting docs). |
| `GET`    | `/api/orders/{orderId}` | Order status (result page polls this). |
| `POST`   | `/api/orders/{orderId}/ship` | Manual SHIPPED flag (real logistics out of scope). |
| `POST`   | `/api/orders/{orderId}/complete` | Manual COMPLETED. |

## Data model

See [`V1__initial_schema.sql`](backend/src/main/resources/db/migration/V1__initial_schema.sql).

Money is `NUMERIC(19, 2)` ↔ Java `BigDecimal`. Never `double`/`float` anywhere in the codebase — this is a hard guardrail.

## Guardrails (active)

- Money is always `BigDecimal`.
- No secrets in code or git. `.env` is gitignored; `.env.example` has placeholders.
- Single tenant. No `tenant_id` anywhere.
- Guest checkout. No buyer/merchant auth.
- PayUni: implement strictly against docs. Never invent field names.
