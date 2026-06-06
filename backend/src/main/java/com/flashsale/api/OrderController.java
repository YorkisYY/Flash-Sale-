package com.flashsale.api;

import com.flashsale.api.dto.ApiDtos.OrderResponse;
import com.flashsale.domain.Order;
import com.flashsale.order.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public order endpoints. Path id is the UUID externalId — never the
 * internal Long DB id. This is what the result page polls after the 202
 * from /purchase.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{externalId}")
    public OrderResponse get(@PathVariable String externalId) {
        return OrderResponse.of(orderService.requireOrderByExternalId(externalId));
    }

    /**
     * Manual "I shipped it" endpoint — per spec, real logistics is out of scope;
     * the merchant flips this flag by hand.
     */
    @PostMapping("/{externalId}/ship")
    public OrderResponse ship(@PathVariable String externalId) {
        Order o = orderService.requireOrderByExternalId(externalId);
        return OrderResponse.of(orderService.markShipped(o.getId()));
    }

    @PostMapping("/{externalId}/complete")
    public OrderResponse complete(@PathVariable String externalId) {
        Order o = orderService.requireOrderByExternalId(externalId);
        return OrderResponse.of(orderService.markCompleted(o.getId()));
    }
}
