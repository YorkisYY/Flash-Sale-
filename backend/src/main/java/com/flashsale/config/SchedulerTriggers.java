package com.flashsale.config;

import com.flashsale.inventory.InventoryService;
import com.flashsale.order.OrderExpiryJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ONLY place the periodic timers are registered. Gated on
 * {@code flashsale.scheduler.enabled} (env {@code SCHEDULER_ENABLED}, default
 * true): when false this bean isn't created, so NO {@code @Scheduled} method
 * exists on the instance and the timers never fire.
 *
 * <p><b>Why a separate trigger bean instead of {@code @Scheduled} on the jobs
 * themselves:</b> in Kubernetes the traffic-serving backend runs at
 * {@code replicas: 2} but must NOT run the timers, while a dedicated scheduler
 * Deployment runs the same image at {@code replicas: 1} with the flag on. One
 * image, two roles. Gating one shared bean is cleaner than annotating each job
 * and relying on lock timing to suppress duplicates. Single-execution is now
 * STRUCTURAL (one instance has the clock), not a function of ShedLock tuning.
 *
 * <p>The underlying job methods keep their own {@code @SchedulerLock} +
 * {@code @Transactional} — those still apply because the calls below go through
 * the beans' proxies. ShedLock stays as defense-in-depth for the brief window
 * during a scheduler rolling update when two scheduler pods overlap.
 */
@Component
@ConditionalOnProperty(name = "flashsale.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerTriggers {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTriggers.class);

    private final OrderExpiryJob orderExpiryJob;
    private final InventoryService inventoryService;

    public SchedulerTriggers(OrderExpiryJob orderExpiryJob, InventoryService inventoryService) {
        this.orderExpiryJob = orderExpiryJob;
        this.inventoryService = inventoryService;
        log.info("Scheduler ENABLED on this instance — it owns the order-expiry sweep and Redis reconcile timers");
    }

    /** Fires the order-expiry sweep; the real work + lock + tx live on {@link OrderExpiryJob#sweep()}. */
    @Scheduled(fixedDelayString = "${flashsale.order.expiry-scan-interval-ms:60000}",
               initialDelayString = "${flashsale.order.expiry-initial-delay-ms:0}")
    public void expirySweep() {
        orderExpiryJob.sweep();
    }

    /** Fires the Redis↔DB reconcile; the real work + lock live on {@code RedisInventoryService#reconcile()}. */
    @Scheduled(fixedDelayString = "${flashsale.inventory.reconcile-interval-ms:60000}",
               initialDelayString = "${flashsale.inventory.reconcile-initial-delay-ms:0}")
    public void reconcile() {
        inventoryService.reconcile();
    }
}
