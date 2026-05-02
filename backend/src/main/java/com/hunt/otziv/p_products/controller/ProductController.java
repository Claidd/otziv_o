package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.c_categories.services.ProductCategoryService;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final ProductCategoryService productCategoryService;

    @GetMapping
    private String products(Model model){
        model.addAttribute("products", productService.findAll());
        for (Product product : productService.findAll()) {
            System.out.println(product);
        }
        return "products/products_list.html";
    }

    @GetMapping("/products")
    private String getProducts(Model model){
        model.addAttribute("products", productService.findAll());
        for (Product product : productService.findAll()) {
            System.out.println(product);
        }
        return "products/products.html";
    }

    @GetMapping("/edit/{productId}")
    private String EditProducts(Model model,@PathVariable Long productId){
        model.addAttribute("editProductDto", productService.findById(productId));
        model.addAttribute("productsCategory", productCategoryService.productCategoryDTOList());
        return "products/edit_product.html";
    }



    @PostMapping("/edit/{productId}")
    private String UpdateProducts(@ModelAttribute("editProductDto") ProductDTO editProductDto, Model model, RedirectAttributes rm, @PathVariable Long productId){
        if (productService.update(editProductDto)){
            rm.addFlashAttribute("saveSuccess", "true");
        }
        return "redirect:/products/edit/{productId}";
    }

    @PostMapping("/edit/delete/{productId}")
    private String DeleteProducts(@ModelAttribute("editProductDto") ProductDTO editProductDto, Model model, RedirectAttributes rm, @PathVariable Long productId){
        System.out.println(editProductDto);
        if (productService.delete(editProductDto)){
            rm.addFlashAttribute("saveSuccess", "true");
        }
        return "redirect:/products/products";
    }


    @GetMapping("/add")
    private String AddProducts(Model model){
        model.addAttribute("newProductDto", new ProductDTO());
        model.addAttribute("productsCategory", productCategoryService.productCategoryDTOList());
        return "products/add_product.html";
    }

    @PostMapping("/add")
    private String AddPostProducts(@ModelAttribute("newProductDto") ProductDTO newProductDto, Model model, RedirectAttributes rm){
        System.out.println(newProductDto);
        if (productService.save(newProductDto)){
            rm.addFlashAttribute("saveSuccess", "true");
        }
        return "redirect:/products/products";
    }
}
