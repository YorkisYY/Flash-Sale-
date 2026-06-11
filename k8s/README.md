# Flash-Sale on Kubernetes (local / demo)

Lightweight manifests to run the whole stack on a local **k3d** cluster for
**correctness** verification at `replicas: 2` — not a production or load setup.
Backing services (Postgres, Redis, Kafka) are single-replica Deployments on
`emptyDir` (non-persistent), deliberately chosen over StatefulSets to stay
laptop-friendly.

> ⚠️ **emptyDir is ephemeral.** All Postgres / Redis / Kafka data lives in
> `emptyDir` volumes that are **wiped whenever a pod restarts or is
> rescheduled** — orders, stock, and Kafka offsets do not survive a pod
> restart. This is intentional for a throwaway verification cluster. For any
> data-persistent deployment, replace these with StatefulSets backed by
> PersistentVolumeClaims (and use a managed/operator-run Kafka & Postgres).

## Files

| File | What it creates |
|------|-----------------|
| `configmap.yaml` | `flashsale-config` — all **non-sensitive** config (addresses, pool size, URLs, tuning knobs) |
| `secret.yaml` | `flashsale-secrets` — **placeholders only** (`REPLACE_ME`); real values go in a gitignored copy |
| `postgres.yaml` | Postgres Deployment (`max_connections=100`) + Service + emptyDir |
| `redis.yaml` | Redis Deployment + Service + emptyDir |
| `kafka.yaml` | Kafka (single-node KRaft) Deployment + Service + emptyDir |
| `backend.yaml` | Backend Deployment (**replicas: 2**, `SCHEDULER_ENABLED=false`) + Service — serves traffic, runs NO timers |
| `scheduler.yaml` | Scheduler Deployment (**replicas: 1**, `SCHEDULER_ENABLED=true`, same image, no Service) — owns the periodic jobs |
| `frontend.yaml` | Frontend Deployment (replicas: 1) + Service |
| `ingress.yaml` | `/api/*` → backend, `/*` → frontend (Traefik) |
| `backend-hpa.yaml` | HPA: min 2 / max 4 / 70% CPU (documents intent) |

## Secrets — do this first

The committed `secret.yaml` is placeholders only. Create your real, **gitignored**
copy:

```bash
cp k8s/secret.yaml k8s/secret.local.yaml
# edit secret.local.yaml — at minimum set DB_PASSWORD (e.g. "flashsale").
# Payment keys can stay REPLACE_ME for the demo; the purchase→DB flow does
# not call Stripe/ECPay.
```

`k8s/secret.local.yaml` and `k8s/*.local.yaml` are in `.gitignore`, so real
values never reach the repo. `DB_PASSWORD` is shared: the Postgres Deployment
reads the same Secret key for `POSTGRES_PASSWORD`, so backend and DB always agree.

## Apply order

Config and backing services before the app:

```bash
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.local.yaml     # NOT secret.yaml (that's placeholders)
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/kafka.yaml
# wait for postgres/redis/kafka to be Ready, then:
kubectl apply -f k8s/backend.yaml      # replicas=2, SCHEDULER_ENABLED=false (no timers)
kubectl apply -f k8s/scheduler.yaml    # replicas=1, SCHEDULER_ENABLED=true  (owns timers)
kubectl apply -f k8s/frontend.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/backend-hpa.yaml
```

The backend tolerates a not-yet-ready Kafka at startup (the admin client
retries and the listener reconnects), but bringing the backing services up
first keeps the logs clean.

## Connection math (why HikariCP pool = 8)

```
backend replicas (2) × HikariCP maximum-pool-size (8) = 16 connections
Postgres max_connections = 100   →   16 ≪ 100, with headroom for Flyway,
                                     actuator health checks, and the HPA's
                                     extra replicas (max 4 × 8 = 32 < 100).
```

`DB_POOL_SIZE=8` is set in `configmap.yaml`; the backend's
`spring.datasource.hikari.maximum-pool-size` reads `${DB_POOL_SIZE:30}` (default
30 preserves local/docker-compose behaviour). This directly addresses the
load-test finding that an oversized pool, multiplied across replicas, would
exhaust Postgres connections.

## Probe design — dependency criticality

| Probe | Endpoint | Group members | Behaviour |
|-------|----------|---------------|-----------|
| liveness | `/actuator/health/liveness` | `livenessState` only | Fails only if the app itself is broken. **Never** includes a dependency — a failed liveness restarts the pod, which can't fix an external outage and would cause restart storms. |
| readiness | `/actuator/health/readiness` | `readinessState`, **`db`** | Fails when **Postgres** is unreachable → pod leaves the Service endpoints. `failureThreshold 3 × periodSeconds 10 ≈ 30s` tolerance so a brief DB blip doesn't flap pods. |

**Redis and Kafka are deliberately excluded from readiness.** The app degrades
gracefully without them (DB-only inventory path; no-op Kafka publish), so their
outages must NOT pull a pod from rotation. Verified by `ActuatorHealthProbesTest`:
Redis down → readiness stays 200; DB down → readiness 503 while liveness stays 200.

## Why replicas>1 is safe

Two concerns when scaling the backend — the request path and the periodic jobs:

- **Request path** is stateless (Redis/Kafka/DB are external), so it scales
  freely to `replicas: 2` and under the HPA.
- **Periodic jobs** (order-expiry sweep, Redis↔DB reconcile) must run on exactly
  one instance. We guarantee that **structurally**, not by lock timing.

### Single-execution by topology: a dedicated scheduler Deployment

