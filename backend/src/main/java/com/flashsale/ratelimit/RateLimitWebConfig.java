package com.flashsale.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires {@link RateLimitInterceptor} onto exactly the purchase endpoint —
 * no other route burns rate-limit budget.
 *
 * Separate config class (not folded into CorsConfig) so the limiter scope
 * is searchable and removable without touching CORS.
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitWebConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/drops/*/purchase");
    }
}
