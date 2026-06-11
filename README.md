# Flash Sale System

Single-tenant limited-drop / flash-sale system. Real buyers, guest checkout, sandbox PayUni. Built around correct behavior under concurrent stock contention + safe payment flow.

The interesting parts: the atomic stock-decrement under concurrent load, the order state machine with reservation TTL + expiry sweeper, and idempotent payment-callback handling.

## What's here / what isn't

In scope:
- Buyer drop page → flash-buy → guest checkout → PayUni payment → result page.
- Backend: concurrent stock decrement (no oversell), order state machine, idempotent webhook.
- PayUni sandbox only (no real money).
- Docker Compose deploy; k6 load test.

Out of scope (deliberately — keeps the surface small enough to verify):
- Multi-tenant / multi-merchant.
- Merchant dashboard / auth.
- Buyer accounts / login (guest checkout only).
- Real logistics (no convenience-store pickup / home-delivery API). Just a shipping address + a manual SHIPPED flag flip.

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

Visit [http://localhost:3000/drop/1](http://localhost:3000/drop/1) (replace `1` with the seeded id). Click flash-buy to start checkout.

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

## Stock consistency — the two-store invariant

When Redis sits in front of Postgres as a pre-deduction throttle, two stores hold "current stock" for the same product. They must stay in sync or one of two failure modes follows:

- **Redis over-reports** → buyer wins a Redis reservation that Postgres then rejects. Caught at the DB final defense; user sees `409`, the Redis counter is refunded so the two re-converge. No oversell.
- **Redis under-reports** → buyer is rejected at the throttle while Postgres still has units. **Silent undersell** — the buyer pool effectively shrinks. This is the failure class to design against.

The invariant the system enforces:

```
redis["stock:" + productId]  ==  product.available_stock
```

This is correct as-is because `available_stock` is itself decremented atomically on every reservation and incremented on every release, so it already nets out every active CREATED + PAID reservation. Redis just mirrors it.

### Maintenance rules

1. **Release writes both stores in fixed order.** [`RedisInventoryService.release()`](backend/src/main/java/com/flashsale/inventory/RedisInventoryService.java) updates Postgres first (source of truth), Redis second (best-effort). All three release call sites — `OrderExpiryJob.sweep`, `OrderService.cancel`, the `OrderService.createOrder` compensation catch — go through this method via the `InventoryService` interface, so the rule applies uniformly with no scattered raw Redis calls in the scheduler.

2. **Partial-failure on Redis INCR is logged loud.** If the Redis INCRBY throws `DataAccessException`, Postgres is correct but Redis under-reports. The code logs at `ERROR` with `productId` and `quantity` so a dashboard / alerting rule can fire on a non-zero rate.

3. **Reconciliation safety net.** [`RedisInventoryService.reconcile()`](backend/src/main/java/com/flashsale/inventory/RedisInventoryService.java) walks every non-archived product, compares `redis["stock:N"]` against `product.available_stock`, writes the DB truth back into Redis on drift, and logs each correction with `productId + oldRedis + dbTruth` at `ERROR` level. It runs:
   - **Once on application startup** via `@EventListener(ApplicationReadyEvent.class)` — covers a fresh Redis or a stale snapshot.
   - **Every 60 seconds** via `@Scheduled` (configurable through `flashsale.inventory.reconcile-interval-ms`) — covers a swallowed INCR failure during release (rule 2's log alone doesn't repair drift) and Redis restarts that wipe in-memory state while the backend keeps running.

   This is a periodic safety net, not a strongly-consistent mirror. Under heavy traffic, a reconcile pass may briefly write a 1–2 unit stale value because `available_stock` can change between the SELECT and the SET — the next pass converges. We chose this over distributed transactions or an outbox-with-MQ because the cost of brief inaccuracy is bounded (the throttle is already a "best-effort sold out" signal) and the operational footprint of a reconcile loop is one method + one annotation.

### Tests that pin the invariant

| Test | What it proves |
|---|---|
| [`RedisInventoryReconciliationTest.expiryReleasesStockAndAllowsRebuy`](backend/src/test/java/com/flashsale/inventory/RedisInventoryReconciliationTest.java) | Order created on a 1-stock product → manually moved past `expires_at` → `OrderExpiryJob.sweep()` invoked → both layers back to 1 → a fresh buyer actually succeeds (proves the released unit is re-bookable, not just that the counters agree). |
| `cancellationReleasesStockAndAllowsRebuy` | Same shape via `OrderService.cancel`. |
| `reconcileRepairsCorruptedRedis` | Redis manually corrupted to a value below DB truth → `reconcile()` → Redis matches DB again → buyer can immediately reserve. |
| [`RedisInventoryConcurrencyTest`](backend/src/test/java/com/flashsale/inventory/RedisInventoryConcurrencyTest.java) | 500 concurrent VUs on 100-stock product → exactly 100 succeed → no-oversell regression guard, unaffected by the reconciliation layer. |
| [`RedisInventoryCompensationTest.multipleConsecutiveFailuresKeepStockExactlyAtOriginal`](backend/src/test/java/com/flashsale/inventory/RedisInventoryCompensationTest.java) | 5 consecutive `OrderService.createOrder` failures with stock=10 → both layers end at exactly 10 (not 15, not 5) — pins the `REQUIRES_NEW` propagation contract that makes compensation arithmetically correct. |

## Rate limiting — keeping bots off the purchase endpoint

Flash-sale traffic includes bots / script-spammers hitting `POST /purchase` at thousands of requests per minute per IP. The Redis stock throttle rejects them as `409 sold out` once the stock is gone, but until then every spammed request still costs a Lua DECR, a DB UPDATE, and an order row. A web-layer limiter cuts that traffic before it touches the inventory hot path.

### Algorithm — fixed-window counter

Key shape: `rl:{clientIp}:{productId}:{windowIndex}`, where `windowIndex = epochSeconds / windowSeconds`. Default 10 attempts per 60 seconds; both knobs configurable via `flashsale.ratelimit.max-attempts` and `flashsale.ratelimit.window-seconds`.

Why fixed-window over sliding-window:

| | Fixed window (chosen) | Sliding window |
|---|---|---|
| Memory per key | 1 integer | 1 integer + a sorted-set / log per scope |
| Per-request cost | 1 Lua call | Several Redis ops |
| Boundary burst | possible — a burst right at the window edge can deliver 2× limit in <1s | smoothed |
| Implementation lines | ~30 | ~150+ |

The boundary burst is fine here because the actual oversell defense is downstream: even if 20 requests sneak through a window edge, the atomic DB UPDATE still lets at most stock-many of them commit. The limiter is for bot pressure, not for guaranteeing fairness to the millisecond.

### Lua atomicity — INCR + EXPIRE in ONE call

```lua
local n = redis.call('INCR', KEYS[1])
if n == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return n
```

This must be a single Lua script, NOT two separate Java calls (`INCR` then `EXPIRE`). The naive two-call version has a fatal race: if the process crashes / loses its Redis connection between INCR and EXPIRE on the first request to a fresh key, the key now has **no TTL** and lives in Redis forever. Every subsequent request for that scope sees a counter that never resets — the buyer is permanently rate-limited and the entry leaks memory. Wrapping both ops in one Lua call makes them atomic from Redis's perspective; there is no window in which a counter can exist without a TTL.

[`RateLimitService.java`](backend/src/main/java/com/flashsale/ratelimit/RateLimitService.java) carries this rationale in its Javadoc so it doesn't get "optimised" later.

### Fail-open on Redis trouble

Any `DataAccessException` from Redis (down, network blip, OOM, connection pool exhausted) → log at `ERROR` and let the request through. The DB atomic UPDATE downstream still guarantees no oversell, and refusing every purchase during a Redis outage would be strictly worse than letting un-limited buyers through that window. Also, when Spring doesn't autowire a `StringRedisTemplate` at all (DB-only test profile), the service permanently no-ops. The unit test [`RateLimitServiceFailOpenTest`](backend/src/test/java/com/flashsale/ratelimit/RateLimitServiceFailOpenTest.java) pins this contract across three failure modes.

### Client IP resolution — never trust `X-Forwarded-For` by default

The rate-limit scope is `(clientIp, productId)`. How `clientIp` is derived is a security boundary: if the backend trusts an arbitrary `X-Forwarded-For` from the wire, any client can send `X-Forwarded-For: <random>` on every request and earn a fresh bucket each time, completely bypassing the limiter. The flag `flashsale.ratelimit.trust-forwarded-header` controls this:

| Setting | Behaviour |
|---|---|
| `false` (default — production) | `clientIp = request.getRemoteAddr()`. `X-Forwarded-For` is ignored entirely. |
| `true` (dev / load-test profiles, controlled networks only) | `clientIp` = leftmost entry of `X-Forwarded-For` if present, else `getRemoteAddr()`. |

**Production: keep this flag at `false` AND make Nginx (or whatever proxy you front the backend with) **overwrite** the header — not append. Most stock Nginx configs use the `$proxy_add_x_forwarded_for` variable which appends whatever the client sent; that's the wrong default for our purposes because a malicious client could still influence the leftmost entry. The correct snippet:

```nginx
# overwrite — sets XFF to JUST the real client, drops anything the client sent
proxy_set_header X-Forwarded-For $remote_addr;
```

Once XFF is guaranteed-trustworthy at the edge, you can flip the flag to `true` and the limiter starts bucketing on real client IPs again.

**Load testing**: k6 runs need the flag set to `true` (and a unique XFF per VU) so the 500 concurrent virtual users don't all share one `localhost` bucket. The k6 doc block in [`loadtest/flash-sale.js`](loadtest/flash-sale.js) lays out the env-var to set, plus alternatives if you'd rather keep the production default and bump `max-attempts` instead.

[`RateLimitSpoofProtectionTest`](backend/src/test/java/com/flashsale/ratelimit/RateLimitSpoofProtectionTest.java) pins the secure default: with the flag off, three requests carrying three different spoofed XFFs all collapse into one bucket, and the fourth request still returns 429.

### Wiring scope

`RateLimitInterceptor` is registered via [`RateLimitWebConfig`](backend/src/main/java/com/flashsale/ratelimit/RateLimitWebConfig.java) on exactly `/api/drops/*/purchase`. Every other route — admin product seed, drop list, order status, webhook callbacks — bypasses the limiter entirely.

### Over-limit response

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37
Content-Type: application/json

{"error":"rate_limited","retryAfterSeconds":37}
```

### Tests that pin the limiter behaviour

| Test | What it proves |
|---|---|
| [`RateLimitServiceFailOpenTest`](backend/src/test/java/com/flashsale/ratelimit/RateLimitServiceFailOpenTest.java) | Three Redis-failure shapes (connection lost, transient blip, no bean) all return `ALLOWED`. |
| [`RateLimitInterceptorTest.overLimitReturns429WithJsonBodyAndRetryAfter`](backend/src/test/java/com/flashsale/ratelimit/RateLimitInterceptorTest.java) | (N+1)th request → 429 with the documented JSON body + `Retry-After` header. |
| `differentIpsHaveIndependentBudgets` | One IP burning its budget doesn't lock out a second IP. |
| `parallelCallsRespectMaxAttempts` | 50 parallel calls into the same scope → exactly `max-attempts` pass — proves the Lua INCR+EXPIRE atomicity holds under contention. |

### Running k6 against the limited endpoint

The k6 script sets a unique `X-Forwarded-For` per VU (`10.{vu>>16}.{vu>>8}.{vu}`) so each virtual user gets its own rate-limit bucket — otherwise every VU would share the same `localhost` IP and the test would just measure how fast we can return 429s. See the comment block in [`loadtest/flash-sale.js`](loadtest/flash-sale.js) for the rationale, and an alternative env-bump approach.

## Asynchronous order pipeline — peak shaving with Kafka

The original synchronous purchase path wrote the order row to Postgres on the request thread, holding the connection open while the consumer-facing JSON response was assembled. Under a 500-VU stress run that's the bottleneck — see the DB-only column in the load-test table, latency p95 = 60 s when HikariCP saturates.

After the Kafka pipeline:

```
POST /purchase
  ├─ rate-limit interceptor
  ├─ Redis Lua check-and-DECR              (still synchronous — admission control)
  ├─ on admit: publish OrderRequestedEvent  (partition key = productId)
  └─ return 202  { orderId, status: "PROCESSING" }   ← API returns here, ~1ms

       async ↓

orders.requested ──► @KafkaListener "order-writer"
                       ├─ existsByExternalId? skip (idempotent redelivery)
                       ├─ Postgres atomic UPDATE (final correctness guard)
                       ├─ INSERT order row (CREATED, expires_at=now+15m)
                       └─ manual ack only after the @Transactional commits

Client polls GET /api/orders/{externalId}/status
   PROCESSING (row not yet written) → CREATED → PAID / EXPIRED / ...
```

### Why these choices

- **Partition key = productId.** All events for the same product land on one partition and are processed in order by one consumer thread. Different products fan out across the three partitions. The single-product hot-row contention that's the whole point of a flash sale is therefore serialised by the consumer — no need for retry storms on the DB side.

- **Pre-generated `externalId` (UUID) in the API layer.** This is what makes the consumer idempotent. At-least-once Kafka delivery + duplicate-key skip on `orders.external_id` UNIQUE = effectively-once order creation. The client gets the externalId in the 202 body and starts polling immediately, before the consumer has even seen the event.

- **Postgres atomic UPDATE stays in the consumer as final defense.** Redis can drift (snapshot restore, missed compensation), Kafka can redeliver — neither matters because the DB `WHERE available_stock >= :qty` clause cannot oversell. If the DB rejects what Redis admitted, the consumer compensates Redis via `releaseRedisOnly` and writes nothing.

- **Manual ack after `@Transactional` commits.** Crash between commit and ack → redelivery → externalId guard absorbs it. Crash the other way (ack-then-process) would silently lose events; we refuse to accept that.

- **`@KafkaListener` retries 3× with 1 s backoff via `DefaultErrorHandler`**, then logs at `ERROR` and skips the record so the partition doesn't wedge. No DLT in v1 (a `// TODO dead-letter topic` lives in `KafkaConfig.java`); a permanently-failing record is logged for ops to chase.

- **Polling endpoint returns 200 `PROCESSING` when the row is absent — explicitly NOT 404.** The client is following the contract; 404 would look like the request failed.

### What's untouched

The rate limiter, the Redis Lua stock script, all webhook controllers, all payment providers, the reconciliation job, the order expiry job — unchanged. The expiry job keeps working: orders only exist after the consumer writes them, and `expires_at` is set at INSERT time by the consumer, so the same 15-minute TTL guarantee holds.

### Tests pinning the pipeline

| Test | What it proves |
|---|---|
| `OrderKafkaPipelineTest.postPurchaseReturns202AndConsumerCreatesOrder` | Real Postgres + Redis + Kafka via Testcontainers; POST → 202 → Awaitility waits for the order row → assertions: status=CREATED, both stock layers decremented exactly once. |
| `OrderKafkaPipelineTest.statusEndpointReturnsProcessingWhenRowAbsent` | GET `/api/orders/{never-written-id}/status` returns **200** with `{"status":"PROCESSING"}`. |
| `OrderKafkaPipelineTest.duplicateEventCreatesExactlyOneOrder` | Publishes the same event TWICE via `KafkaTemplate`; awaits drain; asserts exactly one row + DB stock decremented once. The `externalId` unique constraint is the dedup guard. |
| `OrderKafkaConcurrencyTest.concurrentPurchasesDrainToExactlyStockMany` | 200 parallel ingestion calls on stock=100 → 100 admitted, 100 throttled at Redis, awaits consumer drain, asserts exactly 100 CREATED orders and both stock layers at zero. The no-oversell invariant survives the async pivot. |

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

### Webhook attack surface — three orthogonal defences

Three distinct attacks against a payment callback, three independent mechanisms — each blocks a class of attack the others don't:

| Attack | Defense | Where it lives |
|---|---|---|
| **Forgery** — attacker fabricates a webhook payload, no real payment behind it | HMAC / `CheckMacValue` signature verification rejects anything not signed with our secret | Stripe: `Webhook.constructEvent`. ECPay: [`EcpayCheckMac.compute`](backend/src/main/java/com/flashsale/payment/ecpay/EcpayCheckMac.java) recomputed and compared. |
| **Duplicate** — same legitimate webhook delivered N times by the gateway (gateway retry, network blip) | `UNIQUE(provider, provider_txn_id)` on `payment_event` — the second insert hits the constraint and rolls back; the controller acks normally so the gateway stops retrying | [`PaymentResultService.applyResult`](backend/src/main/java/com/flashsale/payment/PaymentResultService.java) |
| **Replay** — attacker captures a real signed payload and resends it hours / days later | Timestamp tolerance: **Stripe SDK default 300 s** (Webhook.constructEvent throws SignatureVerificationException on `|now - t| > 300s`); **ECPay** rejects payloads whose `PaymentDate` is older than `flashsale.payment.replay-window-seconds` (default 600 s) — returns HTTP 200 + `1|OK` so the gateway doesn't retry-storm us, logs at WARN | Stripe: SDK-delegated. ECPay: [`EcpayProvider#rejectIfStale`](backend/src/main/java/com/flashsale/payment/ecpay/EcpayProvider.java), [`StaleCallbackException`](backend/src/main/java/com/flashsale/payment/StaleCallbackException.java). |

The replay defence is hardening, not a hole — the idempotency constraint already blocks same-`provider_txn_id` replays at the DB layer, so a replay attack only buys you a tiny window before the txn id collides with a row we've already written. The timestamp check closes that window proactively.

## PayUni status

> The `PayUniProvider` class is a **skeleton**. PayUni is a Taiwan-specific gateway with no published SDK; the request/response formats, the AES mode/padding/encoding, the checksum algorithm, and the callback field names all need to come from PayUni's official test-environment docs. The interface, properties config, idempotency machinery, and webhook controller are all wired and ready — only the encrypt/decrypt-and-sign body remains.

To finish PayUni:

1. Register a PayUni test merchant; collect `MerchantID`, `HashKey`, `HashIV`.
2. Paste PayUni's official docs (create-order endpoint, callback format, checksum spec, test URLs) into the project.
3. Implement [`PayUniProvider.createSession`](backend/src/main/java/com/flashsale/payment/payuni/PayUniProvider.java) and `verifyCallback` per those docs. The class Javadoc lists exactly what each method needs to do.

Until then, the rest of the system is fully runnable — order creation, stock decrement, expiry, manual ship/complete, callback dedup logic (a `curl`-simulated callback works once the provider is implemented).

## Local-dev: getting webhooks to your backend

### Stripe — use the Stripe CLI, no tunnel needed

Stripe ships a CLI that forwards real Stripe events from the cloud to your `localhost`, so you don't need ngrok / cloudflared. Two terminal panes:

```sh
# pane 1 — one-time auth, then forward live events to your backend
stripe login                                     # opens browser, OAuth-style
stripe listen --forward-to localhost:8080/api/payments/stripe/callback
# → prints a line like:
#   Ready! Your webhook signing secret is whsec_xxxxxxxxxxxxx
# Copy that whsec into .env as STRIPE_WEBHOOK_SECRET, then restart backend.
```

```sh
# pane 2 — fire fixture events. Stripe sends them to the live "listen" pane,
# which forwards to your backend. metadata.orderId can be injected here.
stripe trigger checkout.session.completed --add checkout_session:metadata.orderId=1
```

Notes:
- The `whsec_` value persists across `stripe listen` runs on the same machine — you only set it in `.env` once.
- `stripe trigger` fixture data has no real `orderId` by default. The backend handles this gracefully: the event verifies, but with no `metadata.orderId` (or one we don't recognise) the controller returns `200 IGNORED` / `200 UNKNOWN_ORDER` so Stripe stops retrying instead of looping for ~3 days. Use `--add checkout_session:metadata.orderId=<real-id-from-our-DB>` to actually drive a `CREATED → PAID` transition.
- For the real-card flow (test card `4242 4242 4242 4242`), keep `stripe listen` running and complete a Checkout Session from the frontend — same forwarding path, real card processing on Stripe's side.

### ECPay — ngrok / cloudflared (no CLI equivalent)

ECPay does not have a CLI that can forward events. Their `ReturnURL` callback is server-to-server from the ECPay stage environment, so it cannot reach `localhost`. Expose your backend with a tunnel:

```sh
# Option 1: ngrok
ngrok http 8080

# Option 2: cloudflared quick tunnel (no Cloudflare account needed)
cloudflared tunnel --url http://localhost:8080
```

Then set `ECPAY_RETURN_URL` in `.env` to the public tunnel URL + `/api/payments/ecpay/callback` and restart the backend. `ECPAY_ORDER_RESULT_URL` (the browser-redirect destination) stays on `localhost:3000/result` since the buyer is browsing locally.

> **The cloudflared quick-tunnel URL is ephemeral.** Every `cloudflared tunnel --url ...` invocation gets a fresh random `https://<words>.trycloudflare.com` subdomain. Whenever you restart the tunnel you MUST update `ECPAY_RETURN_URL` in `.env` (and recreate the backend container so it picks up the new env) — otherwise the stage server will be POSTing callbacks to a dead URL and you'll see ECPay's backend report "notification failed" without ever hitting your handler. For a stable URL, use a named cloudflared tunnel bound to your own subdomain.

> **ECPay stage test credentials.** The defaults in `.env` and `application.yml` (`MerchantID=3002607`, `HashKey=pwFHCqoQZGmho4w6`, `HashIV=EkRm7iFT261dpevs`) are ECPay's *currently-published* stage test set — see the [CheckMacValue generation page](https://developers.ecpay.com.tw/?p=2902). The older `2000132` / 20-char-key pair has been observed to fail with `10200073 CheckMacValue Error` against the live stage server even though the algorithm itself is correct ([`EcpayCheckMacGoldenSampleTest`](backend/src/test/java/com/flashsale/payment/EcpayCheckMacGoldenSampleTest.java) pins our compute against ECPay's own worked example). If ECPay rotates their published set again, refresh `.env` first and re-run that test.

### PayUni — same as ECPay (tunnel required)

When you get the PayUni docs and fill in `PayUniProvider`, use the same ngrok / cloudflared approach as ECPay. Set `PAYUNI_NOTIFY_URL` and `PAYUNI_RETURN_URL` in `.env` to the public tunnel URL + `/api/payments/payuni/callback`.

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

Same VU shape (500 peak, 30s ramp + 60s hold + 10s rampdown, 100 stock), three configurations measured against each other. Two headlines:

1. **+Redis throttle drops 5xx errors from 610 to 0** while pushing throughput up ≈112× — the DB connection pool stops being the bottleneck.
2. **+Kafka pipeline moves the order INSERT off the request thread** — the API returns 202 in ms-range and the consumer persists all 100 orders within **<1 second** after k6 finishes. No 5xx; no oversell.

| | DB-only (sync) | + Redis throttle (sync) | + Kafka pipeline (async) |
|---|---|---|---|
| Total requests | 5,761 | **648,348** | 180,885 |
| Admitted (200 or **202**) | 100 (200) | 100 (200) | **100 (202)** |
| Sold-out (409) | 5,051 | 648,248 | 180,785 |
| **5xx / 0-status errors** | **610** | **0** | **0** |
| Latency (avg) | 5,732ms | **61.5ms** | 220.5ms |
| Latency (p95) | 59,997ms (DB pool saturated) | **143.4ms** | 345.3ms |
| **Time to drain** | n/a (sync) | n/a (sync) | **<1s** |
| Final stock (DB) | 0 | 0 | 0 |
| Final stock (Redis) | n/a | 0 | 0 |
| Orders persisted | 100 | 100 | **100** |
| Oversell check | **PASS** | **PASS** | **PASS** |

**DB-only**: 500 VU saturates HikariCP at `maximum-pool-size: 30`. The inventory guarantee survives (still exactly 100 succeed), but 610 unlucky requests die with `Could not open JPA EntityManager for transaction`. The atomic UPDATE was right; the connection pool was the failure surface.

**+Redis throttle**: 648,248 of 648,348 attempts get a clean 409 from the Lua `check-and-DECR` script without ever asking Spring for a DB connection. Pool stays cold; 5xx goes to literal zero; throughput ×112.

**+Kafka pipeline**: the Redis Lua DECR still admits exactly 100 buyers — but instead of doing the DB INSERT on the request thread, the API publishes an `OrderRequestedEvent` and returns **202 PROCESSING** immediately. The `@KafkaListener` consumer drains all 100 events into Postgres within <1 second after k6 finishes (probe interval was 1s; the first probe found `available_stock=0` already). The latency p95 is *higher* than the +Redis column because the producer's `.get(5s)` blocks on broker ack (`acks=all` + idempotent), but the win is qualitative — the API now sheds back-pressure on a queue rather than a connection pool, and the consumer can be scaled independently. (Lower latency would be reachable by switching to fire-and-forget publishes with a separate confirmation channel; the conservative blocking-publish here trades p95 for a clean "your 202 means it's durable" semantic.)

The 180k vs 648k total-request gap is because the rate limiter is now part of the picture (each request burns a Redis Lua check even when the limit is bumped to 1,000,000 for the load test) and the Kafka producer's idempotent state machine adds per-request bookkeeping. Both costs are paid even on the 409-rejected path.

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
