package com.flashsale.ratelimit;

import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rate-limit interceptor proves three properties under real Redis:
 *
 *   1. Within the same (clientIp, productId) scope, request N+1 returns 429
 *      with the documented JSON body and a Retry-After header.
 *   2. Different client IPs each get their own budget — one bot's spam
 *      doesn't lock out everyone else's buying attempts.
 *   3. Under burst concurrency, EXACTLY max-attempts pass — the Lua
 *      INCR+EXPIRE atomicity holds and there's no race where two requests
 *      both see "count=1" and both EXPIRE the key (or worse, neither does).
 *
 * Tests run with max-attempts=3 / window=60s to keep the loop bodies short.
 * The third test (concurrency) reaches under the interceptor to call the
 * service directly — atomicity is a property of the Lua script, not of the
 * HTTP layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitInterceptorTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        // Push schedulers out of the way of the test window
        registry.add("flashsale.order.expiry-scan-interval-ms",   () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms", () -> "600000");
        // Tight limit so the test loops are short
        registry.add("flashsale.ratelimit.max-attempts",          () -> "3");
        registry.add("flashsale.ratelimit.window-seconds",        () -> "60");
        // These tests exercise per-IP scoping via X-Forwarded-For — opt in to
        // the trust-XFF mode (same flag a load-test profile would set). The
        // adversarial case where the flag is OFF lives in
        // RateLimitSpoofProtectionTest.
        registry.add("flashsale.ratelimit.trust-forwarded-header", () -> "true");
        // Rate-limit tests don't need Kafka — opt out cleanly.
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired RateLimitService rateLimitService;

    @Test
    void overLimitReturns429WithJsonBodyAndRetryAfter() throws Exception {
        Product p = seedProduct(100);
        Long productId = p.getId();
        String ip = "203.0.113.10"; // TEST-NET-3 — safe to use in test data

        // First N requests pass through the limiter. They reach the
        // controller and may 200 or 409 — either is "not 429".
        for (int i = 0; i < 3; i++) {
            int status = mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(purchaseJson()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("request %d passes the limiter", i + 1).isNotEqualTo(429);
        }

        // (N+1)th request blocked at the limiter — never reaches controller.
        mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    void differentIpsHaveIndependentBudgets() throws Exception {
        Product p = seedProduct(100);
        Long productId = p.getId();

        // Bot from one IP burns its whole budget
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                            .header("X-Forwarded-For", "198.51.100.1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(purchaseJson()))
                    .andExpect(result ->
                            assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
        }

        // Same IP next request: blocked
        mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isTooManyRequests());

        // Different IP, fresh budget — must NOT be blocked.
        mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .header("X-Forwarded-For", "198.51.100.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    /**
     * Atomicity of the Lua INCR+EXPIRE pair. Fire 50 parallel calls into
     * the same scope; with the limiter set to 3, EXACTLY 3 must return
     * ALLOWED. Any other count would mean the Lua script's "INCR; if n==1
     * then EXPIRE end" isn't fencing concurrent increments.
     */
    @Test
    void parallelCallsRespectMaxAttempts() throws Exception {
        final int parallel = 50;
        final int maxAttempts = 3;
        final String scope = "concurrency-test-scope-" + System.nanoTime();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(parallel);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();

        for (int i = 0; i < parallel; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (rateLimitService.checkAndCount(scope) == RateLimitService.ALLOWED) {
                        allowed.incrementAndGet();
                    } else {
                        blocked.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(allowed.get())
                .as("exactly max-attempts pass the limiter under burst concurrency")
                .isEqualTo(maxAttempts);
        assertThat(blocked.get())
                .as("remainder are throttled — proves Lua INCR atomicity")
                .isEqualTo(parallel - maxAttempts);
    }

    private Product seedProduct(int stock) {
        Product p = new Product();
        p.setName("Rate Limit Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return productRepository.saveAndFlush(p);
    }

    private static String purchaseJson() {
        return """
            {"quantity":1,"buyerName":"Buyer","buyerEmail":"buyer@example.com",
             "buyerPhone":"0900000000","shippingAddress":"1 Main St","provider":"ECPAY"}
            """;
    }
}
