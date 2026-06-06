package com.flashsale.payment.ecpay;

import com.flashsale.domain.Order;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentSession;
import com.flashsale.payment.PaymentSignatureException;
import com.flashsale.payment.StaleCallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ECPay AioCheckOut V5 integration.
 *
 *  createSession:
 *    Builds the V5 parameter set + CheckMacValue, returns an auto-submitting
 *    HTML form whose action points at the stage endpoint. The frontend writes
 *    the HTML into the document and submits it (browser-side redirect via POST,
 *    which is how ECPay expects to receive merchants).
 *
 *  verifyCallback:
 *    ECPay POSTs form-encoded params to ReturnURL (server-to-server). We
 *    recompute CheckMacValue from every field EXCEPT CheckMacValue itself,
 *    using the same algorithm we used to build the outbound request. Any
 *    mismatch → PaymentSignatureException → 400. Only RtnCode == "1" is a
 *    real success.
 *
 *  Idempotency:
 *    TradeNo (ECPay's own transaction id) is the providerTxnId — the same
 *    TradeNo is repeated on every retry of the same payment, so the UNIQUE
 *    (provider, provider_txn_id) constraint absorbs duplicates.
 *
 *  Merchant trade number:
 *    "FS" + zero-padded order id (20 chars total). This is the merchant-side
 *    idempotent reference; we recover the order id from MerchantTradeNo on
 *    the callback, no DB lookup needed.
 *
 *  Ack response:
 *    ECPay expects literally "1|OK" on the ReturnURL — anything else triggers
 *    retries. We return that string even on duplicate callbacks.
 */
@Component
@EnableConfigurationProperties(EcpayProperties.class)
public class EcpayProvider implements PaymentProvider {

    public static final String PROVIDER_NAME = "ECPAY";
    public static final String ACK_OK = "1|OK";

    private static final Logger log = LoggerFactory.getLogger(EcpayProvider.class);
    private static final DateTimeFormatter TRADE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);
    /** ECPay is a Taiwan gateway; PaymentDate / TradeDate are in Asia/Taipei. */
    private static final ZoneId ECPAY_ZONE = ZoneId.of("Asia/Taipei");

    private final EcpayProperties props;
    private final Duration replayWindow;

    public EcpayProvider(EcpayProperties props,
                         @Value("${flashsale.payment.replay-window-seconds:600}") long replayWindowSeconds) {
        this.props = props;
        this.replayWindow = Duration.ofSeconds(replayWindowSeconds);
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public PaymentSession createSession(Order order) {
        String merchantTradeNo = buildMerchantTradeNo(order.getId());
        String tradeDate = TRADE_DATE_FMT.format(
                order.getCreatedAt().atZone(ZoneId.of("Asia/Taipei")));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID", props.getMerchantId());
        params.put("MerchantTradeNo", merchantTradeNo);
        params.put("MerchantTradeDate", tradeDate);
        params.put("PaymentType", "aio");
        params.put("TotalAmount", String.valueOf(order.getAmount().intValueExact()));
        params.put("TradeDesc", "FlashSaleOrder");
        // ItemName: NO '#' (ECPay treats it as multi-item separator) and NO
        // spaces — both have shown up as the cause of CheckMacValue
        // mismatches against the real stage server. Keep this restricted
        // to ASCII letters + digits.
        params.put("ItemName", "FlashSaleOrder" + order.getId());
        params.put("ReturnURL", props.getReturnUrl());
        params.put("OrderResultURL", props.getOrderResultUrl() + "/" + order.getId());
        params.put("ClientBackURL", props.getClientBackUrl());
        params.put("ChoosePayment", "ALL");
        params.put("EncryptType", "1");

        String checkMac = EcpayCheckMac.compute(params, props.getHashKey(), props.getHashIv());
        params.put("CheckMacValue", checkMac);

        return PaymentSession.form(renderAutoSubmitForm(params));
    }

    @Override
    public PaymentResult verifyCallback(Map<String, String> payload) {
        String received = payload.get("CheckMacValue");
        if (received == null || received.isBlank()) {
            throw new PaymentSignatureException("ECPay callback missing CheckMacValue");
        }

        Map<String, String> toSign = new LinkedHashMap<>(payload);
        toSign.remove("CheckMacValue");
        String expected = EcpayCheckMac.compute(toSign, props.getHashKey(), props.getHashIv());
        if (!expected.equalsIgnoreCase(received)) {
            throw new PaymentSignatureException("ECPay CheckMacValue mismatch");
        }

        // Replay-attack defence: signature verified, but if the payload
        // timestamp is older than our configured window, this is most likely
        // a captured-and-replayed legitimate callback. The PaymentEvent
        // unique constraint already blocks identical-TradeNo duplicates;
        // this catches the same-TradeNo (in case of TradeNo collision /
        // reset) AND the "we want a tighter freshness window than the
        // gateway provides" case.
        rejectIfStale(payload);

        String rtnCode = payload.get("RtnCode");
        boolean success = "1".equals(rtnCode);

        String tradeNo = payload.get("TradeNo");
        String merchantTradeNo = payload.get("MerchantTradeNo");
        Long orderId = parseOrderIdFromMerchantTradeNo(merchantTradeNo);

        log.info("ECPay callback verified: order={} tradeNo={} rtnCode={}",
                orderId, tradeNo, rtnCode);
        return new PaymentResult(success, orderId, tradeNo, ACK_OK);
    }

    /**
     * Reject if the payload's PaymentDate is older than the replay window.
     * Prefer PaymentDate (when the payment was actually captured) over
     * TradeDate (when the trade was initiated) — replay attacks substitute
     * a captured success notification, so freshness of payment is what we
     * care about.
     *
     * If PaymentDate is missing or unparseable (e.g. on some failed-payment
     * callbacks), we don't enforce — better to let the request through to
     * the downstream {@code applyResult} than to false-positive reject a
     * weird-but-legitimate gateway message. {@code applyResult} won't change
     * order state for non-success {@code RtnCode} anyway.
     */
    private void rejectIfStale(Map<String, String> payload) {
        String paymentDateStr = payload.get("PaymentDate");
        if (paymentDateStr == null || paymentDateStr.isBlank()) {
            return; // gateway didn't include it — can't enforce, don't block
        }
        Instant paymentInstant;
        try {
            paymentInstant = LocalDateTime.parse(paymentDateStr, TRADE_DATE_FMT)
                    .atZone(ECPAY_ZONE)
                    .toInstant();
        } catch (DateTimeParseException badFormat) {
            log.warn("ECPay PaymentDate {} unparseable; skipping replay check: {}",
                    paymentDateStr, badFormat.getMessage());
            return;
        }
        Duration age = Duration.between(paymentInstant, Instant.now());
        if (age.compareTo(replayWindow) > 0) {
            // Don't throw PaymentSignatureException — signature WAS valid,
            // and the controller's signature-failure response is 400, which
            // would make ECPay retry the (still-stale) message endlessly.
            // StaleCallbackException is caught separately and produces a
            // 200 ack so the gateway stops trying.
            throw new StaleCallbackException(
                    "PaymentDate=" + paymentDateStr + " is " + age.toSeconds()
                            + "s old (window " + replayWindow.toSeconds() + "s) — replay rejected");
        }
    }

    static String buildMerchantTradeNo(long orderId) {
        return "FS" + String.format("%018d", orderId);
    }

    static Long parseOrderIdFromMerchantTradeNo(String merchantTradeNo) {
        if (merchantTradeNo == null || merchantTradeNo.length() < 3 || !merchantTradeNo.startsWith("FS")) {
            throw new PaymentSignatureException("ECPay callback has invalid MerchantTradeNo: " + merchantTradeNo);
        }
        try {
            return Long.parseLong(merchantTradeNo.substring(2));
        } catch (NumberFormatException e) {
            throw new PaymentSignatureException("ECPay MerchantTradeNo not parseable: " + merchantTradeNo, e);
        }
    }

    private String renderAutoSubmitForm(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><body onload=\"document.forms[0].submit()\">");
        sb.append("<form method=\"post\" action=\"").append(escapeHtml(props.getApiUrl())).append("\">");
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append("<input type=\"hidden\" name=\"")
              .append(escapeHtml(e.getKey()))
              .append("\" value=\"")
              .append(escapeHtml(e.getValue()))
              .append("\"/>");
        }
        sb.append("<noscript><button type=\"submit\">Continue to ECPay</button></noscript>");
        sb.append("</form></body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
