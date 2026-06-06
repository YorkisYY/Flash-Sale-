package com.flashsale.api.dto;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/** Request and response DTOs for the public API. */
public final class ApiDtos {

    private ApiDtos() {}

    public record CreateProductRequest(
            @NotBlank String name,
            @NotNull @Positive BigDecimal price,
            @NotNull @Min(1) Integer totalStock,
            @NotNull Instant dropStartsAt
    ) {}

    public record ProductResponse(
            Long id,
            String name,
            BigDecimal price,
            int totalStock,
            int availableStock,
            Instant dropStartsAt,
            ProductStatus status
    ) {
        public static ProductResponse of(Product p) {
            return new ProductResponse(
                    p.getId(), p.getName(), p.getPrice(),
                    p.getTotalStock(), p.getAvailableStock(),
                    p.getDropStartsAt(), p.getStatus()
            );
        }
    }

    public record PurchaseRequest(
            @NotNull @Min(1) Integer quantity,
            @NotBlank String buyerName,
            @NotBlank @Email String buyerEmail,
            @NotBlank String buyerPhone,
            @NotBlank String shippingAddress,
            /** Provider name: STRIPE, ECPAY, or PAYUNI. Defaults to STRIPE if blank. */
            String provider
    ) {}

    public record PurchaseResponse(
            Long orderId,
            String redirectUrl,
            String formHtml
    ) {}

    /**
     * Public order representation. {@code id} is the UUID externalId — the
     * internal Long DB id is intentionally NOT exposed. The result page polls
     * by this id; any leak of the internal Long would re-introduce the
     * {@code Long.parseLong("uuid")} NumberFormatException regression.
     */
    public record OrderResponse(
            String id,
            Long productId,
            int quantity,
            String buyerName,
            String buyerEmail,
            String buyerPhone,
            String shippingAddress,
            BigDecimal amount,
            OrderStatus status,
            String provider,
            String providerRef,
            Instant createdAt,
            Instant expiresAt
    ) {
        public static OrderResponse of(Order o) {
            return new OrderResponse(
                    o.getExternalId(), o.getProductId(), o.getQuantity(),
                    o.getBuyerName(), o.getBuyerEmail(), o.getBuyerPhone(),
                    o.getShippingAddress(), o.getAmount(),
                    o.getStatus(), o.getProvider(), o.getProviderRef(),
                    o.getCreatedAt(), o.getExpiresAt()
            );
        }
    }
}
