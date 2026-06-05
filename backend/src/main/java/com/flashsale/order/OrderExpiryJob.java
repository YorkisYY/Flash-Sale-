package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps CREATED orders whose expires_at has passed, flips them to EXPIRED,
 * and releases the reserved stock back to the pool.
 *
 * Runs on a fixed delay (default 60s). Single-node only — if/when this app is
 * deployed multi-node, add a distributed lock (e.g. Redisson) around the sweep.
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

    @Scheduled(fixedDelayString = "${flashsale.order.expiry-scan-interval-ms:60000}")
    @Transactional
    public void sweep() {
        List<Order> expired = orderRepository.findExpiredOrders(OrderStatus.CREATED, Instant.now());
        if (expired.isEmpty()) return;

        for (Order order : expired) {
            order.setStatus(OrderStatus.EXPIRED);
            inventoryService.release(order.getProductId(), order.getQuantity());
            log.info("order {} expired; released {} units of product {}",
                    order.getId(), order.getQuantity(), order.getProductId());
        }
    }
}
