package com.flashsale.api;

import com.flashsale.api.dto.ApiDtos.OrderResponse;
import com.flashsale.order.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable Long orderId) {
        return OrderResponse.of(orderService.requireOrder(orderId));
    }

    /**
     * Manual "I shipped it" endpoint — per spec, real logistics is out of scope;
     * the merchant flips this flag by hand.
     */
    @PostMapping("/{orderId}/ship")
    public OrderResponse ship(@PathVariable Long orderId) {
        return OrderResponse.of(orderService.markShipped(orderId));
    }

    @PostMapping("/{orderId}/complete")
    public OrderResponse complete(@PathVariable Long orderId) {
        return OrderResponse.of(orderService.markCompleted(orderId));
    }
}
