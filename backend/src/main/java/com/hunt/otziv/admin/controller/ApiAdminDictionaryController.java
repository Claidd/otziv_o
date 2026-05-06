package com.hunt.otziv.admin.controller;

import com.hunt.otziv.admin.services.BotImportService;
import com.hunt.otziv.admin.services.BotImportService.BotImportResult;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
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
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
public class ApiAdminDictionaryController {

    private final CategoryRepository categoryRepository;
    private final SubCategoryRepository subCategoryRepository;
    private final CityRepository cityRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final BotsRepository botsRepository;
    private final StatusBotRepository statusBotRepository;
    private final WorkerRepository workerRepository;
    private final BotImportService botImportService;

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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public List<CityResponse> getCities(String keyword) {
        return cityRepository.findAll().stream()
                .filter(city -> matches(keyword, city.getTitle()))
                .sorted(Comparator.comparing(City::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toCityResponse)
                .toList();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public CityResponse createCity(@RequestBody TitleRequest request) {
        City city = City.builder()
                .title(requiredTitle(request.title()))
                .build();
        return toCityResponse(cityRepository.save(city));
    }

    @PutMapping("/cities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteCity(@PathVariable Long id) {
        City city = cityRepository.findById(id);
        if (city == null) {
            throw notFound("Город не найден");
        }
        cityRepository.delete(city);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteProduct(@PathVariable Long id) {
        if (!productRepository.existsById(id)) {
            throw notFound("Продукт не найден");
        }
        productRepository.deleteById(id);
    }

    @GetMapping("/bots")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public BotsResponse getBots(String keyword) {
        List<BotResponse> bots = botsRepository.findAllAdminRows().stream()
                .filter(bot -> matchesBot(keyword, bot))
                .sorted(Comparator.comparing(BotsRepository.AdminBotRow::getFio, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toBotResponse)
                .toList();

        return new BotsResponse(bots, workerOptions(), botStatusOptions(), cityOptions());
    }

    @GetMapping("/bots/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public BotResponse getBot(@PathVariable Long id) {
        return botsRepository.findByIdWithAdminDetails(id)
                .map(this::toBotResponse)
                .orElseThrow(() -> notFound("Аккаунт не найден"));
    }

    @PostMapping("/bots/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public BotImportResult importBots(@RequestParam("file") MultipartFile file) {
        return botImportService.importBots(file);
    }

    @PostMapping("/bots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public BotResponse createBot(@RequestBody BotRequest request) {
        String login = requiredText(request.login(), "Логин обязателен");
        assertLoginAvailable(login, null);

        Bot bot = Bot.builder()
                .login(login)
                .password(requiredText(request.password(), "Пароль обязателен"))
                .fio(requiredText(request.fio(), "ФИО обязательно"))
                .worker(requiredWorker(request.workerId()))
                .botCity(optionalCity(request.cityId()))
                .status(requiredBotStatus(request.statusId()))
                .counter(requiredCounter(request.counter()))
                .active(request.active())
                .build();

        return toBotResponse(botsRepository.save(bot));
    }

    @PutMapping("/bots/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public BotResponse updateBot(@PathVariable Long id, @RequestBody BotRequest request) {
        Bot bot = botsRepository.findByIdWithAdminDetails(id)
                .orElseThrow(() -> notFound("Аккаунт не найден"));
        String login = requiredText(request.login(), "Логин обязателен");
        assertLoginAvailable(login, id);

        bot.setLogin(login);
        bot.setPassword(requiredText(request.password(), "Пароль обязателен"));
        bot.setFio(requiredText(request.fio(), "ФИО обязательно"));
        bot.setWorker(requiredWorker(request.workerId()));
        bot.setBotCity(optionalCity(request.cityId()));
        bot.setStatus(requiredBotStatus(request.statusId()));
        bot.setCounter(requiredCounter(request.counter()));
        bot.setActive(request.active());

        return toBotResponse(botsRepository.save(bot));
    }

    @DeleteMapping("/bots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public void deleteBot(@PathVariable Long id) {
        if (!botsRepository.existsById(id)) {
            throw notFound("Аккаунт не найден");
        }
        botsRepository.deleteById(id);
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

    private BotResponse toBotResponse(Bot bot) {
        return new BotResponse(
                bot.getId(),
                safe(bot.getLogin()),
                safe(bot.getPassword()),
                safe(bot.getFio()),
                bot.isActive(),
                bot.getCounter(),
                toStatusOption(bot.getStatus()),
                toWorkerOption(bot.getWorker()),
                toCityOption(bot.getBotCity())
        );
    }

    private BotResponse toBotResponse(BotsRepository.AdminBotRow bot) {
        return new BotResponse(
                bot.getId(),
                safe(bot.getLogin()),
                safe(bot.getPassword()),
                safe(bot.getFio()),
                Boolean.TRUE.equals(bot.getActive()),
                bot.getCounter() == null ? 0 : bot.getCounter(),
                bot.getStatusId() == null ? null : new OptionResponse(bot.getStatusId(), safe(bot.getStatusTitle())),
                bot.getWorkerId() == null ? null : new OptionResponse(
                        bot.getWorkerId(),
                        workerTitle(bot.getWorkerId(), bot.getWorkerFio(), bot.getWorkerUsername())
                ),
                bot.getCityId() == null ? null : new OptionResponse(bot.getCityId(), safe(bot.getCityTitle()))
        );
    }

    private List<OptionResponse> productCategoryOptions() {
        return productCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(ProductCategory::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(category -> new OptionResponse(category.getId(), safe(category.getTitle())))
                .toList();
    }

    private List<OptionResponse> workerOptions() {
        return workerRepository.findWorkerOptions().stream()
                .sorted(Comparator.comparing(this::workerTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(worker -> new OptionResponse(worker.getId(), workerTitle(worker)))
                .toList();
    }

    private List<OptionResponse> botStatusOptions() {
        return StreamSupport.stream(statusBotRepository.findAll().spliterator(), false)
                .sorted(Comparator.comparing(StatusBot::getBotStatusTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toStatusOption)
                .toList();
    }

    private List<OptionResponse> cityOptions() {
        return cityRepository.findAll().stream()
                .sorted(Comparator.comparing(City::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toCityOption)
                .toList();
    }

    private OptionResponse toWorkerOption(Worker worker) {
        if (worker == null) {
            return null;
        }
        return new OptionResponse(worker.getId(), workerTitle(worker));
    }

    private OptionResponse toStatusOption(StatusBot status) {
        if (status == null) {
            return null;
        }
        return new OptionResponse(status.getId(), safe(status.getBotStatusTitle()));
    }

    private OptionResponse toCityOption(City city) {
        if (city == null) {
            return null;
        }
        return new OptionResponse(city.getId(), safe(city.getTitle()));
    }

    private Worker requiredWorker(Long workerId) {
        return workerRepository.findById(requiredId(workerId, "Владелец обязателен"))
                .orElseThrow(() -> notFound("Владелец не найден"));
    }

    private StatusBot requiredBotStatus(Long statusId) {
        return statusBotRepository.findById(requiredId(statusId, "Статус обязателен"))
                .orElseThrow(() -> notFound("Статус аккаунта не найден"));
    }

    private City optionalCity(Long cityId) {
        if (cityId == null) {
            return null;
        }

        City city = cityRepository.findById(cityId);
        if (city == null) {
            throw notFound("Город не найден");
        }
        return city;
    }

    private void assertLoginAvailable(String login, Long currentBotId) {
        Optional<Bot> sameLoginBot = botsRepository.findByLogin(login);
        if (sameLoginBot.isPresent() && !sameLoginBot.get().getId().equals(currentBotId)) {
            throw badRequest("Аккаунт с таким логином уже существует");
        }
    }

    private int requiredCounter(int value) {
        if (value < 0) {
            throw badRequest("Количество публикаций не может быть меньше нуля");
        }
        return value;
    }

    private String requiredText(String value, String message) {
        String text = safe(value).trim();
        if (text.isBlank()) {
            throw badRequest(message);
        }
        return text;
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

    private boolean matchesBot(String keyword, BotsRepository.AdminBotRow bot) {
        return matches(keyword, String.valueOf(bot.getId()))
                || matches(keyword, bot.getLogin())
                || matches(keyword, bot.getPassword())
                || matches(keyword, bot.getFio())
                || matches(keyword, workerTitle(bot.getWorkerId(), bot.getWorkerFio(), bot.getWorkerUsername()))
                || matches(keyword, bot.getStatusTitle())
                || matches(keyword, bot.getCityTitle());
    }

    private String workerTitle(Worker worker) {
        if (worker == null) {
            return "";
        }

        User user = worker.getUser();
        if (user == null) {
            return "Worker #" + worker.getId();
        }

        String fio = safe(user.getFio()).trim();
        if (!fio.isBlank()) {
            return fio;
        }

        String username = safe(user.getUsername()).trim();
        return username.isBlank() ? "Worker #" + worker.getId() : username;
    }

    private String workerTitle(WorkerRepository.WorkerOptionRow worker) {
        if (worker == null) {
            return "";
        }
        return workerTitle(worker.getId(), worker.getFio(), worker.getUsername());
    }

    private String workerTitle(Long workerId, String fioValue, String usernameValue) {
        String fio = safe(fioValue).trim();
        if (!fio.isBlank()) {
            return fio;
        }

        String username = safe(usernameValue).trim();
        return username.isBlank() ? "Worker #" + workerId : username;
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

    public record BotRequest(
            String login,
            String password,
            String fio,
            Long workerId,
            Long cityId,
            Long statusId,
            boolean active,
            int counter
    ) {
    }

    public record BotResponse(
            Long id,
            String login,
            String password,
            String fio,
            boolean active,
            int counter,
            OptionResponse status,
            OptionResponse worker,
            OptionResponse city
    ) {
    }

    public record BotsResponse(
            List<BotResponse> bots,
            List<OptionResponse> workers,
            List<OptionResponse> statuses,
            List<OptionResponse> cities
    ) {
    }
}
