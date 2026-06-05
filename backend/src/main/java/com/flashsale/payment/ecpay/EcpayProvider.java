package com.flashsale.payment.ecpay;

import com.flashsale.domain.Order;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentResult;
import com.flashsale.payment.PaymentSession;
import com.flashsale.payment.PaymentSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private final EcpayProperties props;

    public EcpayProvider(EcpayProperties props) {
        this.props = props;
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
        params.put("ItemName", "Flash Sale Order #" + order.getId());
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

        String rtnCode = payload.get("RtnCode");
        boolean success = "1".equals(rtnCode);

        String tradeNo = payload.get("TradeNo");
        String merchantTradeNo = payload.get("MerchantTradeNo");
        Long orderId = parseOrderIdFromMerchantTradeNo(merchantTradeNo);

        log.info("ECPay callback verified: order={} tradeNo={} rtnCode={}",
                orderId, tradeNo, rtnCode);
        return new PaymentResult(success, orderId, tradeNo, ACK_OK);
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
