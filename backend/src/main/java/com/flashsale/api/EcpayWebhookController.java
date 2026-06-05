package com.flashsale.api;

import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentResultService;
import com.flashsale.payment.PaymentSignatureException;
import com.flashsale.payment.ecpay.EcpayProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ECPay ReturnURL handler (server-to-server).
 *
 * ECPay POSTs form-encoded; verifyCallback recomputes CheckMacValue and
 * rejects on mismatch. PaymentResultService.applyResult records the dedup
 * row + flips the order in one transaction.
 *
 * ECPay considers the callback handled iff the response body is exactly
 * "1|OK". On a duplicate (unique constraint absorbed it) we still return
 * "1|OK" so ECPay stops retrying. On markPaid failure we deliberately do
 * NOT return "1|OK" — letting ECPay retry is the point of rolling the
 * event back.
 */
@RestController
@RequestMapping("/api/payments/ecpay")
public class EcpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(EcpayWebhookController.class);

    private final EcpayProvider ecpayProvider;
    private final PaymentResultService paymentResultService;

    public EcpayWebhookController(EcpayProvider ecpayProvider,
                                  PaymentResultService paymentResultService) {
        this.ecpayProvider = ecpayProvider;
        this.paymentResultService = paymentResultService;
    }

    @PostMapping(value = "/callback",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> callback(@RequestParam Map<String, String> payload) {

        PaymentResult result;
        try {
            result = ecpayProvider.verifyCallback(payload);
        } catch (PaymentSignatureException bad) {
            log.warn("ECPay webhook rejected: {}", bad.getMessage());
            return ResponseEntity.badRequest().body("0|INVALID");
        }

        try {
            paymentResultService.applyResult(EcpayProvider.PROVIDER_NAME, result, payload.toString());
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Duplicate ECPay TradeNo {} absorbed", result.providerTxnId());
            // Same TradeNo arrived twice — already applied; ack with the exact
            // string ECPay expects so it stops retrying.
        } catch (RuntimeException failed) {
            // markPaid throw / unknown order / infra hiccup: tx already rolled
            // back the dedup-row insert. Return anything BUT "1|OK" so ECPay
            // keeps retrying against a clean slate.
            log.error("Failed to apply ECPay event {}: {}",
                    result.providerTxnId(), failed.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("0|RETRY");
        }
        return ResponseEntity.ok(EcpayProvider.ACK_OK);
    }
}
