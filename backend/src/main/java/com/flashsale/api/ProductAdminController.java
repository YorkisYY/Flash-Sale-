package com.flashsale.api;

import com.flashsale.api.dto.ApiDtos.CreateProductRequest;
import com.flashsale.api.dto.ApiDtos.ProductResponse;
import com.flashsale.domain.Product;
import com.flashsale.domain.ProductStatus;
import com.flashsale.inventory.InventoryService;
import com.flashsale.repository.OrderRepository;
import com.flashsale.repository.ProductRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Internal product-seeding endpoint. Spec section 1: there is no merchant
 * dashboard; products are created via this single internal endpoint.
 *
 * NOT auth-protected in v1 — the assumption is this endpoint is only reachable
 * by the merchant (you) on a private network. If you ever expose this app
 * publicly, put a reverse-proxy auth check in front of /api/internal/*.
 */
@RestController
@RequestMapping("/api/internal/products")
public class ProductAdminController {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    public ProductAdminController(ProductRepository productRepository,
                                  OrderRepository orderRepository,
                                  InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestBody @Valid CreateProductRequest req) {
        Product p = new Product();
        p.setName(req.name());
        p.setPrice(req.price());
        p.setTotalStock(req.totalStock());
        p.setAvailableStock(req.totalStock());
        p.setDropStartsAt(req.dropStartsAt());
        p.setStatus(ProductStatus.ACTIVE);
        Product saved = productRepository.save(p);
        // Push initial stock into Redis (when Redis layer is active).
        // No-op for DB-only deployments.
        inventoryService.loadProduct(saved.getId());
        return ResponseEntity.ok(ProductResponse.of(saved));
    }

    /**
     * Delete a product. If any order references it (PAID or otherwise), we
     * can't hard-delete without violating the FK and breaking payment_event
     * history, so we soft-archive instead. listDrops filters ARCHIVED out, so
     * either path makes the product disappear from the buyer-facing list.
     */
    @DeleteMapping("/{productId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long productId) {
        Product p = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "product not found"));

        if (orderRepository.existsByProductId(productId)) {
            p.setStatus(ProductStatus.ARCHIVED);
            return ResponseEntity.ok(Map.of("id", productId, "mode", "archived"));
        }
        productRepository.delete(p);
        return ResponseEntity.ok(Map.of("id", productId, "mode", "deleted"));
    }
}
