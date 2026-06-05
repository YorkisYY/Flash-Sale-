package com.flashsale.inventory;

/**
 * Stock reservation abstraction. Production-shipping impls:
 *
 *   - {@link DatabaseInventoryService} (always present): atomic conditional UPDATE
 *     in Postgres. Source of truth, final defense, and standalone fallback when
 *     Redis is down.
 *   - {@link RedisInventoryService} (present when Redis is reachable, @Primary):
 *     atomic check-and-DECR in Redis via Lua, then delegates to the DB impl as
 *     final defense. Most sold-out responses come back here without ever touching
 *     Postgres, which is the "削峰" (peak shaving) layer.
 *
 * --- Concurrency strategy tradeoff (the core of this system) ---
 *
 *  1. Atomic conditional UPDATE (the FINAL DEFENSE — never removed)
 *       UPDATE product SET available_stock = available_stock - :qty
 *         WHERE id = :id AND available_stock >= :qty
 *     - Correct under any concurrency without app-side locking.
 *     - rowsAffected == 0 ⇒ sold out; no read-modify-write race possible.
 *     - Throughput bounded by row-level lock contention in Postgres on the hot row;
 *       a few thousand ops/sec on a single hot product before queues form.
 *
 *  2. Optimistic lock (@Version on Product, retry on OptimisticLockException)
 *     - Works, but every losing thread retries — cascading failures under heavy
 *       contention. The thundering-herd retry storm.
 *     - We KEEP @Version on Product for *other* edits (name/price/status changes)
 *       to demonstrate the pattern; the hot stock path deliberately bypasses it.
 *
 *  3. Pessimistic lock (SELECT ... FOR UPDATE)
 *     - Serializes all writers on the hot row; throughput collapses under load.
 *
 *  4. Redis pre-deduction (the THROTTLE — phase 2, implemented)
 *     - Lua wraps check-and-DECRBY atomically. Sold-out requests never reach Postgres.
 *     - Redis is NOT the source of truth — Postgres is. Redis is a derived cache
 *     that can be rebuilt from Postgres on startup or on drift detection.
 *     - DB atomic UPDATE remains as the final defense. If Redis allows a reservation
 *     but Postgres rejects (drift, restored snapshot, etc.), we refund Redis to
 *     re-sync the two layers.
 *     - Compensation discipline:
 *         order creation failure   → release() refunds BOTH Redis + Postgres.
 *         scheduled order expiry   → same release() path.
 *         buyer cancellation       → same release() path.
 *     - Degradation: when Redis throws DataAccessException, fall straight through
 *     to the DB layer. Slower but correct. See {@link RedisInventoryService}.
 */
public interface InventoryService {

    /**
     * Attempt to reserve {@code quantity} units of {@code productId}.
     *
     * @return true if reservation succeeded; false if not enough stock.
     */
    boolean tryReserve(Long productId, int quantity);

    /**
     * Return previously reserved units to the available pool. Called on order
     * EXPIRED (payment timeout) or CANCELLED. With Redis enabled, MUST also
     * refund the Redis counter — otherwise stock returns to the buyer pool in
     * Postgres but the Redis throttle still sees the lower count, and the
     * returned stock stays unsellable until the next reload.
     */
    void release(Long productId, int quantity);

    /**
     * Push a single product's current Postgres available_stock into Redis.
     * No-op for DB-only deployments. Called on product creation and as the
     * lazy bootstrap when {@link #tryReserve} sees a missing key.
     */
    default void loadProduct(Long productId) {}

    /**
     * Reload every non-archived product into Redis. No-op for DB-only.
     * Called once on application startup (Redis is a derived cache, not a
     * source of truth — startup is when you re-derive it).
     */
    default void reloadAll() {}
}
