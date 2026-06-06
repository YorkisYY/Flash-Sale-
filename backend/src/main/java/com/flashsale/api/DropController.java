package com.flashsale.api;

import com.flashsale.api.dto.ApiDtos.OrderResponse;
import com.flashsale.api.dto.ApiDtos.ProductResponse;
import com.flashsale.api.dto.ApiDtos.PurchaseRequest;
import com.flashsale.domain.Product;
import com.flashsale.inventory.InventoryService;
import com.flashsale.order.DropNotOpenException;
import com.flashsale.order.OrderIngestionService;
import com.flashsale.order.OrderService;
import com.flashsale.order.SoldOutException;
import com.flashsale.repository.ProductRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Public drop endpoints — drop info and the hot-path purchase endpoint.
 *
 * Provider selection: the buyer's choice (or default STRIPE) is persisted on
 * {@code Order.provider}; we then resolve the concrete implementation from
 * the registry by that name. Adding a new gateway is a single bean + a new
 * value the frontend can send.
 */
@RestController
@RequestMapping("/api/drops")
public class DropController {

    private static final String DEFAULT_PROVIDER = "STRIPE";

    private final ProductRepository productRepository;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final OrderIngestionService ingestionService;

    public DropController(ProductRepository productRepository,
                          OrderService orderService,
                          InventoryService inventoryService,
                          OrderIngestionService ingestionService) {
        this.productRepository = productRepository;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.ingestionService = ingestionService;
    }

    /**
     * Public listing of every drop (any status). The home page uses this so
     * buyers can browse without knowing product ids up front.
     *
     * No pagination — flash-sale apps deliberately have a small product set.
     * If that ever stops being true, add a Pageable here.
     */
    @GetMapping
    public List<ProductResponse> listDrops() {
        return productRepository.findAll().stream()
                .filter(p -> p.getStatus() != com.flashsale.domain.ProductStatus.ARCHIVED)
                .map(ProductResponse::of)
                .toList();
    }

    @GetMapping("/{productId}")
    public ProductResponse getDrop(@PathVariable Long productId) {
        Product p = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "product not found"));
        return ProductResponse.of(p);
    }

    /**
     * Hot path, async edition.
     *
     * <p>1. Rate-limit interceptor (registered separately) — rejected requests
     *    never reach this method.
     * <p>2. Validate product exists + drop is open.
     * <p>3. Redis Lua DECR (no DB write yet). Sold out at this layer → 409.
     * <p>4. Publish OrderRequestedEvent (partition key = productId for
     *    per-product ordering).
     * <p>5. Return 202 with the pre-generated externalId. Client polls
     *    {@code GET /api/orders/{externalId}/status} until it leaves PROCESSING.
     *
     * <p>If the Kafka publish fails (broker unreachable inside the publish
     * timeout), we compensate the Redis DECR before throwing — otherwise the
     * buyer pool would leak one unit per failed publish.
     */
    @PostMapping("/{productId}/purchase")
    public ResponseEntity<Map<String, Object>> purchase(
            @PathVariable Long productId,
            @RequestBody @Valid PurchaseRequest req) {

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + productId));
        if (Instant.now().isBefore(product.getDropStartsAt())) {
            throw new DropNotOpenException(product.getDropStartsAt());
        }

        String providerName = (req.provider() == null || req.provider().isBlank())
                ? DEFAULT_PROVIDER
                : req.provider().toUpperCase();
        int quantity = req.quantity();

        // Redis Lua DECR — the peak-shave layer. Most sold-out requests die here.
        if (!inventoryService.tryReserveRedisOnly(productId, quantity)) {
            throw new SoldOutException(productId);
        }

        // Publish to Kafka. On failure, refund Redis so the buyer pool isn't
        // permanently leaked.
        String externalId;
        try {
            externalId = ingestionService.publishRequest(
                    productId, quantity,
                    req.buyerName(), req.buyerEmail(), req.buyerPhone(),
                    req.shippingAddress(), providerName);
        } catch (RuntimeException publishFailed) {
            inventoryService.releaseRedisOnly(productId, quantity);
            throw publishFailed;
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "orderId", externalId,
                "status", "PROCESSING"
        ));
    }

    @GetMapping("/orders/{externalId}")
    public OrderResponse getOrderUnderDrops(@PathVariable String externalId) {
        return OrderResponse.of(orderService.requireOrderByExternalId(externalId));
    }
}
