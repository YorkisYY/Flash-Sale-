package com.flashsale.payment.ecpay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * CheckMacValue computation for ECPay AioCheckOut V5.
 *
 * Algorithm (EncryptType=1, SHA256), per the official ECPay docs and the
 * reference Java SDK on github.com/ECPay:
 *
 *   1. Sort params by key, A→Z, case-insensitive.
 *   2. Build: "HashKey={hashKey}&k1=v1&k2=v2&...&HashIV={hashIv}"
 *   3. URL-encode using .NET HttpUtility.UrlEncode rules
 *      (lowercase percent-encoding; restore a small set of "safe" punctuation).
 *   4. Lowercase the encoded string.
 *   5. SHA-256, hex-encoded.
 *   6. Uppercase the hex digest.
 *
 * The lowercase/uppercase dance is the most common source of mismatches. The
 * reference implementations all follow this exact pattern; the unit-style
 * test in EcpayWebhookIdempotencyTest pins it.
 */
public final class EcpayCheckMac {

    private static final Logger log = LoggerFactory.getLogger(EcpayCheckMac.class);

    private EcpayCheckMac() {}

    public static String compute(Map<String, String> params, String hashKey, String hashIv) {
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(params);

        StringBuilder raw = new StringBuilder();
        raw.append("HashKey=").append(hashKey);
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            raw.append('&').append(e.getKey()).append('=').append(e.getValue());
        }
        raw.append("&HashIV=").append(hashIv);

        String encoded = ecpayUrlEncode(raw.toString()).toLowerCase();
        // The pre-hash string contains HashKey AND HashIV in the clear, so
        // it must NEVER be logged above DEBUG. Production runs at INFO; this
        // log line is a no-op there. Only enable
        // `logging.level.com.flashsale.payment.ecpay=DEBUG` locally when
        // debugging a CheckMacValue mismatch against the stage server.
        if (log.isDebugEnabled()) {
            log.debug("ECPay CheckMacValue pre-hash string: {}", encoded);
        }
        return sha256Hex(encoded).toUpperCase();
    }

    /**
     * Restores a small set of punctuation back to its literal form, mirroring
     * .NET's {@code HttpUtility.UrlEncode}. Without this, the hash won't match
     * ECPay's side.
     */
    static String ecpayUrlEncode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded
                .replace("%21", "!")
                .replace("%2A", "*")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%2D", "-")
                .replace("%2E", ".")
                .replace("%5F", "_");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