One image, two roles, selected by `flashsale.scheduler.enabled` (env
`SCHEDULER_ENABLED`):

| Deployment | replicas | `SCHEDULER_ENABLED` | Role |
|---|---|---|---|
| `backend` | 2 | `false` | serves API traffic; registers **no** `@Scheduled` timers |
| `scheduler` | 1 | `true` | runs the timers; no Service (takes no traffic) |

When the flag is `false`, the `SchedulerTriggers` bean — the only place the
`@Scheduled` methods live — is not created, so those pods have no timers at all.
Exactly one process (the scheduler, `replicas: 1`) owns the clock. Single
execution is a property of the **topology**, not of lock tuning.

Verified on k3d (ShedLock DEBUG on):
```
scheduler pod:  Scheduler ENABLED on this instance — it owns the ... timers
                Locked 'inventoryReconcile' ...   Locked 'orderExpirySweep' ...
backend pods:   (zero scheduled-job log lines)
```

#### Why this is cleaner than tuning lock timing

An earlier iteration kept `@Scheduled` on every backend replica and relied on a
large `lockAtLeastFor` (≈50s) so ShedLock would suppress the duplicate runs.
That works, but it's fragile: it depends on `lockAtLeastFor` being tuned to span
the inter-pod schedule offset (`offset < lockAtLeastFor < lockAtMostFor <
period`), and a mis-tuned value silently degrades to "every replica runs the
job, staggered." Moving the timer to a single-replica Deployment removes that
coupling entirely — structurally there is only one timer, regardless of lock
tuning.

#### ShedLock stays as defense-in-depth

ShedLock is still wired (`lockAtMostFor=55s`, small `lockAtLeastFor=5s`). It now
covers just one window: during a **rolling update of the scheduler Deployment**,
an old and a new scheduler pod could briefly overlap, and the lock prevents
concurrent execution in that window. (The scheduler uses `strategy: Recreate` to
avoid even that overlap; the lock is belt-and-braces.) Because single-execution
no longer depends on the lock, `lockAtLeastFor` is back to a small `5s`.

#### Correctness never depended on the lock

Unchanged, and the key invariant:

- `reconcile()` is **idempotent** (it SETs `redis = db-truth`), and
- `sweep()` releases stock only behind an **atomic conditional transition** —
  `UPDATE orders SET status=EXPIRED WHERE id=? AND status=CREATED`, acting only
  when one row changed.

So even if two instances ran a job at the same instant (lock failed open, or
Redis absent → no-op lock), there is no double-release and no corruption.
ShedLock is the efficiency layer; this guard is the correctness layer.

#### The `replicas: 1` tradeoff

A single scheduler is a single point of *timeliness*, not of correctness: if the
scheduler pod dies, the periodic jobs pause until Kubernetes recreates it
(seconds, via the Deployment controller). The gap is safe — expiry just runs a
little late (orders stay `CREATED` slightly longer before being swept) and
reconcile drift is corrected on the next pass; no order is lost and no stock is
mis-counted. If you ever need the timers highly-available, run the scheduler at
`replicas: 2` and let ShedLock keep execution single (tuning `lockAtLeastFor` per
the `offset < lockAtLeastFor < lockAtMostFor < period` rule) — the same image
supports both topologies via the flag.

## Kafka topic creation

Broker-side auto-create is **off** (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`).
The backend declares `orders.requested` (3 partitions) via a `TopicBuilder`/
`NewTopic` bean in `KafkaConfig`, and its `KafkaAdmin` creates it on startup
against the in-cluster broker (`KAFKA_BROKERS=kafka:9092`). Confirm in step 6:

```bash
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic orders.requested
# expect: PartitionCount: 3
```

---

## Step 6 — k3d bring-up (run only after manifests are approved)

```bash
# 1. Cluster: 2 agents, map the Traefik LB to localhost:8081 (matches the
#    callback URLs in configmap.yaml).
k3d cluster create flashsale --agents 2 -p "8081:80@loadbalancer"

# 2. Build images (JAVA_HOME must point at JDK 21 for the backend build).
docker build -t flashsale-backend:local ./backend
docker build -t flashsale-frontend:local ./frontend

# 3. Import images into the cluster (no registry needed).
k3d image import flashsale-backend:local flashsale-frontend:local -c flashsale

# 4. Apply (see "Apply order" above).

# 5. Verify
kubectl get pods                      # all Ready; 2 backend + 1 scheduler
kubectl rollout status deploy/backend && kubectl rollout status deploy/scheduler
# Single-execution by topology: the scheduler owns the timers; the backend
# pods register none. (Enable ShedLock DEBUG to see the lock lines:
#   kubectl set env deploy/scheduler LOGGING_LEVEL_NET_JAVACRUMBS_SHEDLOCK=DEBUG )
kubectl logs -l app=flashsale-scheduler | grep -iE "Scheduler ENABLED|Locked '"   # the scheduler runs jobs
kubectl logs -l app=flashsale-backend   | grep -iE "Locked '|scheduling-1"        # backend: expect NOTHING
# End-to-end purchase through Redis→Kafka→DB:
#   POST /api/drops/{id}/purchase  →  202 + externalId
#   GET  /api/orders/{externalId}/status  →  leaves PROCESSING once the
#        consumer writes the order to Postgres
```

### Cleanup

```bash
kubectl delete all,cm,secret,ingress,hpa -l app.kubernetes.io/part-of=flashsale
# or nuke the whole cluster:
k3d cluster delete flashsale
```
