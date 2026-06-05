package com.flashsale.payment;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.payment.ecpay.EcpayCheckMac;
import com.flashsale.payment.ecpay.EcpayProvider;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.PaymentEventRepository;
import com.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ECPay ReturnURL webhook idempotency. Sends a form-encoded payload with a
 * correctly-computed CheckMacValue twice; asserts:
 *   - first call: response body == "1|OK", order → PAID, one payment_event row.
 *   - second call: response still "1|OK" (otherwise ECPay would retry forever),
 *                  order stays PAID, no extra payment_event row.
 *
 * Bad CheckMacValue path is asserted separately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EcpayWebhookIdempotencyTest {

    // ECPay's published test merchant. Same values appear in .env.example.
    private static final String MERCHANT_ID = "2000132";
    private static final String HASH_KEY    = "pwFHCqoQZGmoCv08Q3XY";
    private static final String HASH_IV     = "EkRm7iFT760PKIKAKExC";

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flashsale_test")
            .withUsername("flashsale")
            .withPassword("flashsale");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
        registry.add("flashsale.payment.ecpay.merchant-id",      () -> MERCHANT_ID);
        registry.add("flashsale.payment.ecpay.hash-key",         () -> HASH_KEY);
        registry.add("flashsale.payment.ecpay.hash-iv",          () -> HASH_IV);
        registry.add("flashsale.payment.ecpay.api-url",          () -> "https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5");
        registry.add("flashsale.payment.ecpay.return-url",       () -> "http://localhost:8080/api/payments/ecpay/callback");
        registry.add("flashsale.payment.ecpay.order-result-url", () -> "http://localhost:3000/result");
        registry.add("flashsale.payment.ecpay.client-back-url",  () -> "http://localhost:3000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentEventRepository paymentEventRepository;
    @SpyBean OrderService orderService;

    @Test
    void duplicateEcpayCallbackTransitionsOrderOnce() throws Exception {
        Order order = seedOrder("ECPAY");
        String tradeNo = "EC2026010100000001";
        MultiValueMap<String, String> form = buildSignedReturnUrlPayload(order.getId(), tradeNo);

        long eventsBefore = paymentEventRepository.count();

        // 1st call — must yield exactly "1|OK"
        MvcResult first = mockMvc.perform(post("/api/payments/ecpay/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(form))
                .andExpect(status().isOk())
                .andExpect(content().string(EcpayProvider.ACK_OK))
                .andReturn();

        Order paid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.getProviderRef()).isEqualTo(tradeNo);
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore + 1);
        assertThat(first.getResponse().getContentAsString()).isEqualTo(EcpayProvider.ACK_OK);

        // 2nd call (duplicate) — must still yield "1|OK", and not double-process
        mockMvc.perform(post("/api/payments/ecpay/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(form))
                .andExpect(status().isOk())
                .andExpect(content().string(EcpayProvider.ACK_OK));

        Order stillPaid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(stillPaid.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore + 1);
    }

    /**
     * Tx rollback proof: when markPaid throws (the case the user called out),
     * the PaymentEvent row MUST NOT remain. Otherwise ECPay's next retry of
     * the same TradeNo hits the unique constraint, gets silently absorbed as
     * a "duplicate," and the order sits in CREATED forever.
     *
     * We force markPaid to throw via @SpyBean — the existsById pre-check
     * passes (real order), saveAndFlush inserts the dedup row, markPaid then
     * throws → whole tx rolls back → dedup row gone, order untouched, ECPay
     * retries against a clean slate.
     */
    @Test
    void markPaidFailureRollsBackPaymentEvent() throws Exception {
        Order order = seedOrder("ECPAY");
        String tradeNo = "EC_ROLLBACK_TEST_01";

        doThrow(new RuntimeException("simulated markPaid failure"))
                .when(orderService).markPaid(eq(order.getId()), anyString());

        MultiValueMap<String, String> form = buildSignedReturnUrlPayload(order.getId(), tradeNo);
        long eventsBefore = paymentEventRepository.count();

        // markPaid throws → tx rolls back → controller's catch is for
        // DataIntegrityViolationException only, so this propagates → non-"1|OK"
        // response, ECPay will retry.
        mockMvc.perform(post("/api/payments/ecpay/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(form))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

        // The crucial assertion: dedup row must NOT have committed. Otherwise
        // ECPay's next retry would be silently swallowed and the order
        // stranded.
        assertThat(paymentEventRepository.findByProviderAndProviderTxnId("ECPAY", tradeNo))
                .as("PaymentEvent must NOT persist when markPaid throws — tx must roll back")
                .isEmpty();
        assertThat(paymentEventRepository.count()).isEqualTo(eventsBefore);

        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        Order order = seedOrder("ECPAY");
        MultiValueMap<String, String> form = buildSignedReturnUrlPayload(order.getId(), "EC_TAMPER_0001");

        // Mutate one field after CheckMacValue has been computed.
        form.set("TradeAmt", "9999");

        mockMvc.perform(post("/api/payments/ecpay/callback")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .params(form))
                .andExpect(status().isBadRequest());

        Order untouched = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    private Order seedOrder(String provider) {
        Product p = new Product();
        p.setName("ECPay Test");
        p.setPrice(new BigDecimal("1000.00"));
        p.setTotalStock(10);
        p.setAvailableStock(10);
        p.setDropStartsAt(Instant.now().minusSeconds(60));
        p.setStatus(ProductStatus.ACTIVE);
        p = productRepository.saveAndFlush(p);

        return orderService.createOrder(new CreateOrderCommand(
                p.getId(), 1,
                "ECPay Buyer", "ecpay@example.com", "0922222222", "ECPay Address",
                provider));
    }

    /**
     * Build a form payload as ECPay would send it back on a successful
     * payment, with a CheckMacValue computed off the test HashKey/IV.
     */
    private static MultiValueMap<String, String> buildSignedReturnUrlPayload(Long orderId, String tradeNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MerchantID",      MERCHANT_ID);
        params.put("MerchantTradeNo", "FS" + String.format("%018d", orderId));
        params.put("RtnCode",         "1");
        params.put("RtnMsg",          "Trade Succeeded");
        params.put("TradeNo",         tradeNo);
        params.put("TradeAmt",        "1000");
        params.put("PaymentDate",     "2026/06/05 10:30:00");
        params.put("PaymentType",     "Credit_CreditCard");
        params.put("PaymentTypeChargeFee", "0");
        params.put("TradeDate",       "2026/06/05 10:29:00");
        params.put("SimulatePaid",    "0");

        String checkMac = EcpayCheckMac.compute(params, HASH_KEY, HASH_IV);
        params.put("CheckMacValue", checkMac);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return form;
    }
}
