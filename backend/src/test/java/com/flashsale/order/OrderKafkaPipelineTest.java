package com.flashsale.order;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.inventory.RedisInventoryService;
import com.flashsale.kafka.KafkaConfig;
import com.flashsale.kafka.OrderRequestedEvent;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the asynchronous order pipeline:
 *
 *   POST /purchase → 202 → Kafka → consumer → DB row exists with CREATED.
 *
 * Plus the supporting contracts: the polling endpoint must return PROCESSING
 * when the row isn't there yet (NOT 404), and a duplicate Kafka event must
 * not produce a second order.
 *
 * Uses real Postgres + Redis + Kafka via Testcontainers. Awaitility polls
 * for the eventual consumer outcome — there is no "wait for consumer
 * deterministically" hook by design (the whole point of the async path is
 * that the API doesn't block on it).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderKafkaPipelineTest {

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

    @Container
    @SuppressWarnings("resource")
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Keep the order expiry job out of the way of these tests
        registry.add("flashsale.order.expiry-scan-interval-ms",   () -> "600000");
        registry.add("flashsale.inventory.reconcile-interval-ms", () -> "600000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired InventoryService inventoryService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired KafkaTemplate<String, OrderRequestedEvent> kafkaTemplate;

    @Test
    void postPurchaseReturns202AndConsumerCreatesOrder() throws Exception {
        Product p = seedProduct(10);
        Long productId = p.getId();
        inventoryService.loadProduct(productId);

        MvcResult result = mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.orderId").exists())
                .andReturn();

        String externalId = JsonPath.read(
                result.getResponse().getContentAsString(), "$.orderId");

        // The consumer is async — Awaitility polls until the order row
        // exists with status CREATED. Up to 30s headroom for cold first
        // partition assignment.
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Order order = orderRepository.findByExternalId(externalId).orElse(null);
                    assertThat(order).as("consumer must persist the order").isNotNull();
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
                });

        // Stock decremented EXACTLY once in BOTH layers — proves the consumer
        // ran the DB conditional UPDATE and the producer's prior Redis DECR.
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB stock decremented exactly once").isEqualTo(9);
        assertThat(redisStock(productId))
                .as("Redis stock decremented exactly once").isEqualTo("9");
    }

    /**
     * Regression gap test.
     *
     * The previous suite asserted "consumer wrote a row keyed by externalId"
     * but NEVER fed that externalId back through {@code GET /api/orders/{id}}.
     * That endpoint declared {@code @PathVariable Long}, so the real frontend
     * call ({@code GET /api/orders/<UUID>}) threw NumberFormatException — and
     * tests stayed green the whole time.
     *
     * This test closes that gap: it takes the EXACT string the 202 returned
     * and round-trips it through the public detail endpoint. Any future
     * regression that reintroduces a Long-typed path variable on the public
     * surface fails here.
     */
    @Test
    void getOrderByExternalIdFrom202_returnsOrderAndIdEqualsExternalId() throws Exception {
        Product p = seedProduct(10);
        Long productId = p.getId();
        inventoryService.loadProduct(productId);

        MvcResult result = mockMvc.perform(post("/api/drops/" + productId + "/purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purchaseJson()))
                .andExpect(status().isAccepted())
                .andReturn();

        String externalId = JsonPath.read(
                result.getResponse().getContentAsString(), "$.orderId");
        assertThat(externalId).as("UUID-style externalId is the public id").doesNotMatch("\\d+");

        // Wait for the consumer to write the row (Awaitility, not Thread.sleep
        // — see other tests in this class for the rationale).
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> mockMvc.perform(get("/api/orders/" + externalId + "/status"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CREATED")));

        // The actual gap: the detail endpoint must accept the UUID string and
        // return the order with id == externalId. A regression to
        // @PathVariable Long would fail this with HTTP 400 / 500.
        mockMvc.perform(get("/api/orders/" + externalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(externalId))
                .andExpect(jsonPath("$.status").value("CREATED"));

        // Same UUID under the legacy /api/drops/orders alias.
        mockMvc.perform(get("/api/drops/orders/" + externalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(externalId));
    }

    @Test
    void statusEndpointReturnsProcessingWhenRowAbsent() throws Exception {
        // Use an arbitrary external id no consumer will ever have written.
        String externalId = "never_written_" + UUID.randomUUID().toString().replace("-", "");

        mockMvc.perform(get("/api/orders/" + externalId + "/status"))
                .andExpect(status().isOk())  // explicitly NOT 404
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void duplicateEventCreatesExactlyOneOrder() throws Exception {
        Product p = seedProduct(10);
        Long productId = p.getId();
        inventoryService.loadProduct(productId);

        String externalId = "idem_" + UUID.randomUUID().toString().replace("-", "");
        OrderRequestedEvent event = new OrderRequestedEvent(
                externalId, productId, 1,
                "Idem Buyer", "idem@example.com", "0900000000", "Idem St", "ECPAY",
                Instant.now()
        );

        // Bypass the API + Redis DECR — publish the SAME event twice
        // directly so the test focuses on the consumer's idempotency.
        kafkaTemplate.send(KafkaConfig.ORDERS_REQUESTED_TOPIC,
                String.valueOf(productId), event);
        kafkaTemplate.send(KafkaConfig.ORDERS_REQUESTED_TOPIC,
                String.valueOf(productId), event);

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Order order = orderRepository.findByExternalId(externalId).orElse(null);
                    assertThat(order).isNotNull();
                });

        // Settle: give the second redelivery a moment to be absorbed (it
        // hits the existsByExternalId guard) before asserting final stock.
        Thread.sleep(500);

        // Exactly ONE row, stock decremented EXACTLY once — the unique
        // constraint absorbed the second event.
        long matching = orderRepository.findAll().stream()
                .filter(o -> externalId.equals(o.getExternalId())).count();
        assertThat(matching).as("exactly one order persisted from a duplicate event").isEqualTo(1);
        assertThat(productRepository.findById(productId).orElseThrow().getAvailableStock())
                .as("DB stock decremented exactly once despite duplicate event").isEqualTo(9);
    }

    private Product seedProduct(int stock) {
        Product p = new Product();
        p.setName("Kafka Pipeline Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(stock);
        p.setAvailableStock(stock);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        return productRepository.saveAndFlush(p);
    }

    private String redisStock(Long productId) {
        return redisTemplate.opsForValue().get(RedisInventoryService.STOCK_KEY_PREFIX + productId);
    }

    private static String purchaseJson() {
        return """
            {"quantity":1,"buyerName":"Buyer","buyerEmail":"buyer@example.com",
             "buyerPhone":"0900000000","shippingAddress":"1 Main St","provider":"ECPAY"}
            """;
    }
}
