# Flash-Sale System — Project Overview & Kubernetes Migration Discussion Brief

> Purpose of this document: describe the existing flash-sale system clearly, as
> a starting point for discussing **"how to move it onto Kubernetes."**
> Two parts: **Part A describes the current state (facts)**, **Part B lists the
> questions to decide for the k8s migration (to discuss).**

---

# Part A — Current state (facts)

## 1. What the system is

A **time-limited flash-sale system**: a product goes on sale at a set time,
stock is limited, and a burst of concurrent purchase requests floods in. The
core challenge is **never overselling under high concurrency** while not letting
the database get overwhelmed.

## 2. Tech stack (versions locked, no substitutions)

| Layer | Technology | Version |
|----|------|------|
| Backend language | Java | 21 |
| Backend framework | Spring Boot | 3.3.4 |
| Packaging | single executable jar (`spring-boot-maven-plugin`) | — |
| Database | PostgreSQL | 16 |
| Cache / rate limiting | Redis | 7 |
| Message queue | Apache Kafka | 3.7.0 (KRaft mode, no ZooKeeper) |
| DB schema migration | Flyway (runs migrations automatically on boot) | — |
| Payments | Stripe (implemented), ECPay (implemented), PayUni (skeleton) | — |
| Frontend | Next.js + HeroUI (Node 20) | — |

## 3. Core architecture: three layers against overselling

The full path of one purchase request:

```
Buyer clicks "Buy"
   │
   ▼
[Rate limit] RateLimitInterceptor — Redis fixed-window counter (atomic Lua INCR+EXPIRE)
   │   over the limit → 429. Fail-open (allow through) if Redis is down
   ▼
[Layer 1] Redis Lua "check + decrement stock" (atomic)
   │   99% of sold-out requests die here, never touching the DB. Returns 409 sold out
   ▼
[Queue] Publish OrderRequestedEvent to Kafka topic "orders.requested"
   │   API returns 202 + externalId immediately; the frontend polls for status
   │   partition key = productId (orders for the same product land on the same
   │   partition and are processed in order)
   ▼
[Consume] OrderEventConsumer takes the message off Kafka
   │
   ▼
[Layer 2 / final defense] Postgres atomic UPDATE on stock + INSERT order (one transaction)
   │   externalId UNIQUE constraint = idempotent dedup (at-least-once → effectively-once)
   │   manual ack: only report back to Kafka after the DB commit succeeds
   ▼
Order placed (status CREATED, awaiting payment)
   │
   ▼
[Payment] Redirect to Stripe/ECPay; a webhook callback confirms payment
```

**Key design principles:**
- **Postgres is the single source of truth**; Redis is a "derived cache sitting
  in front" that can be rebuilt from the DB at any time.
- Both Redis and Kafka have **graceful degradation**: when the bean is absent
  the app automatically degrades to DB-only mode (`ObjectProvider.getIfAvailable()`
  returns null + null checks throughout). This is exactly how the tests run in
  an environment without Redis/Kafka.

## 4. Service inventory (docker-compose currently runs 5 containers)

| Container | image | port | Stateful? | Notes |
|------|-------|------|---------|------|
| `flashsale-postgres` | postgres:16-alpine | 5432 | **Stateful** (volume) | source of truth |
| `flashsale-redis` | redis:7-alpine | 6379 | semi-stateful (rebuildable) | stock brake + rate-limit counters |
| `flashsale-kafka` | apache/kafka:3.7.0 | 9092 (internal) / 29092 (external) | **Stateful** (log) | order queue |
| `flashsale-backend` | self-built (see below) | 8080 | **Mostly stateless** ⚠️ with exceptions | Spring Boot jar |
| `flashsale-frontend` | self-built (Next.js) | 3000 | stateless | user interface |

Dependency order: backend starts only after postgres / redis / kafka are all
`healthy`; frontend waits for backend.

## 5. How the containers are built

**Backend** (`backend/Dockerfile`): multi-stage build
- build stage: `maven:3.9-eclipse-temurin-21` → `mvn package` (skip tests)
- runtime stage: `eclipse-temurin:21-jre`, `java -jar app.jar`, EXPOSE 8080
- ⚠️ **No health-check endpoint at present** (pom.xml has no `spring-boot-starter-actuator`)

**Frontend** (`frontend/Dockerfile`): multi-stage Next.js, `node:20-alpine`, `npm start`, EXPOSE 3000

## 6. Configuration & environment variables (currently injected via docker-compose env)

