package com.flashsale.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-handler limiter on the purchase endpoint only.
 *
 * Scope = "{clientIp}:{productId}" — different products burn separate
 * budgets so a bot spamming product A can't also lock the buyer out of
 * product B, and different buyers each get their own bucket.
 *
 * Over limit → HTTP 429 with a JSON body
 * {@code { "error": "rate_limited", "retryAfterSeconds": N }} and a
 * {@code Retry-After} header. Below limit → returns true and the request
 * proceeds to the controller (which then does the real stock check).
 *
 * Behind a proxy, set {@code X-Forwarded-For} so the limiter keys on the
 * real client; without that header, {@link HttpServletRequest#getRemoteAddr}
 * is used. (k6 sets a unique XFF per VU during load tests — see
 * loadtest/flash-sale.js.)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final Pattern PURCHASE_PATH = Pattern.compile("^/api/drops/(\\d+)/purchase$");

    private final RateLimitService rateLimitService;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                RateLimitProperties props,
                                ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        Matcher matcher = PURCHASE_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            // Defensive: the WebMvcConfig only registers this interceptor on
            // /api/drops/*/purchase, so this branch shouldn't fire. Belt-and-
            // braces in case someone misconfigures the registration.
            return true;
        }
        String productId = matcher.group(1);
        String clientIp = resolveClientIp(request);
        String scope = clientIp + ":" + productId;

        int retryAfter = rateLimitService.checkAndCount(scope);
        if (retryAfter == RateLimitService.ALLOWED) {
            return true;
        }

        log.warn("Rate-limited: scope={} retryAfter={}s", scope, retryAfter);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "rate_limited",
                "retryAfterSeconds", retryAfter
        ));
        return false;
    }

    /**
     * Resolve the client IP used as part of the rate-limit scope.
     *
     * <p>Default (and production-correct) behaviour: ignore any
     * {@code X-Forwarded-For} header — return {@code request.getRemoteAddr()}.
     * Otherwise any client could send {@code X-Forwarded-For: <random>} on
     * every request and earn a brand-new bucket each time, completely
     * bypassing the limiter.
     *
     * <p>Only when {@link RateLimitProperties#isTrustForwardedHeader()} is
     * explicitly enabled — meaning the operator has confirmed a reverse
     * proxy in front OVERWRITES the header rather than appending to it — do
     * we honor XFF. Use that mode for k6 / load-test profiles and never in
     * a production deployment that doesn't strip the header at the edge.
     */
    private String resolveClientIp(HttpServletRequest req) {
        if (props.isTrustForwardedHeader()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma < 0 ? xff : xff.substring(0, comma)).trim();
            }
        }
        return req.getRemoteAddr();
    }
}
