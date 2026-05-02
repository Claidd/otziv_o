package com.hunt.otziv.p_products.services.service;

import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Product;

import java.util.List;

public interface ProductService {

    List<Product> findAll(); // взять все продукты

    Product findById(Long id); // взять продукт по id

    boolean save(ProductDTO productDTO);
    boolean update(ProductDTO productDTO);

    boolean delete(ProductDTO productDTO);

}
