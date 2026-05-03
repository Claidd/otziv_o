package com.hunt.otziv.admin.controller;

import com.hunt.otziv.c_categories.model.Category;
import com.hunt.otziv.c_categories.model.ProductCategory;
import com.hunt.otziv.c_categories.model.SubCategory;
import com.hunt.otziv.c_categories.repository.CategoryRepository;
import com.hunt.otziv.c_categories.repository.ProductCategoryRepository;
import com.hunt.otziv.c_categories.repository.SubCategoryRepository;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminDictionaryController {

    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final CityRepository cityRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;

    @GetMapping("/categories")
    public List<CategoryResponse> getCategories(String keyword) {
        return uniqueCategories(categoryRepository.findAllCategoryAndSubcategory()).stream()
                .filter(category -> matches(keyword, category.getCategoryTitle()))
                .sorted(Comparator.comparing(Category::getCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toCategoryResponse)
                .toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@RequestBody TitleRequest request) {
        Category category = Category.builder()
                .categoryTitle(requiredTitle(request.title()))
                .subCategoryTitle(new ArrayList<>())
                .build();
        return toCategoryResponse(categoryRepository.save(category));
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(@PathVariable Long id, @RequestBody TitleRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> notFound("Категория не найдена"));
        category.setCategoryTitle(requiredTitle(request.title()));
        return toCategoryResponse(categoryRepository.save(category));
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable Long id) {
        if (!categoryRepository.existsById(id)) {
            throw notFound("Категория не найдена");
        }
        categoryRepository.deleteById(id);
    }

    @GetMapping("/subcategories")
    public List<SubCategoryResponse> getSubCategories(Long categoryId, String keyword) {
        List<SubCategory> subCategories = categoryId == null
                ? subCategoryRepository.findAll()
                : subCategoryRepository.findAllByCategoryId(categoryId);

        return subCategories.stream()
                .filter(subCategory -> matches(keyword, subCategory.getSubCategoryTitle())
                        || matches(keyword, subCategory.getCategory() != null ? subCategory.getCategory().getCategoryTitle() : null))
                .sorted(Comparator.comparing(SubCategory::getSubCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toSubCategoryResponse)
                .toList();
    }

    @PostMapping("/subcategories")
    @ResponseStatus(HttpStatus.CREATED)
    public SubCategoryResponse createSubCategory(@RequestBody SubCategoryRequest request) {
        Category category = categoryRepository.findById(requiredId(request.categoryId(), "Категория обязательна"))
                .orElseThrow(() -> notFound("Категория не найдена"));
        SubCategory subCategory = SubCategory.builder()
                .subCategoryTitle(requiredTitle(request.title()))
                .category(category)
                .build();
        return toSubCategoryResponse(subCategoryRepository.save(subCategory));
    }

    @PutMapping("/subcategories/{id}")
    public SubCategoryResponse updateSubCategory(@PathVariable Long id, @RequestBody SubCategoryRequest request) {
        SubCategory subCategory = subCategoryRepository.findById(id)
                .orElseThrow(() -> notFound("Подкатегория не найдена"));
        Category category = categoryRepository.findById(requiredId(request.categoryId(), "Категория обязательна"))
                .orElseThrow(() -> notFound("Категория не найдена"));
        subCategory.setSubCategoryTitle(requiredTitle(request.title()));
        subCategory.setCategory(category);
        return toSubCategoryResponse(subCategoryRepository.save(subCategory));
    }

    @DeleteMapping("/subcategories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubCategory(@PathVariable Long id) {
        if (!subCategoryRepository.existsById(id)) {
            throw notFound("Подкатегория не найдена");
        }
        subCategoryRepository.deleteById(id);
    }

    @GetMapping("/cities")
    public List<CityResponse> getCities(String keyword) {
        return cityRepository.findAll().stream()
                .filter(city -> matches(keyword, city.getTitle()))
                .sorted(Comparator.comparing(City::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toCityResponse)
                .toList();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public CityResponse createCity(@RequestBody TitleRequest request) {
        City city = City.builder()
                .title(requiredTitle(request.title()))
                .build();
        return toCityResponse(cityRepository.save(city));
    }

    @PutMapping("/cities/{id}")
    public CityResponse updateCity(@PathVariable Long id, @RequestBody TitleRequest request) {
        City city = cityRepository.findById(id);
        if (city == null) {
            throw notFound("Город не найден");
        }
        city.setTitle(requiredTitle(request.title()));
        return toCityResponse(cityRepository.save(city));
    }

    @DeleteMapping("/cities/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCity(@PathVariable Long id) {
        City city = cityRepository.findById(id);
        if (city == null) {
            throw notFound("Город не найден");
        }
        cityRepository.delete(city);
    }

    @GetMapping("/products")
    public ProductsResponse getProducts(String keyword) {
        List<ProductResponse> products = StreamSupport.stream(productRepository.findAll().spliterator(), false)
                .filter(product -> matches(keyword, product.getTitle())
                        || matches(keyword, product.getProductCategory() != null ? product.getProductCategory().getTitle() : null))
                .sorted(Comparator.comparing(Product::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toProductResponse)
                .toList();

        return new ProductsResponse(products, productCategoryOptions());
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@RequestBody ProductRequest request) {
        ProductCategory category = productCategoryRepository
                .findById(requiredId(request.categoryId(), "Категория продукта обязательна"))
                .orElseThrow(() -> notFound("Категория продукта не найдена"));

        Product product = Product.builder()
                .title(requiredTitle(request.title()))
                .price(requiredPrice(request.price()))
                .photo(request.photo())
                .productCategory(category)
                .build();

        return toProductResponse(productRepository.save(product));
    }

    @PutMapping("/products/{id}")
    public ProductResponse updateProduct(@PathVariable Long id, @RequestBody ProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> notFound("Продукт не найден"));
        ProductCategory category = productCategoryRepository
                .findById(requiredId(request.categoryId(), "Категория продукта обязательна"))
                .orElseThrow(() -> notFound("Категория продукта не найдена"));

        product.setTitle(requiredTitle(request.title()));
        product.setPrice(requiredPrice(request.price()));
        product.setPhoto(request.photo());
        product.setProductCategory(category);

        return toProductResponse(productRepository.save(product));
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(@PathVariable Long id) {
        if (!productRepository.existsById(id)) {
            throw notFound("Продукт не найден");
        }
        productRepository.deleteById(id);
    }

    private List<Category> uniqueCategories(List<Category> categories) {
        Map<Long, Category> unique = new LinkedHashMap<>();
        categories.forEach(category -> unique.putIfAbsent(category.getId(), category));
        return new ArrayList<>(unique.values());
    }

    private CategoryResponse toCategoryResponse(Category category) {
        List<OptionResponse> subCategories = category.getSubCategoryTitle() == null
                ? List.of()
                : category.getSubCategoryTitle().stream()
                .sorted(Comparator.comparing(SubCategory::getSubCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(subCategory -> new OptionResponse(subCategory.getId(), safe(subCategory.getSubCategoryTitle())))
                .toList();

        return new CategoryResponse(
                category.getId(),
                safe(category.getCategoryTitle()),
                subCategories.size(),
                subCategories
        );
    }

    private SubCategoryResponse toSubCategoryResponse(SubCategory subCategory) {
        Category category = subCategory.getCategory();
        return new SubCategoryResponse(
                subCategory.getId(),
                safe(subCategory.getSubCategoryTitle()),
                category == null ? null : new OptionResponse(category.getId(), safe(category.getCategoryTitle()))
        );
    }

    private CityResponse toCityResponse(City city) {
        return new CityResponse(city.getId(), safe(city.getTitle()));
    }

    private ProductResponse toProductResponse(Product product) {
        ProductCategory category = product.getProductCategory();
        return new ProductResponse(
                product.getId(),
                safe(product.getTitle()),
                product.getPrice() == null ? BigDecimal.ZERO : product.getPrice(),
                Boolean.TRUE.equals(product.getPhoto()),
                category == null ? null : new OptionResponse(category.getId(), safe(category.getTitle()))
        );
    }

    private List<OptionResponse> productCategoryOptions() {
        return productCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(ProductCategory::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(category -> new OptionResponse(category.getId(), safe(category.getTitle())))
                .toList();
    }

    private String requiredTitle(String value) {
        String title = safe(value).trim();
        if (title.isBlank()) {
            throw badRequest("Название обязательно");
        }
        return title;
    }

    private Long requiredId(Long value, String message) {
        if (value == null) {
            throw badRequest(message);
        }
        return value;
    }

    private BigDecimal requiredPrice(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw badRequest("Цена должна быть не меньше нуля");
        }
        return value;
    }

    private boolean matches(String keyword, String value) {
        String query = safe(keyword).trim().toLowerCase(Locale.ROOT);
        return query.isBlank() || safe(value).toLowerCase(Locale.ROOT).contains(query);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public record OptionResponse(Long id, String title) {
    }

    public record TitleRequest(String title) {
    }

    public record CategoryResponse(
            Long id,
            String title,
            int subCategoryCount,
            List<OptionResponse> subCategories
    ) {
    }

    public record SubCategoryRequest(String title, Long categoryId) {
    }

    public record SubCategoryResponse(
            Long id,
            String title,
            OptionResponse category
    ) {
    }

    public record CityResponse(Long id, String title) {
    }

    public record ProductRequest(
            String title,
            BigDecimal price,
            Long categoryId,
            boolean photo
    ) {
    }

    public record ProductResponse(
            Long id,
            String title,
            BigDecimal price,
            boolean photo,
            OptionResponse category
    ) {
    }

    public record ProductsResponse(
            List<ProductResponse> products,
            List<OptionResponse> categories
    ) {
    }
}
