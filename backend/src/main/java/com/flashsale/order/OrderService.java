package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.inventory.InventoryService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
}
