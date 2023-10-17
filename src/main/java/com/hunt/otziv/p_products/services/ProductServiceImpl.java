package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_categories.dto.ProductCategoryDTO;
import com.hunt.otziv.c_categories.model.ProductCategory;
import com.hunt.otziv.c_categories.services.ProductCategoryService;
import com.hunt.otziv.p_products.dto.ProductDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.ProductRepository;
import com.hunt.otziv.p_products.services.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductCategoryService productCategoryService;

    public ProductServiceImpl(ProductRepository productRepository, ProductCategoryService productCategoryService) {
        this.productRepository = productRepository;
        this.productCategoryService = productCategoryService;
    }

    @Override
    public List<Product> findAll() {
        return (List<Product>) productRepository.findAll();
    } // взять все продукты

    @Override
    public Product findById(Long filialId) { // взять продукт по Id
        return productRepository.findById(filialId).orElse(null);
    } // взять продукт по Id

    public boolean save(ProductDTO productDTO){ // Сохранение продукта в БД
        Product product = new Product();
        product.setTitle(productDTO.getTitle());
        product.setPrice(productDTO.getPrice());
        ProductCategory productCategory = productCategoryService.findById(productDTO.getProductCategory().getId());
        product.setProductCategory(productCategory);
        Product product1 = productRepository.save(product);
        productCategory.getProduct().add(product1);
        productCategoryService.save(productCategory);
        return true;
    } // Сохранение продукта в БД

    public boolean delete(ProductDTO productDTO){ // Удаление продукта
        Product product = productRepository.findById(productDTO.getId()).orElse(null);
        ProductCategory productCategory = productCategoryService.findById(productDTO.getProductCategory().getId());
        productCategory.getProduct().remove(product);
        productCategoryService.save(productCategory);
        assert product != null;
        productRepository.delete(product);
        return true;
    } // Удаление продукта


    public boolean update(ProductDTO productDTO){ // Обновление продукта
        log.info("2. Вошли в обновление данных Продукта");
        Product saveProduct = productRepository.findById(productDTO.getId()).orElseThrow(() -> new UsernameNotFoundException(String.format("Компания '%s' не найден", productDTO)));
        log.info("Достали Продукт");
        boolean isChanged = false;

        /*Временная проверка сравнений*/
        System.out.println("title: " + !Objects.equals(productDTO.getTitle(), saveProduct.getTitle()));
        System.out.println("price: " + !Objects.equals(productDTO.getPrice(), saveProduct.getPrice()));
        System.out.println("productCategory: " + !Objects.equals(productDTO.getProductCategory().getId(), saveProduct.getProductCategory().getId()));


        if (!Objects.equals(productDTO.getTitle(), saveProduct.getTitle())){ /*Проверка смены названия*/
            log.info("Обновляем филиал");
            saveProduct.setTitle(productDTO.getTitle());
            isChanged = true;
        }
        if (!Objects.equals(productDTO.getPrice(), saveProduct.getPrice())){ /*Проверка смены работника*/
            log.info("Обновляем работника");
            saveProduct.setPrice(productDTO.getPrice());
            isChanged = true;
        }
        if (!Objects.equals(productDTO.getProductCategory().getId(), saveProduct.getProductCategory().getId())){ /*Проверка смены работника*/
            log.info("Обновляем работника");
            saveProduct.setProductCategory(productCategoryService.findById(productDTO.getProductCategory().getId()));
            isChanged = true;
        }

        if  (isChanged){
            log.info("3. Начали сохранять обновленный Заказ в БД");
            productRepository.save(saveProduct);
            log.info("4. Сохранили обновленный Заказ в БД");
            return true;
        }
        else {
            log.info("3. Изменений не было, сущность в БД не изменена");
            return false;
        }
    } // Обновление продукта

    private ProductCategoryDTO convertProductCategoryToDTO(ProductCategory productCategory){ // Перевод категории продукта в дто
        return ProductCategoryDTO.builder()
                .id(productCategory.getId())
                .title(productCategory.getTitle())
                .build();
    } // Перевод категории продукта в дто

    private ProductCategory converterProductCategoryDTOToEntity(ProductCategoryDTO productCategoryDTO){ // Перевод категории продукта в сущность
        ProductCategory productCategory = new ProductCategory();
        productCategory.setTitle(productCategoryDTO.getTitle());
        return productCategory;
    } // Перевод категории продукта в сущность
}
