package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.repository.OrderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps CREATED orders whose expires_at has passed, flips them to EXPIRED,
 * and releases the reserved stock back to the pool.
 *
 * The periodic timer is owned by {@link com.flashsale.config.SchedulerTriggers}
 * (only the replicas=1 scheduler Deployment registers it); this class just
 * provides the {@code sweep()} body, its lock, and its transaction. Tests call
 * {@code sweep()} directly.
 *
 * <p>--- Exactly-once release across multiple pods ---
 *
 *  Each expired order is closed via an ATOMIC conditional status transition
 *  ({@link OrderRepository#transitionStatus}: {@code UPDATE ... SET
 *  status=EXPIRED WHERE id=? AND status=CREATED}). Stock is released ONLY when
 *  that UPDATE matched exactly one row — i.e. this worker is the one that
 *  actually moved the order out of CREATED. If two pods race the same order,
 *  the loser's UPDATE matches zero rows and it does NOT release. This makes
 *  double-release impossible even under READ COMMITTED, even multi-pod, even
 *  with no lock at all.
 *
 *  {@code @SchedulerLock} (ShedLock, Redis-backed) is layered on top so that
 *  in a multi-replica deployment only one pod runs the scan per tick — but
 *  that is an EFFICIENCY optimisation (avoids N redundant scans), NOT the
 *  correctness mechanism. The atomic guard above is the correctness mechanism
 *  and stands on its own if the lock is unavailable (e.g. Redis down → jobs
 *  run on every pod, guard still prevents double-release).
 */
@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    public OrderExpiryJob(OrderRepository orderRepository, InventoryService inventoryService) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
    }

    // initialDelay defaults to 0 (fire immediately on startup, unchanged
    // production behaviour). Tests push it far out so the scheduler never
    // auto-fires and contends with the lock during an explicit sweep() call.
    // The timer lives in SchedulerTriggers (only the replicas=1 scheduler
    // Deployment registers it), so single-execution across replicas is now
    // STRUCTURAL. @SchedulerLock stays purely as defense-in-depth for the brief
    // scheduler rolling-update overlap; lockAtLeastFor is back to a small 5s
    // because the lock is no longer the primary single-execution mechanism.
    // The atomic guard below remains the correctness layer.
    @SchedulerLock(name = "orderExpirySweep", lockAtMostFor = "55s", lockAtLeastFor = "5s")
    @Transactional
    public void sweep() {
        List<Order> expired = orderRepository.findExpiredOrders(OrderStatus.CREATED, Instant.now());
        if (expired.isEmpty()) return;

        for (Order order : expired) {
            // Atomic guard: only the worker that actually moves this order out
            // of CREATED releases its stock. Loser matches 0 rows → skips.
            int won = orderRepository.transitionStatus(
                    order.getId(), OrderStatus.CREATED, OrderStatus.EXPIRED);
            if (won == 1) {
                inventoryService.release(order.getProductId(), order.getQuantity());
                log.info("order {} expired; released {} units of product {}",
                        order.getId(), order.getQuantity(), order.getProductId());
            } else {
                log.debug("order {} already left CREATED before this worker claimed it; skipping release",
                        order.getId());
            }
        }
    }
}
