package com.flashsale.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-buyer rate limit on the purchase endpoint.
 *
 * Defaults: 10 attempts per 60-second window per (client IP, productId).
 * Both knobs are env-overridable so load tests / ops can bump them without
 * a code change — see README "Rate limiting".
 */
@ConfigurationProperties(prefix = "flashsale.ratelimit")
public class RateLimitProperties {

    /** Window length in seconds for the fixed-window counter. */
    private int windowSeconds = 60;

    /** Max attempts allowed within one window before the limiter blocks. */
    private int maxAttempts = 10;

    /**
     * When true, derive the rate-limit client IP from {@code X-Forwarded-For}
     * (leftmost entry) if the header is present. When false (the secure
     * default), the IP is always taken from {@code HttpServletRequest.getRemoteAddr}
     * regardless of any header the client sent.
     *
     * Setting this to true UNCONDITIONALLY is a privilege escalation: any
     * client can send {@code X-Forwarded-For: 9.9.9.9} and burn a fresh
     * bucket per arbitrary value, defeating the limit entirely. Only enable
     * it when the backend sits behind a reverse proxy that you control AND
     * that overwrites the header — not appends to it.
     *
     * Production policy: {@code false}. Dev / load-test profiles: {@code true}
     * is acceptable on isolated networks. See README "Rate limiting" for the
     * Nginx config snippet.
     */
    private boolean trustForwardedHeader = false;

    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public boolean isTrustForwardedHeader() { return trustForwardedHeader; }
    public void setTrustForwardedHeader(boolean trustForwardedHeader) { this.trustForwardedHeader = trustForwardedHeader; }
}
