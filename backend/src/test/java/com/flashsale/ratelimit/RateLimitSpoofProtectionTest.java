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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security regression guard for the rate limiter.
 *
 * <p>With {@code flashsale.ratelimit.trust-forwarded-header=false} (the
 * production default), an attacker rotating {@code X-Forwarded-For} on
 * every request MUST NOT earn fresh rate-limit buckets — every request
 * still scopes against the real socket peer. Otherwise the limiter is
 * theatre: any client can append {@code X-Forwarded-For: <random>} and
 * spam the purchase endpoint at unlimited rate.
 *
 * <p>This test sends N+1 requests through MockMvc, each with a different
 * spoofed XFF, and asserts the (N+1)th still returns 429 — because all
 * those requests share the same real {@code remoteAddr} (MockMvc's
 * "127.0.0.1").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitSpoofProtectionTest {

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
        registry.add("flashsale.order.expiry-scan-interval-ms",   () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms", () -> "600000");
        registry.add("flashsale.ratelimit.max-attempts",          () -> "3");
        registry.add("flashsale.ratelimit.window-seconds",        () -> "60");
        // The whole point of this class: prove the production default holds.
        registry.add("flashsale.ratelimit.trust-forwarded-header", () -> "false");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;

    @Test
    void spoofedForwardedHeaderDoesNotCreateSeparateBuckets() throws Exception {
        Product p = seedProduct();
        Long productId = p.getId();

        // Three "different" spoofed IPs — all from the same real source
        // (MockMvc's 127.0.0.1). With trust-forwarded-header=false, the
        // limiter must ignore XFF entirely and bucket them all together.
        String[] spoofedIps = {"203.0.113.10", "198.51.100.42", "192.0.2.99"};
        for (String spoofed : spoofedIps) {
            mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                            .header("X-Forwarded-For", spoofed)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(purchaseJson()))
                    .andExpect(status().is(not429()));
        }

        // 4th request with yet another fresh spoof — still 429, because all
        // four share the real socket peer's bucket.
        mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .header("X-Forwarded-For", "100.64.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isTooManyRequests());
    }

    /** Lazy way to assert "any status except 429" via MockMvc's matchers. */
    private static org.hamcrest.Matcher<Integer> not429() {
        return org.hamcrest.Matchers.not(429);
    }

    private Product seedProduct() {
        Product p = new Product();
        p.setName("Spoof Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(100);
        p.setAvailableStock(100);
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
