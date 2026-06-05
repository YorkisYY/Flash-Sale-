package com.flashsale.inventory;

import com.flashsale.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Postgres atomic-UPDATE layer.
 *
 * In a fully wired stack this is BOTH:
 *   - the final defense, sitting behind {@link RedisInventoryService}'s Lua
 *     throttle — Redis can be wrong (drift, restart from stale RDB) but the
 *     SQL `WHERE available_stock >= :qty` clause cannot oversell.
 *   - the standalone fallback when Redis isn't reachable — the Redis layer
 *     delegates here on DataAccessException so the system stays correct (just
 *     slower) under a Redis outage.
 *
 * REQUIRES_NEW so the reservation commits independently of any outer
 * transaction — we never want a paid-but-stock-not-decremented state if the
 * outer order-creation transaction rolls back after a successful reservation.
 *
 * In a DB-only deployment (no Redis bean), this class is the only
 * {@link InventoryService} bean and is autowired everywhere directly.
 */
@Service
public class DatabaseInventoryService implements InventoryService {

    private final ProductRepository productRepository;

    public DatabaseInventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        int updated = productRepository.decrementStock(productId, quantity);
        return updated == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
        productRepository.incrementStock(productId, quantity);
    }
}
