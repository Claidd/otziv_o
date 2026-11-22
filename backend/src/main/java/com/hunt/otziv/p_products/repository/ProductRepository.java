package com.hunt.otziv.p_products.repository;

import com.hunt.otziv.p_products.model.Product;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends CrudRepository<Product, Long> {
    @Override
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.productCategory")
    List<Product> findAll();

    @Override
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.productCategory WHERE p.id = :id")
    Optional<Product> findById(@Param("id") Long id);

    /**
     * Rows that would be damaged by deleting the product: order_details.order_detail_product is
     * ON DELETE CASCADE (order lines vanish) and reviews.review_product is SET NULL. Archived rows
     * keep the id without a foreign key, so an archived order could no longer be restored.
     */
    @Query(value = """
            SELECT
                (SELECT COUNT(*) FROM order_details od WHERE od.order_detail_product = :productId)
              + (SELECT COUNT(*) FROM reviews r WHERE r.review_product = :productId)
              + (SELECT COUNT(*)
                   FROM archive_order_details aod
                   JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                  WHERE aod.order_detail_product = :productId
                    AND ao.restored_at IS NULL)
              + (SELECT COUNT(*)
                   FROM archive_reviews ar
                   JOIN archive_order_details aod ON aod.order_detail_id = ar.review_order_details
                   JOIN archive_orders ao ON ao.order_id = aod.order_detail_order
                  WHERE ar.review_product = :productId
                    AND ao.restored_at IS NULL)
            """, nativeQuery = true)
    long countOrderUsagesByProductId(@Param("productId") Long productId);
}
