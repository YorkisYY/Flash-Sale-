package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.inventory.InventoryService;
import com.flashsale.kafka.OrderRequestedEvent;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final Duration reservationTtl;

    public OrderService(ProductRepository productRepository,
                        OrderRepository orderRepository,
                        InventoryService inventoryService,
                        @Value("${flashsale.order.reservation-ttl-minutes:15}") int ttlMinutes) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.reservationTtl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Hot path. Reserve stock atomically, then persist a CREATED order with a
     * 15-minute payment TTL.
     *
     * If stock reservation fails → throw {@link SoldOutException}; no order row
     * is written.
     *
     * The reservation runs in its own REQUIRES_NEW transaction so it commits
     * even if the surrounding order-creation transaction rolls back (we'd then
     * release on the way out — see compensating release below).
     */
    @Transactional
    public Order createOrder(CreateOrderCommand cmd) {
        Product product = productRepository.findById(cmd.productId())
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + cmd.productId()));

        if (Instant.now().isBefore(product.getDropStartsAt())) {
            throw new DropNotOpenException(product.getDropStartsAt());
        }

        boolean reserved = inventoryService.tryReserve(cmd.productId(), cmd.quantity());
        if (!reserved) {
            throw new SoldOutException(cmd.productId());
        }

        try {
            BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(cmd.quantity()));
            Order order = new Order();
            order.setProductId(cmd.productId());
            order.setQuantity(cmd.quantity());
            order.setBuyerName(cmd.buyerName());
            order.setBuyerEmail(cmd.buyerEmail());
            order.setBuyerPhone(cmd.buyerPhone());
            order.setShippingAddress(cmd.shippingAddress());
            order.setAmount(amount);
            order.setStatus(OrderStatus.CREATED);
            order.setPaymentIdempotencyKey(UUID.randomUUID().toString().replace("-", ""));
            order.setProvider(cmd.provider() == null ? "PAYUNI" : cmd.provider().toUpperCase());
            order.setExpiresAt(Instant.now().plus(reservationTtl));
            return orderRepository.save(order);
        } catch (RuntimeException e) {
            // Compensating release: reservation already committed in its own TX.
            log.error("Order persist failed after stock reservation; releasing {} units of product {}",
                    cmd.quantity(), cmd.productId(), e);
            inventoryService.release(cmd.productId(), cmd.quantity());
            throw e;
        }
    }

    /**
     * Async-path entry point — invoked by {@code OrderEventConsumer} after
     * the API layer's Redis Lua DECR and Kafka publish.
     *
     * Semantics:
     *   1. Idempotency check: if an order with this {@code externalId} already
     *      exists, this is a redelivery. Log and return the existing row.
     *   2. DB conditional UPDATE as the final correctness guard: Redis
     *      admitted the request, but Postgres has the last word. If DB
     *      rejects (drift, restored snapshot), compensate the Redis side
     *      and skip — no order written, but no oversell either.
     *   3. INSERT the order row keyed on the pre-generated externalId. On
     *      duplicate-key (race with a concurrent redelivery of the same
     *      event), absorb the {@link DataIntegrityViolationException} and
     *      return the existing row — the partition-per-product partition
     *      key on the producer makes this race extremely rare, but defending
     *      against it cleans up the corner case.
     *
     * The whole method runs inside one {@code @Transactional} (REQUIRED) so
     * an exception between the DB UPDATE and the order INSERT rolls both
     * back atomically. The consumer's manual ack runs only AFTER this
     * method returns normally — so any failure leaves the partition unacked
     * and triggers redelivery, which the externalId guard then absorbs.
     */
    @Transactional
    public Order createOrderFromEvent(OrderRequestedEvent event) {
        // Idempotency check — at-least-once delivery means the same event can
        // arrive twice. The duplicate path is logged at DEBUG (not WARN);
        // it's expected operational behaviour, not an anomaly.
        var existing = orderRepository.findByExternalId(event.externalId());
        if (existing.isPresent()) {
            log.debug("Event externalId={} already applied; skipping (idempotent)", event.externalId());
            return existing.get();
        }

        Product product = productRepository.findById(event.productId())
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + event.productId()));

        // Postgres atomic UPDATE — the final defense. Redis already said yes,
        // but if the two layers have drifted (Redis-restart bootstrap stale,
        // manual DB edit, restored snapshot), the DB row-level lock wins and
        // we compensate Redis to bring it back in line.
        if (!inventoryService.tryReserveDbOnly(event.productId(), event.quantity())) {
            log.warn("DB rejected reservation for product {} qty {} (event externalId={}); compensating Redis",
                    event.productId(), event.quantity(), event.externalId());
            inventoryService.releaseRedisOnly(event.productId(), event.quantity());
            return null;
        }

        BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(event.quantity()));
        Order order = new Order();
        order.setExternalId(event.externalId());
        order.setProductId(event.productId());
        order.setQuantity(event.quantity());
        order.setBuyerName(event.buyerName());
        order.setBuyerEmail(event.buyerEmail());
        order.setBuyerPhone(event.buyerPhone());
        order.setShippingAddress(event.shippingAddress());
        order.setAmount(amount);
        order.setStatus(OrderStatus.CREATED);
        order.setPaymentIdempotencyKey(UUID.randomUUID().toString().replace("-", ""));
        order.setProvider(event.provider() == null ? "STRIPE" : event.provider().toUpperCase());
        order.setExpiresAt(Instant.now().plus(reservationTtl));

        try {
            return orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException raceDup) {
            // A second consumer thread raced us (rare rebalance overlap, or
            // a redelivery the existsByExternalId check above didn't yet see
            // due to read-vs-flush ordering inside the tx). Our tryReserveDbOnly
            // committed in its own REQUIRES_NEW tx, so the constraint-violation
            // rollback can't undo it — we have to actively release the DB
            // back to the winner's correct state. Redis is NOT touched: the
            // producer's single Redis DECR is accounted for by the winner's
            // persisted order; restoring Redis here would over-report by 1.
            log.info("External id {} won by concurrent consumer; releasing our DB reservation (Redis untouched)",
                    event.externalId());
            inventoryService.releaseDbOnly(event.productId(), event.quantity());
            return orderRepository.findByExternalId(event.externalId()).orElse(null);
        }
    }

    /**
     * Webhook-driven transition. Idempotent at the caller level — the caller
     * checks PaymentEvent uniqueness before invoking this.
     */
    @Transactional
    public void markPaid(Long orderId, String providerRef) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));

        switch (order.getStatus()) {
            case CREATED -> {
                order.setStatus(OrderStatus.PAID);
                order.setProviderRef(providerRef);
                log.info("order {} → PAID (providerRef={})", orderId, providerRef);
            }
            case PAID, SHIPPED, COMPLETED -> {
                // Duplicate or late callback after manual shipping; no-op.
                log.info("order {} already in terminal+ state {}; skipping markPaid", orderId, order.getStatus());
            }
            case EXPIRED, CANCELLED -> {
                // Payment arrived after we already gave the stock back. Rare race;
                // log it loudly so the user knows to investigate / refund.
                log.warn("payment received for already-released order {} (status={}); manual refund likely needed",
                        orderId, order.getStatus());
            }
        }
    }

    @Transactional
    public Order markShipped(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("can only ship a PAID order, got " + order.getStatus());
        }
        order.setStatus(OrderStatus.SHIPPED);
        return order;
    }

    @Transactional
    public Order markCompleted(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new IllegalStateException("can only complete a SHIPPED order, got " + order.getStatus());
        }
        order.setStatus(OrderStatus.COMPLETED);
        return order;
    }

    /**
     * Buyer cancellation before payment. Releases the reservation.
     */
    @Transactional
    public void cancel(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new IllegalStateException("can only cancel a CREATED order, got " + order.getStatus());
        }
        order.setStatus(OrderStatus.CANCELLED);
        inventoryService.release(order.getProductId(), order.getQuantity());
    }

    @Transactional(readOnly = true)
    public Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));
    }

    /**
     * Public-API order lookup. The wire-side identifier is the UUID
     * externalId — internal Long ids must never reach the HTTP layer
     * (Long.parseLong on a UUID is the regression this exists to prevent).
     */
    @Transactional(readOnly = true)
    public Order requireOrderByExternalId(String externalId) {
        return orderRepository.findByExternalId(externalId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + externalId));
    }
}
