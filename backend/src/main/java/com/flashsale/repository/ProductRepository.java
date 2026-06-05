package com.flashsale.repository;

import com.flashsale.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Atomic conditional stock decrement. This is the single core of the
     * "no oversell" guarantee — see DatabaseInventoryService for the
     * concurrency tradeoff discussion.
     *
     * Returns rows affected: 1 = reservation succeeded, 0 = sold out.
     */
    @Modifying
    @Query(value = """
        UPDATE product
           SET available_stock = available_stock - :qty,
               updated_at      = now()
         WHERE id = :id
           AND available_stock >= :qty
        """, nativeQuery = true)
    int decrementStock(@Param("id") Long productId, @Param("qty") int qty);

    /**
     * Return reserved stock back to the pool (on order EXPIRED or CANCELLED).
     */
    @Modifying
    @Query(value = """
        UPDATE product
           SET available_stock = available_stock + :qty,
               updated_at      = now()
         WHERE id = :id
        """, nativeQuery = true)
    int incrementStock(@Param("id") Long productId, @Param("qty") int qty);
}
