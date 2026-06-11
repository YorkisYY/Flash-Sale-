package com.flashsale.repository;

import com.flashsale.domain.Order;
import com.flashsale.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByPaymentIdempotencyKey(String key);

    Optional<Order> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    boolean existsByProductId(Long productId);

    @Query("""
        SELECT o FROM Order o
         WHERE o.status = :status
           AND o.expiresAt < :now
        """)
    List<Order> findExpiredOrders(@Param("status") OrderStatus status,
                                  @Param("now") Instant now);

    /**
     * Atomic conditional status transition. Flips an order from {@code from}
     * to {@code to} ONLY if it is currently in {@code from}, and reports how
     * many rows changed.
     *
     * <p>This is the correctness layer that makes stock-release exactly-once
     * even without a distributed lock: the {@code AND o.status = :from} clause
     * row-locks the order and re-checks its state at write time, so two racing
     * workers (multiple pods, READ COMMITTED) cannot both observe CREATED and
     * both release. The loser's UPDATE matches zero rows and it skips the
     * release. ShedLock sits on top purely to avoid the wasted duplicate scan;
     * it is not what prevents the double-release — this guard is.
     *
     * <p>Bulk JPQL update: bypasses the persistence context and the
     * {@code @PreUpdate} callback, so {@code updated_at} is not auto-touched
     * here. The caller does not re-read the entity after calling this, so the
     * now-stale managed copy is never re-flushed.
     */
    @Modifying
    @Query("UPDATE Order o SET o.status = :to WHERE o.id = :id AND o.status = :from")
    int transitionStatus(@Param("id") Long id,
                         @Param("from") OrderStatus from,
                         @Param("to") OrderStatus to);
}
