package com.flashsale.repository;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPaymentIdempotencyKey(String key);

    boolean existsByProductId(Long productId);

    @Query("""
        SELECT o FROM Order o
         WHERE o.status = :status
           AND o.expiresAt < :now
        """)
    List<Order> findExpiredOrders(@Param("status") OrderStatus status,
                                  @Param("now") Instant now);
}
