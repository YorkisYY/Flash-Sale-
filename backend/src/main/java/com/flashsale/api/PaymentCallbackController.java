package com.flashsale.api;

import com.flashsale.domain.PaymentEvent;
import com.flashsale.order.OrderService;
import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.payuni.PayUniProvider;
import com.flashsale.repository.PaymentEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PayUni notify/return webhook. Idempotent: duplicate callbacks for the same
 * (provider, provider_txn_id) are absorbed and still return the success ack
 * PayUni expects.
 */
@RestController
@RequestMapping("/api/payments/payuni")
public class PaymentCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackController.class);

    // Inject the concrete PayUniProvider directly so this controller stays
    // unambiguous now that Stripe + ECPay providers also live in the context.
    private final PayUniProvider paymentProvider;
    private final OrderService orderService;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentCallbackController(PayUniProvider paymentProvider,
                                     OrderService orderService,
                                     PaymentEventRepository paymentEventRepository) {
        this.paymentProvider = paymentProvider;
        this.orderService = orderService;
        this.paymentEventRepository = paymentEventRepository;
    }

    /**
     * PayUni posts form-encoded data; Spring binds it as a Map.
     * The exact field names will come from the PayUni docs and are decoded by
     * {@link com.flashsale.payment.payuni.PayUniProvider#verifyCallback}.
     */
    @PostMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam Map<String, String> payload,
                                           @RequestBody(required = false) String rawBody) {
        log.info("PayUni callback received with {} fields", payload.size());

        PaymentResult result;
        try {
            result = paymentProvider.verifyCallback(payload);
        } catch (UnsupportedOperationException notImplemented) {
            // PayUniProvider not wired yet — drop the callback (the docs aren't in).
            log.warn("PayUni callback ignored: provider not yet implemented");
            return ResponseEntity.ok("PROVIDER_NOT_IMPLEMENTED");
        } catch (RuntimeException badSig) {
            log.warn("PayUni callback rejected: signature/checksum invalid", badSig);
            return ResponseEntity.badRequest().body("INVALID_SIGNATURE");
        }

        boolean isFirstTimeEvent = recordEvent(result, rawBody == null ? payload.toString() : rawBody);
        if (isFirstTimeEvent && result.success()) {
            orderService.markPaid(result.orderId(), result.providerTxnId());
        }

        String ack = result.ackResponse() != null ? result.ackResponse() : "OK";
        return ResponseEntity.ok(ack);
    }

    /**
     * Insert a PaymentEvent row. The UNIQUE(provider, provider_txn_id) index
     * absorbs duplicates — return false if this is a repeat.
     */
    @Transactional
    boolean recordEvent(PaymentResult result, String rawPayload) {
        PaymentEvent event = new PaymentEvent();
        event.setOrderId(result.orderId());
        event.setProvider("PAYUNI");
        event.setProviderTxnId(result.providerTxnId());
        event.setRawPayload(rawPayload);
        event.setProcessed(true);
        try {
            paymentEventRepository.saveAndFlush(event);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Duplicate PayUni callback for txn {}; absorbed", result.providerTxnId());
            return false;
        }
    }
}
