package com.flashsale.inventory;

import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Redis pre-deduction throttle. Sits IN FRONT OF {@link DatabaseInventoryService},
 * not instead of it.
 *
 *  Flow on a successful reservation:
 *    1. Lua `check-and-DECR` in Redis (atomic). 99% of sold-out requests die here.
 *    2. If Redis allowed it, DB atomic UPDATE runs as the final defense.
 *    3. If DB rejects (drift between layers), INCR Redis back so the two
 *       stay in sync. The buyer sees "sold out" — semantically correct.
 *
 *  Compensation (when {@code release(productId, qty)} is called — order
 *  creation failed mid-tx, order expired, buyer cancelled):
 *    1. DB UPDATE +qty (Postgres is the source of truth, always restore first).
 *    2. Redis INCR (best-effort). If Redis is down, the next {@link #reloadAll}
 *       resyncs from Postgres — so a Redis hiccup during release doesn't
 *       permanently lose stock.
 *
 *  Degradation:
 *    Two distinct degraded modes, both correct:
 *    1. Redis bean is missing at startup (autoconfig excluded, e.g. in unit
 *       tests). {@link ObjectProvider#getIfAvailable()} returns null in the
 *       constructor; every method below null-checks {@link #redis} and
 *       delegates to the DB layer for its entire lifetime. No exceptions,
 *       no retries — permanent DB-only mode for this JVM.
 *    2. Redis was present at startup but now throws (network blip, OOM,
 *       failover). Each Redis op is wrapped in a DataAccessException catch
 *       that falls through to the DB layer for that one request. The next
 *       request retries Redis.
 *
 *  Why this design over {@code @ConditionalOnBean}: ordering. A
 *  {@code @ConditionalOnBean(StringRedisTemplate.class)} on either a
 *  component-scanned class OR a user {@code @Configuration} bean method
 *  evaluates BEFORE RedisAutoConfiguration runs, so it never sees the
 *  template even when it's about to be created. Auto-configuration ordering
 *  via {@code @AutoConfigureAfter} would work but is heavyweight for one
 *  bean. ObjectProvider sidesteps the timing issue — it's a deferred lookup
 *  that resolves to null when no candidate exists.
 *
 *  Bootstrap:
 *    - On {@link ApplicationReadyEvent} we reload every non-archived product
 *      from Postgres into Redis. Redis is a derived cache, not a source of
 *      truth, so this is safe on every startup. No-op when Redis is null.
 *    - On product creation, {@link InventoryService#loadProduct} is called
 *      explicitly from the admin endpoint so the first /purchase doesn't pay
 *      the lazy-bootstrap penalty.
 *    - {@link #tryReserve} also performs a lazy bootstrap if the Lua script
 *      returns -1 (key missing) — covers ad-hoc DB inserts, Redis evictions,
 *      and post-outage holes.
 */
@Service
@Primary
public class RedisInventoryService implements InventoryService {

    private static final Logger log = LoggerFactory.getLogger(RedisInventoryService.class);
    public static final String STOCK_KEY_PREFIX = "stock:";

    /**
     * Atomic check-and-decrement in one round-trip. Returns:
     *   <ul>
     *     <li>-1 — key not present (caller bootstraps from DB and retries)</li>
     *     <li> 0 — sold out (current stock &lt; requested qty)</li>
     *     <li> 1 — reserved (DECRBY done)</li>
     *   </ul>
     */
    private static final String RESERVE_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if cur == false then return -1 end
            local n = tonumber(cur)
            local q = tonumber(ARGV[1])
            if n < q then return 0 end
            redis.call('DECRBY', KEYS[1], q)
            return 1
            """;

    private final DatabaseInventoryService dbService;
    private final ProductRepository productRepository;
    /** Null when Spring's Redis autoconfig isn't active (DB-only mode). */
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> reserveScript;

    public RedisInventoryService(DatabaseInventoryService dbService,
                                 ProductRepository productRepository,
                                 ObjectProvider<StringRedisTemplate> redisProvider) {
        this.dbService = dbService;
        this.productRepository = productRepository;
        this.redis = redisProvider.getIfAvailable();
        this.reserveScript = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
        if (this.redis == null) {
            log.info("RedisInventoryService running in permanent DB-only mode (no StringRedisTemplate bean)");
        }
    }

    /** Exposed so tests can confirm which mode they're exercising. */
    public boolean isRedisActive() {
        return redis != null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (redis == null) return;
        log.info("Redis throttle active — bootstrapping stock from Postgres");
        reloadAll();
    }

    @Override
    public boolean tryReserve(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        // Composes the three single-layer primitives — same behaviour as
        // before the split, retained for the sync createOrder path.
        if (!tryReserveRedisOnly(productId, quantity)) {
            return false;
        }
        if (!tryReserveDbOnly(productId, quantity)) {
            log.warn("Redis allowed reservation but DB rejected (product={} qty={}); refunding Redis",
                    productId, quantity);
            releaseRedisOnly(productId, quantity);
            return false;
        }
        return true;
    }

    /**
     * Redis Lua DECR ONLY — the async API path stops here and publishes to
     * Kafka. Lazy-bootstraps from Postgres if the key is missing. Falls
     * through to the DB layer (which {@link #tryReserveDbOnly} delegates to
     * the dbService) when Redis itself errors — same fallback contract as
     * the original combined method.
     */
    @Override
    public boolean tryReserveRedisOnly(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        if (redis == null) {
            // No Redis layer at all — degrade to "let DB layer handle it."
            // Returning true here means the API publishes; if the DB then
            // can't honor it, the consumer's compensation runs cleanly.
            return true;
        }

        String key = STOCK_KEY_PREFIX + productId;
        Long result = runReserveScript(key, quantity);

        if (result == null) {
            log.warn("Redis reserve script failed for product {}; degrading to pass-through", productId);
            return true;
        }
        if (result == -1L) {
            log.info("Redis stock key missing for product {}; lazy-bootstrapping from DB", productId);
            loadProduct(productId);
            result = runReserveScript(key, quantity);
            if (result == null || result == -1L) {
                return true; // bootstrap failed — let DB decide
            }
        }
        return result != 0L;
    }

    /** Postgres atomic UPDATE only — the consumer's final defense. */
    @Override
    public boolean tryReserveDbOnly(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        return dbService.tryReserve(productId, quantity);
    }

    /** Redis INCR only — consumer compensation when DB rejects a Redis-allowed reservation. */
    @Override
    public void releaseRedisOnly(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        if (redis != null) {
            tryIncrementRedis(productId, quantity);
        }
    }

    /**
     * DB INCR only — consumer race-loser path. The race-losing consumer
     * decremented the DB in its own REQUIRES_NEW tx, so the unique-constraint
     * rollback can't reverse it. This method restores ONLY the DB; Redis is
     * left alone because the producer's single DECR is accounted for by the
     * winner's persisted order. See {@link InventoryService#releaseDbOnly}
     * for the full rationale.
     */
    @Override
    public void releaseDbOnly(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        dbService.release(productId, quantity);
    }

    /**
     * Compensation entry. Restores stock to BOTH layers.
     *
     * Order matters: DB first (source of truth, always restore), Redis second
     * (best-effort, recoverable on next reload). If we INCR'd Redis first and
     * the DB UPDATE then somehow failed, Redis would over-report. Restoring
     * DB first means a Redis hiccup at worst leaves Redis under-reporting
     * until the next reload — buyer sees sold-out wrongly for a moment, which
     * is strictly better than overselling.
     */
    @Override
    public void release(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        dbService.release(productId, quantity);
        if (redis != null) {
            tryIncrementRedis(productId, quantity);
        }
    }

    /**
     * Periodic reconciliation safety net. Enforces the two-store invariant:
     *
     *   redis["stock:" + productId]  ==  product.available_stock
     *
     * for every non-archived product. Necessary because {@link #release} does
     * its Redis INCR on a best-effort basis — a transient {@link DataAccessException}
     * during release is logged and swallowed so the caller doesn't fail, which
     * means a Redis blip can leave Redis under-reporting until something
     * pushes the truth back in.
     *
     * Drift sources this covers:
     *   - {@code tryIncrementRedis} swallowed a Redis exception during release
     *   - Redis was restarted with empty state; lazy bootstrap inside
     *     {@link #tryReserve} only fires on the first reservation per product
     *   - Manual DB edits or restored Postgres snapshots
     *
     * Wired to run every {@code flashsale.inventory.reconcile-interval-ms}
     * (default 60s) in addition to the one-shot reload on application startup.
     *
     * Race note: {@code available_stock} can change between the SELECT and the
     * Redis SET, so this method may briefly write a 1-2 unit stale value under
     * heavy traffic — the next pass corrects it. This is a periodic safety
     * net, not a strongly-consistent mirror.
     *
     * Each correction logs at ERROR with {@code productId} + old/new values
     * so a dashboard can alert on a non-zero correction count.
     */
    @Override
    @Scheduled(fixedDelayString = "${flashsale.inventory.reconcile-interval-ms:60000}")
    public void reconcile() {
        if (redis == null) return;
        int total = 0;
        int corrected = 0;
        try {
            for (Product p : productRepository.findAll()) {
                if (p.getStatus() == ProductStatus.ARCHIVED) continue;
                total++;
                String key = STOCK_KEY_PREFIX + p.getId();
                String expected = String.valueOf(p.getAvailableStock());
                String actual;
                try {
                    actual = redis.opsForValue().get(key);
                } catch (DataAccessException e) {
                    log.error("Reconcile read failed for productId={}: {}", p.getId(), e.toString());
                    continue;
                }
                if (!Objects.equals(expected, actual)) {
                    try {
                        redis.opsForValue().set(key, expected);
                        corrected++;
                        log.error("Reconcile corrected drift: productId={} oldRedis={} dbTruth={}",
                                p.getId(), actual, expected);
                    } catch (DataAccessException e) {
                        log.error("Reconcile write failed for productId={}: {}", p.getId(), e.toString());
                    }
                }
            }
            if (corrected > 0) {
                log.warn("Reconcile pass complete: {}/{} products required correction", corrected, total);
            } else {
                log.debug("Reconcile pass complete: {} products checked, no drift", total);
            }
        } catch (DataAccessException e) {
            log.error("Reconcile pass aborted: {}", e.toString());
        }
    }

    @Override
    public void loadProduct(Long productId) {
        if (redis == null) return;
        productRepository.findById(productId).ifPresent(this::writeStock);
    }

    @Override
    public void reloadAll() {
        if (redis == null) return;
        try {
            for (Product p : productRepository.findAll()) {
                if (p.getStatus() != ProductStatus.ARCHIVED) {
                    writeStock(p);
                }
            }
            log.info("Redis stock reload completed");
        } catch (DataAccessException e) {
            log.warn("Redis reloadAll failed; will retry on next startup: {}", e.toString());
        }
    }

    // ---- internals (only reachable when redis != null) ----

    private Long runReserveScript(String key, int quantity) {
        try {
            return redis.execute(reserveScript, List.of(key), String.valueOf(quantity));
        } catch (DataAccessException e) {
            log.warn("Redis reserve script threw for {}: {}", key, e.toString());
            return null;
        }
    }

    private void tryIncrementRedis(Long productId, int quantity) {
        try {
            redis.opsForValue().increment(STOCK_KEY_PREFIX + productId, quantity);
        } catch (DataAccessException e) {
            // G1 bug-class: DB has been restored, Redis has NOT. Drift is
            // permanent until reconcile() corrects it. Log at ERROR with
            // productId so dashboards can alert on a non-zero rate.
            log.error("Redis refund failed for productId={} qty={} — drift until next reconcile: {}",
                    productId, quantity, e.toString());
        }
    }

    private void writeStock(Product p) {
        try {
            redis.opsForValue().set(STOCK_KEY_PREFIX + p.getId(), String.valueOf(p.getAvailableStock()));
        } catch (DataAccessException e) {
            log.warn("Redis set failed for product {}: {}", p.getId(), e.toString());
        }
    }
}
