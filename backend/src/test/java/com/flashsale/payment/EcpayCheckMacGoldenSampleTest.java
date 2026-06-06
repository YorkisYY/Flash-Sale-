package com.flashsale.payment;

import com.flashsale.payment.ecpay.EcpayCheckMac;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-sample test for {@link EcpayCheckMac}.
 *
 * <p>The existing {@code EcpayWebhookIdempotencyTest} validates that our
 * compute() is <em>internally consistent</em> — we sign, then we verify,
 * passes by construction even if both sides share the same bug.
 *
 * <p>This test is the missing piece: it pins our compute() against ECPay's
 * own published worked example from
 * {@code developers.ecpay.com.tw} (CheckMacValue generation page) using
 * their current stage merchant key set. If the official inputs no longer
 * produce the official expected hash, EITHER:
 *
 * <ol>
 *   <li>our algorithm regressed (sort order, URL-encoding table, lower/upper
 *       case ordering, charset), or
 *   <li>ECPay published a new sample and we need to refresh this one.
 * </ol>
 *
 * <p>Either case warrants investigation — silent CheckMacValue mismatches
 * surface as ECPay error 10200073 in production-style stage tests, which is
 * how we discovered the gap in the first place.
 *
 * <p>The credentials used here are ECPay's PUBLIC stage test set (HashKey
 * {@code pwFHCqoQZGmho4w6}, HashIV {@code EkRm7iFT261dpevs}) — safe to
 * commit; never real merchant secrets.
 */
class EcpayCheckMacGoldenSampleTest {

    /** Published stage merchant — see EcpayProperties.merchantId default. */
    private static final String HASH_KEY = "pwFHCqoQZGmho4w6";
    private static final String HASH_IV  = "EkRm7iFT261dpevs";

    /**
     * Sample 1 — ECPay's official worked example for AioCheckOut V5 with
     * EncryptType=1 (SHA256).
     *
     * <p>Inputs come straight from the developer-docs page; the expected
     * hash is the one ECPay's own server computes for these inputs (and
     * therefore the value our outbound CheckMacValue must match — that's
     * what the stage server is checking us against).
     */
    @Test
    void matchesEcpayOfficialWorkedExample() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ChoosePayment",     "ALL");
        params.put("EncryptType",       "1");
        params.put("ItemName",          "Apple iphone 15");
        params.put("MerchantID",        "3002607");
        params.put("MerchantTradeDate", "2023/03/12 15:30:23");
        params.put("MerchantTradeNo",   "ecpay20230312153023");
        params.put("PaymentType",       "aio");
        params.put("ReturnURL",         "https://www.ecpay.com.tw/receive.php");
        params.put("TotalAmount",       "30000");
        params.put("TradeDesc",         "促銷方案");

        String actual = EcpayCheckMac.compute(params, HASH_KEY, HASH_IV);

        // Expected value: produced by ECPay's reference server-side
        // implementation against the same inputs + HashKey/HashIV. If this
        // line ever needs to change, you are admitting an algorithm change
        // — verify against the official docs first.
        assertThat(actual)
                .as("CheckMacValue must match ECPay's reference output exactly")
                .isEqualTo("6C51C9E6888DE861FD62FB1DD17029FC742634498FD813DC43D4243B5685B840");
    }

    /**
     * Sample 2 — order of parameter insertion must not matter. Same inputs
     * as sample 1, reverse-order keys; the result must be identical because
     * compute() sorts internally.
     */
    @Test
    void insertionOrderIsIrrelevant() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("ChoosePayment",     "ALL");
        forward.put("EncryptType",       "1");
        forward.put("ItemName",          "Apple iphone 15");
        forward.put("MerchantID",        "3002607");
        forward.put("MerchantTradeDate", "2023/03/12 15:30:23");
        forward.put("MerchantTradeNo",   "ecpay20230312153023");
        forward.put("PaymentType",       "aio");
        forward.put("ReturnURL",         "https://www.ecpay.com.tw/receive.php");
        forward.put("TotalAmount",       "30000");
        forward.put("TradeDesc",         "促銷方案");

        Map<String, String> reversed = new LinkedHashMap<>();
        forward.entrySet().stream()
                .sorted((a, b) -> b.getKey().compareTo(a.getKey()))
                .forEach(e -> reversed.put(e.getKey(), e.getValue()));

        assertThat(EcpayCheckMac.compute(reversed, HASH_KEY, HASH_IV))
                .isEqualTo(EcpayCheckMac.compute(forward, HASH_KEY, HASH_IV));
    }

    /**
     * Sample 3 — the trycloudflare-style ReturnURL with {@code :} and {@code /}
     * is the exact shape that originally landed us on this bug. Pin that
     * such a URL doesn't break compute(). Result should match a recompute
     * with the same inputs (smoke test only — no external golden value).
     */
    @Test
    void returnUrlWithColonAndSlashIsHandled() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID",   "3002607");
        params.put("TotalAmount",  "1000");
        params.put("ReturnURL",    "https://nevertheless-pens-importance-supplement.trycloudflare.com/api/payments/ecpay/callback");
        params.put("EncryptType",  "1");

        String first  = EcpayCheckMac.compute(params, HASH_KEY, HASH_IV);
        String second = EcpayCheckMac.compute(params, HASH_KEY, HASH_IV);
        assertThat(first).isEqualTo(second);
        assertThat(first).matches("^[0-9A-F]{64}$");
    }
}
