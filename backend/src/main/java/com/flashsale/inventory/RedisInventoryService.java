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
import org.springframework.stereotype.Service;

import java.util.List;

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
        if (redis == null) {
            return dbService.tryReserve(productId, quantity);
        }

        String key = STOCK_KEY_PREFIX + productId;
        Long result = runReserveScript(key, quantity);

        // ----- Degradation path: Redis itself errored on this request -----
        if (result == null) {
            log.warn("Redis reserve script failed for product {}; falling through to DB-only", productId);
            return dbService.tryReserve(productId, quantity);
        }

        // ----- Lazy bootstrap: key missing in Redis -----
        if (result == -1L) {
            log.info("Redis stock key missing for product {}; lazy-bootstrapping from DB", productId);
            loadProduct(productId);
            result = runReserveScript(key, quantity);
            if (result == null || result == -1L) {
                return dbService.tryReserve(productId, quantity);
            }
        }

        // ----- Sold out at the throttle — most requests die here, never hit DB -----
        if (result == 0L) {
            return false;
        }

        // ----- Redis reserved. Confirm at the DB final defense. -----
        // If DB rejects (drift between layers — restored snapshot, missed
        // compensation, etc.), refund Redis to re-sync.
        boolean dbReserved = dbService.tryReserve(productId, quantity);
        if (!dbReserved) {
            log.warn("Redis allowed reservation but DB rejected (product={} qty={}); refunding Redis",
                    productId, quantity);
            tryIncrementRedis(key, quantity);
            return false;
        }
        return true;
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
            tryIncrementRedis(STOCK_KEY_PREFIX + productId, quantity);
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

    private void tryIncrementRedis(String key, int quantity) {
        try {
            redis.opsForValue().increment(key, quantity);
        } catch (DataAccessException e) {
            // Stock IS restored in Postgres at this point. Redis just falls
            // behind until the next reloadAll resyncs. Don't fail the caller
            // for a transient Redis blip — the buyer-visible cost is at worst
            // a window of incorrect "sold out" responses; the DB value is right.
            log.warn("Redis refund failed for {} qty {} — will reconcile on next reload: {}",
                    key, quantity, e.toString());
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
