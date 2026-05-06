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
}
