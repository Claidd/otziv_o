package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.ProductService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    private String products(Model model){
        model.addAttribute("products", productService.findAll());
        for (Product product : productService.findAll()) {
            System.out.println(product);
        }
        return "products/products_list.html";
    }
}
