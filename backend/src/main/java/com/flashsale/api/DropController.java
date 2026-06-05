package com.flashsale.api;

import com.flashsale.api.dto.ApiDtos.OrderResponse;
import com.flashsale.api.dto.ApiDtos.ProductResponse;
import com.flashsale.api.dto.ApiDtos.PurchaseRequest;
import com.flashsale.api.dto.ApiDtos.PurchaseResponse;
import com.flashsale.domain.Order;
import com.flashsale.domain.Product;
import com.flashsale.order.OrderService;
import com.flashsale.order.dto.CreateOrderCommand;
import com.flashsale.payment.PaymentProvider;
import com.flashsale.payment.PaymentProviderRegistry;
import com.flashsale.payment.PaymentSession;
import com.flashsale.repository.ProductRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
    private final PaymentProviderRegistry providers;

    public DropController(ProductRepository productRepository,
                          OrderService orderService,
                          PaymentProviderRegistry providers) {
        this.productRepository = productRepository;
        this.orderService = orderService;
        this.providers = providers;
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

    @PostMapping("/{productId}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(
            @PathVariable Long productId,
            @RequestBody @Valid PurchaseRequest req) {

        String providerName = (req.provider() == null || req.provider().isBlank())
                ? DEFAULT_PROVIDER
                : req.provider();

        Order order = orderService.createOrder(new CreateOrderCommand(
                productId,
                req.quantity(),
                req.buyerName(),
                req.buyerEmail(),
                req.buyerPhone(),
                req.shippingAddress(),
                providerName
        ));

        // Resolve the provider AFTER the order commits, so a misconfigured
        // gateway can't leave a buyer in a half-state — the order exists and
        // the result page can show "Awaiting payment" until the buyer retries.
        PaymentProvider provider = providers.require(order.getProvider());

        try {
            PaymentSession session = provider.createSession(order);
            return ResponseEntity.ok(new PurchaseResponse(
                    order.getId(), session.redirectUrl(), session.formHtml()));
        } catch (UnsupportedOperationException notImplemented) {
            // PayUni currently throws this; surface the order id so the buyer
            // still sees a result page rather than a hard 500.
            return ResponseEntity.ok(new PurchaseResponse(order.getId(), null, null));
        }
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrderUnderDrops(@PathVariable Long orderId) {
        return OrderResponse.of(orderService.requireOrder(orderId));
    }
}
