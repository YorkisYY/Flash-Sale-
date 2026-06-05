package com.flashsale.payment;

import com.flashsale.domain.PaymentEvent;
import com.flashsale.order.OrderService;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.PaymentEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic webhook-outcome application: insert the PaymentEvent dedup row AND
 * flip the order to PAID in ONE transaction. This is the only correct shape
 * for "unique-constraint-as-dedup" idempotency.
 *
 *  Why one tx:
 *    The webhook idempotency guard is the UNIQUE(provider, provider_txn_id)
 *    index on payment_event. If the event insert and markPaid commit
 *    separately, a markPaid failure leaves a committed event row behind →
 *    the gateway's next retry inserts a duplicate → unique constraint
 *    blocks it → markPaid is never retried → order stranded in CREATED
 *    forever. Wrap both in one tx so failure rolls them back together; the
 *    next retry then has a clean slate to try again.
 *
 *  Why a separate @Service:
 *    The old code put @Transactional(REQUIRES_NEW) on a helper method inside
 *    the controller, then self-invoked it from the @PostMapping method.
 *    Spring's transactional proxy only wraps EXTERNAL calls; self-invocation
 *    skips the proxy entirely, so REQUIRES_NEW was silently ignored. Moving
 *    the work into another Spring bean makes the proxy boundary real.
 *
 *  Concurrency under retries:
 *    Two simultaneous deliveries of the same event both attempt to insert.
 *    Postgres serializes them on the unique index; one commits, the other
 *    sees DataIntegrityViolationException → its tx (including any work
 *    markPaid did) rolls back. Either way, exactly one tx commits the order
 *    change. The other returns up the stack, controller acks the gateway.
 *
 *  markPaid joins this tx:
 *    OrderService.markPaid is @Transactional (default REQUIRED). Called
 *    from inside this method's tx, it joins — no nested-tx surprises.
 */
@Service
public class PaymentResultService {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultService.class);

    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    public PaymentResultService(PaymentEventRepository paymentEventRepository,
                                OrderRepository orderRepository,
                                OrderService orderService) {
        this.paymentEventRepository = paymentEventRepository;
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    /**
     * @throws IllegalArgumentException                                 if the order doesn't exist
     * @throws org.springframework.dao.DataIntegrityViolationException  on duplicate
     *         (provider, provider_txn_id) — caller treats this as a successful
     *         idempotent absorption. With the existsById pre-check below, this
     *         exception type is UNAMBIGUOUS: the only remaining way to throw
     *         it is the dedup unique constraint, never a stale FK to orders.
     */
    @Transactional
    public void applyResult(String provider, PaymentResult result, String rawPayload) {
        // Pre-validate the order exists. Without this, a gateway event
        // referencing an unknown order would trip the payment_event.order_id
        // FK as a DataIntegrityViolationException — which the controller's
        // dedup catch would silently absorb as if it were a duplicate. Same
        // exception class, completely different meaning. Surface the unknown
        // order as a clean IllegalArgumentException so the catch is precise.
        if (!orderRepository.existsById(result.orderId())) {
            throw new IllegalArgumentException("order not found: " + result.orderId());
        }

        PaymentEvent event = new PaymentEvent();
        event.setOrderId(result.orderId());
        event.setProvider(provider);
        event.setProviderTxnId(result.providerTxnId());
        event.setRawPayload(rawPayload);
        event.setProcessed(true);
        // saveAndFlush forces the unique-constraint check to happen NOW, before
        // markPaid does any work. On duplicate, we abort early and roll back
        // a clean tx (no half-applied order state).
        paymentEventRepository.saveAndFlush(event);

        if (result.success()) {
            // markPaid joins this tx (REQUIRED). If it throws, the event
            // insert above rolls back with it — that's the whole point.
            orderService.markPaid(result.orderId(), result.providerTxnId());
        }
        log.debug("PaymentResult applied: provider={} txn={} orderId={} success={}",
                provider, result.providerTxnId(), result.orderId(), result.success());
    }
}
