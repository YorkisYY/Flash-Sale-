package com.flashsale.actuator;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the Kubernetes probe behaviour wired in {@code application.yml}:
 *
 * <ol>
 *   <li>Healthy → {@code /actuator/health/liveness} and {@code /readiness}
 *       both return 200.</li>
 *   <li>Redis DOWN but DB up → readiness stays 200. Redis is non-critical
 *       (graceful degradation), so it must NOT be in the readiness group and
 *       must not pull the pod out of rotation.</li>
 *   <li>DB unreachable → readiness returns 503 (pod must stop receiving
 *       traffic), while liveness stays 200 (we must NOT let Kubernetes restart
 *       the pod over an external DB outage).</li>
 * </ol>
 *
 * Methods are ordered because they progressively tear down dependencies
 * (stop Redis, then stop Postgres) on the shared static containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ActuatorHealthProbesTest {

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
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        // Keep the schedulers quiet; this test only cares about probes.
        registry.add("flashsale.order.expiry-scan-interval-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms",      () -> "600000");
        registry.add("flashsale.order.expiry-initial-delay-ms",        () -> "600000");
        registry.add("flashsale.inventory.reconcile-initial-delay-ms", () -> "600000");
        // Fail fast on a dead DB so the readiness probe flips DOWN promptly.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
    }

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    private HttpStatusCode statusOf(String path) {
        return rest.getForEntity("http://localhost:" + port + path, String.class)
                .getStatusCode();
    }

    @Test
    @Order(1)
    void livenessAndReadinessAreUpWhenHealthy() {
        assertThat(statusOf("/actuator/health/liveness"))
                .as("liveness 200 when healthy").isEqualTo(HttpStatus.OK);
        assertThat(statusOf("/actuator/health/readiness"))
                .as("readiness 200 when healthy").isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(2)
    void readinessStaysUpWhenRedisIsDown() {
        redis.stop();
        // Readiness must remain UP — Redis is not in the readiness group, so a
        // Redis outage leaves the pod in rotation (degraded mode). Asserted
        // over a short window to be sure it doesn't transiently flip.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(statusOf("/actuator/health/readiness"))
                        .as("Redis down must NOT fail readiness (graceful degradation)")
                        .isEqualTo(HttpStatus.OK));
    }

    @Test
    @Order(3)
    void readinessGoesDownWhenDbIsUnreachable() {
        postgres.stop();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(statusOf("/actuator/health/readiness"))
                        .as("DB unreachable must fail readiness → pod leaves Service endpoints")
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(statusOf("/actuator/health/liveness"))
                .as("liveness must STAY up during a DB outage — no restart storm")
                .isEqualTo(HttpStatus.OK);
    }
}