The backend reads `application.yml`; every external address/secret uses the
`${ENV:default}` form so it can be overridden by an environment variable:

**Connection (good for a ConfigMap):**
- `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER`
- `REDIS_HOST` / `REDIS_PORT`
- `KAFKA_BROKERS`
- `SERVER_PORT` (default 8080)

**Secrets (must go in a Secret):**
- `DB_PASSWORD`
- `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET`
- `ECPAY_HASH_KEY` / `ECPAY_HASH_IV` (currently ECPay's stage test values)
- `PAYUNI_HASH_KEY` / `PAYUNI_HASH_IV`

**Behavior tuning:**
- `FLASHSALE_RATELIMIT_TRUST-FORWARDED-HEADER` (default false; set true only
  behind a reverse proxy that overwrites X-Forwarded-For)
- `FLASHSALE_RATELIMIT_MAX-ATTEMPTS` (default 10)
- `flashsale.order.reservation-ttl-minutes` (order held for 15 minutes)
- `flashsale.order.expiry-scan-interval-ms` (expiry scan, 60s)
- `flashsale.inventory.reconcile-interval-ms` (Redis-DB reconcile, 60s)

**Frontend:**
- `NEXT_PUBLIC_API_URL` (currently `http://localhost:8080/api`, **a build-time
  variable, baked into the static files**)

## 7. Important connection-pool and resource settings

- **HikariCP**: `maximum-pool-size: 30`, `minimum-idle: 5` (up to 30 DB
  connections per backend instance)
- Kafka producer: `acks=all`, `enable.idempotence=true`
- Kafka consumer: `group-id=order-writer`, manual ack, `auto-offset-reset=earliest`
- Kafka topic `orders.requested`: **3 partitions**, replicas=1, broker-side
  auto-create **disabled** (created declaratively by the backend's `TopicBuilder`
  bean)

---

# Part B — Questions to decide for the Kubernetes migration (to discuss with Claude)

> Each item below is a point that will "break" or "needs a decision" in a
> multi-replica k8s environment. Ordered by importance.

## 🔴 Key problem 1: the two @Scheduled jobs "can only run on a single node"

The backend has two `@Scheduled` background jobs that **currently assume only
one instance is running**. Once you run multiple replicas in k8s (replicas > 1),
every pod runs its own copy:

1. **`OrderExpiryJob.sweep()`** (every 60s) — sweeps expired orders, releases
   stock. The code comment already says so:
   `Single-node only — if/when this app is deployed multi-node, add a distributed lock (e.g. Redisson).`
2. **`RedisInventoryService.reconcile()`** (every 60s) — reconciles Redis vs DB
   stock. Multiple replicas would reconcile simultaneously and overwrite each other.

**Options to discuss:**
- (A) Add a distributed lock (Redisson / ShedLock, Redis- or DB-backed) so only
  one of the replicas actually runs → the backend as a whole can scale horizontally.
- (B) Extract these two jobs into a separate Deployment (replicas=1) or a k8s
  `CronJob`, deployed apart from the request-serving backend → the request
  backend scales freely, the scheduler stays a singleton.
- (C) Use k8s built-in leader election (lease).

**This is the number-one blocker for k8s-ification; it must be solved before you
can safely run multiple replicas.**

## 🔴 Key problem 2: DB connection count grows linearly with replica count

Each backend pod opens 30 HikariCP connections. Postgres defaults to
`max_connections ≈ 100`.

- 4 backend replicas → 4 × 30 = 120 connections, **straight over the Postgres limit**.
- Earlier load tests already identified HikariCP-30 as the bottleneck shape.

**To discuss:** introduce a connection-pool middleware (PgBouncer)? Or lower the
per-pod pool size and raise Postgres max_connections? The replica-count vs
connection-count math has to be worked out first.

## 🟠 Problem 3: missing health-check endpoints (needed for k8s probes)

The backend currently has no `spring-boot-starter-actuator` and **no `/health`,
`/readiness` endpoints**. k8s `livenessProbe` / `readinessProbe` need these.

**To discuss:** add actuator, expose `/actuator/health/liveness` and `/readiness`.
In particular, readiness must accurately reflect "DB / Redis / Kafka reachable,"
otherwise a pod will take traffic before its dependencies are ready.

## 🟠 Problem 4: where to put the stateful services (Postgres / Redis / Kafka)

All three are stateful and can't be scaled up/down casually like stateless pods.

**Strategy to discuss for each:**
- **Postgres**: cloud-managed (RDS / Cloud SQL) or in-cluster StatefulSet +
  operator (CloudNativePG / Zalando)?
- **Redis**: managed (ElastiCache / Memorystore) or in-cluster (Bitnami chart /
  Redis operator)? Note it holds the stock cache + rate-limit counters; it can
  be rebuilt from the DB (reconcile) if it dies, so weaker persistence is acceptable.
- **Kafka**: managed (MSK / Confluent Cloud) or in-cluster (Strimzi operator)?
  Note the topic is currently created declaratively by the backend's
  `TopicBuilder` at startup with broker-side auto-create off; confirm that
  creation flow still works after the move.

## 🟠 Problem 5: the Kafka consumer's horizontal-scaling ceiling = partition count

In consumer group `order-writer` a single partition is assigned to only one
consumer. Currently **3 partitions = at most 3 effective parallel consumers**.
Backend replicas beyond 3 will have idle consumer threads (no partition to take).

**To discuss:** how many partitions does the expected throughput need? Should the
partition count be raised to match the target replica count? (Note partition
count can only increase, never decrease, and increasing it reshuffles the
existing key assignment.)

## 🟡 Problem 6: payment webhooks must be reachable from outside (Ingress)

Stripe / ECPay payment results come back via **HTTP webhook callbacks** (e.g.
`/api/payments/ecpay/callback`, the Stripe webhook controller). These paths must
be **reachable from the public internet into the cluster**.

**To discuss:** Ingress / Gateway API routing rules; the public callback URL
(currently defaults to localhost, needs to become a real domain); Stripe webhook
signature verification is fine across multiple replicas (stateless verification),
but the URL has to be right.

## 🟡 Problem 7: the frontend's API URL is a build-time variable

`NEXT_PUBLIC_API_URL` is baked into the static files at `npm run build` time. In
k8s you can't just override it with an env var.

**To discuss:** specify the real API URL when building the image, or switch to
runtime injection (Next.js runtime config / reverse-proxy rewrite)?

## 🟡 Problem 8: splitting config and secrets

Everything is currently mixed in docker-compose env. In k8s it should split into:
- **ConfigMap**: non-sensitive connection settings (DB_HOST, REDIS_HOST,
  KAFKA_BROKERS, the various URLs, rate-limit params)
- **Secret**: DB_PASSWORD, Stripe/ECPay/PayUni keys

**To discuss:** use External Secrets Operator / Sealed Secrets to manage secrets
instead of plaintext Secrets?

## ✅ The k8s-friendly parts (good news)

- **Rate limiting is Redis-backed** (not in-memory), so multiple replicas share
  one counter and horizontal scaling doesn't break it.
- **Request handling itself is stateless** (apart from the two scheduled jobs
  above); DB/Redis/Kafka are all external dependencies → suitable for HPA autoscaling.
- **Idempotency is done** (externalId UNIQUE constraint + Kafka manual ack); pods
  being killed and restarted, or messages redelivered, won't create duplicate
  orders → fits the k8s "a pod can be killed at any time" environment.
- **Graceful degradation is built in**, so a temporarily unavailable dependency
  won't take the whole thing down.

---

# Suggested discussion order

1. First solve **problem 1 (single-node scheduler)** and **problem 2 (DB
   connection count)** — without these, running multiple replicas breaks.
2. Then handle **problem 3 (health checks)** — a basic prerequisite for a k8s deploy.
3. Then decide **problem 4 (managed vs self-hosted for the three stateful services)**
   — it shapes the overall architecture.
4. Finally handle 5–8 (throughput tuning, Ingress, frontend, secrets).

---

## Appendix: current directory structure (key backend packages)

```
com.flashsale
├── api/              REST controllers (DropController = the hot purchase path)
│                     + the payment webhook controllers
├── inventory/        InventoryService interface
│                     ├── RedisInventoryService (@Primary, Redis brake + reconcile job)
│                     └── DatabaseInventoryService (DB atomic UPDATE, final defense)
├── order/            OrderService, OrderIngestionService (Kafka producer)
│                     OrderEventConsumer (Kafka consumer), OrderExpiryJob (scheduled)
├── kafka/            KafkaConfig (topic + error handler), OrderRequestedEvent
├── payment/          PaymentProvider interface + registry
│                     ├── stripe/   StripeProvider
│                     ├── ecpay/    EcpayProvider + CheckMac signing
│                     └── payuni/   PayUniProvider (skeleton)
├── ratelimit/        RateLimitService (Redis Lua fixed-window) + interceptor
├── domain/           Order, Product, and other entities
└── repository/       Spring Data JPA repositories
```
