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
import com.hunt.otziv.c_companies.services.SharedChatLinkSyncResponse;
import com.hunt.otziv.c_companies.services.SharedChatLinkSyncService;
import com.hunt.otziv.client_messages.ClientMessageSlotPlanner;
import com.hunt.otziv.client_messages.ScheduledClientMessageService;
import com.hunt.otziv.config.cache.CacheConfig;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.l_lead.model.PromoText;
import com.hunt.otziv.l_lead.model.PromoTextAssignment;
import com.hunt.otziv.l_lead.promo.PromoButtonCatalog;
import com.hunt.otziv.l_lead.promo.PromoButtonCatalog.Slot;
import com.hunt.otziv.l_lead.repository.PromoTextAssignmentRepository;
import com.hunt.otziv.l_lead.repository.PromoTextRepository;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.ProductRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramReportScheduleSettingsRequest;
import com.hunt.otziv.t_telegrambot.service.TelegramReportScheduleSettingsResponse;
import com.hunt.otziv.t_telegrambot.service.TelegramReportScheduleSettingsService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupSyncSettingsRequest;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupSyncSettingsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
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
    private final ManagerRepository managerRepository;
    private final BotImportService botImportService;
    private final PromoTextRepository promoTextRepository;
    private final PromoTextAssignmentRepository promoTextAssignmentRepository;
    private final AppSettingService appSettingService;
    private final TelegramReportScheduleSettingsService telegramReportScheduleSettingsService;
    private final WhatsAppGroupLinkSyncService whatsAppGroupLinkSyncService;
    private final SharedChatLinkSyncService sharedChatLinkSyncService;

    @Value("${app.nagul.cooldown:60}")
    private int defaultNagulCooldownMinutes;

    @Value("${app.nagul.lookahead-days:60}")
    private int defaultNagulLookaheadDays;

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
    @Transactional
    public CategoryResponse createCategory(@RequestBody TitleRequest request) {
        Category category = Category.builder()
                .categoryTitle(requiredTitle(request.title()))
                .subCategoryTitle(new ArrayList<>())
                .build();
        return toCategoryResponse(categoryRepository.save(category));
    }

    @PutMapping("/categories/{id}")
    @Transactional
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
    @Transactional
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
    @Transactional
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
    @Transactional
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
    @Transactional
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
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
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
    @Transactional
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
    @Transactional
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

    @GetMapping("/promo-texts")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public List<PromoTextResponse> getPromoTexts(String keyword) {
        return toPromoTextResponses(promoTextRepository.findAllByOrderByIdAsc()).stream()
                .filter(text -> matches(keyword, String.valueOf(text.id()))
                        || matches(keyword, String.valueOf(text.position()))
                        || matches(keyword, text.text()))
                .toList();
    }

    @GetMapping("/promo-texts/management")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public PromoTextManagementResponse getPromoTextManagement(String keyword) {
        List<PromoText> promoTexts = promoTextRepository.findAllByOrderByIdAsc();
        List<PromoTextResponse> textResponses = toPromoTextResponses(promoTexts).stream()
                .filter(text -> matches(keyword, String.valueOf(text.id()))
                        || matches(keyword, String.valueOf(text.position()))
                        || matches(keyword, text.text())
                        || matches(keyword, promoTextLabel(text.position())))
                .toList();

        return new PromoTextManagementResponse(
                textResponses,
                managerOptions(),
                promoTextAssignmentRepository.findAllWithDetails().stream()
                        .map(this::toPromoAssignmentResponse)
                        .toList(),
                PromoButtonCatalog.slots().stream()
                        .map(slot -> toPromoButtonResponse(slot, promoTexts))
                        .toList()
        );
    }

    @PostMapping("/promo-texts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @CacheEvict(value = CacheConfig.PROMO_TEXTS, allEntries = true)
    public PromoTextResponse createPromoText(@RequestBody PromoTextRequest request) {
        PromoText promoText = PromoText.builder()
                .promoText(toStoredPromoText(requiredText(request.text(), "Текст обязателен")))
                .build();
        PromoText saved = promoTextRepository.save(promoText);
        return toPromoTextResponses(promoTextRepository.findAllByOrderByIdAsc()).stream()
                .filter(text -> text.id().equals(saved.getId()))
                .findFirst()
                .orElseGet(() -> new PromoTextResponse(saved.getId(), 0, toDisplayPromoText(saved.getPromoText())));
    }

    @PutMapping("/promo-texts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @CacheEvict(value = CacheConfig.PROMO_TEXTS, allEntries = true)
    public PromoTextResponse updatePromoText(@PathVariable Long id, @RequestBody PromoTextRequest request) {
        PromoText promoText = promoTextRepository.findById(id)
                .orElseThrow(() -> notFound("Промо-текст не найден"));
        promoText.setPromoText(toStoredPromoText(requiredText(request.text(), "Текст обязателен")));
        PromoText saved = promoTextRepository.save(promoText);
        return toPromoTextResponses(promoTextRepository.findAllByOrderByIdAsc()).stream()
                .filter(text -> text.id().equals(saved.getId()))
                .findFirst()
                .orElseGet(() -> new PromoTextResponse(saved.getId(), 0, toDisplayPromoText(saved.getPromoText())));
    }

    @PutMapping("/promo-text-assignments")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @CacheEvict(value = CacheConfig.PROMO_TEXTS, allEntries = true)
    public PromoAssignmentResponse savePromoTextAssignment(@RequestBody PromoAssignmentRequest request) {
        Manager manager = managerRepository.findById(requiredId(request.managerId(), "Менеджер обязателен"))
                .orElseThrow(() -> notFound("Менеджер не найден"));
        PromoText promoText = promoTextRepository.findById(requiredId(request.promoTextId(), "Промо-текст обязателен"))
                .orElseThrow(() -> notFound("Промо-текст не найден"));
        Slot slot = PromoButtonCatalog.find(requiredText(request.section(), "Раздел обязателен"), requiredText(request.buttonKey(), "Кнопка обязательна"))
                .orElseThrow(() -> badRequest("Кнопка для промо-текста не найдена"));

        PromoTextAssignment assignment = promoTextAssignmentRepository
                .findForSlot(manager.getId(), slot.sectionCode(), slot.buttonKey())
                .orElseGet(PromoTextAssignment::new);
        assignment.setManager(manager);
        assignment.setSectionCode(slot.sectionCode());
        assignment.setButtonKey(slot.buttonKey());
        assignment.setPromoText(promoText);

        promoTextAssignmentRepository.save(assignment);
        return promoTextAssignmentRepository.findForSlot(manager.getId(), slot.sectionCode(), slot.buttonKey())
                .map(this::toPromoAssignmentResponse)
                .orElseThrow(() -> notFound("Назначение промо-текста не найдено"));
    }

    @DeleteMapping("/promo-text-assignments/{managerId}/{section}/{buttonKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @CacheEvict(value = CacheConfig.PROMO_TEXTS, allEntries = true)
    public void resetPromoTextAssignment(
            @PathVariable Long managerId,
            @PathVariable String section,
            @PathVariable String buttonKey
    ) {
        Slot slot = PromoButtonCatalog.find(requiredText(section, "Раздел обязателен"), requiredText(buttonKey, "Кнопка обязательна"))
                .orElseThrow(() -> badRequest("Кнопка для промо-текста не найдена"));
        promoTextAssignmentRepository.findForSlot(managerId, slot.sectionCode(), slot.buttonKey())
                .ifPresent(promoTextAssignmentRepository::delete);
    }

    @GetMapping("/manager-texts")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public List<ManagerTextResponse> getManagerTexts(String keyword) {
        return managerTextResponses().stream()
                .filter(text -> matches(keyword, String.valueOf(text.managerId()))
                        || matches(keyword, text.managerTitle())
                        || matches(keyword, text.payText())
                        || matches(keyword, text.beginText())
                        || matches(keyword, text.offerText())
                        || matches(keyword, text.reminderText())
                        || matches(keyword, text.startText()))
                .toList();
    }

    @PutMapping("/manager-texts/{managerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ManagerTextResponse updateManagerTexts(
            @PathVariable Long managerId,
            @RequestBody ManagerTextRequest request
    ) {
        Manager manager = managerRepository.findById(managerId)
                .orElseThrow(() -> notFound("Менеджер не найден"));

        manager.setPayText(safe(request.payText()));
        manager.setBeginText(safe(request.beginText()));
        manager.setOfferText(safe(request.offerText()));
        manager.setReminderText(safe(request.reminderText()));
        manager.setStartText(safe(request.startText()));

        Manager saved = managerRepository.save(manager);
        return managerTextResponses().stream()
                .filter(text -> text.managerId().equals(saved.getId()))
                .findFirst()
                .orElseGet(() -> new ManagerTextResponse(
                        saved.getId(),
                        "Manager #" + saved.getId(),
                        safe(saved.getPayText()),
                        safe(saved.getBeginText()),
                        safe(saved.getOfferText()),
                        safe(saved.getReminderText()),
                        safe(saved.getStartText())
                ));
    }

    @GetMapping("/settings/nagul")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public NagulSettingsResponse getNagulSettings() {
        return new NagulSettingsResponse(nagulCooldownMinutes(), nagulLookaheadDays());
    }

    @PutMapping("/settings/nagul")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public NagulSettingsResponse updateNagulSettings(@RequestBody NagulSettingsRequest request) {
        int cooldownMinutes = requiredCooldownMinutes(request.cooldownMinutes());
        int lookaheadDays = requiredLookaheadDays(request.lookaheadDays());

        int savedCooldownMinutes = appSettingService.setInt(
                AppSettingService.NAGUL_COOLDOWN_MINUTES,
                cooldownMinutes
        );
        int savedLookaheadDays = appSettingService.setInt(
                AppSettingService.NAGUL_LOOKAHEAD_DAYS,
                lookaheadDays
        );
        return new NagulSettingsResponse(savedCooldownMinutes, savedLookaheadDays);
    }

    @GetMapping("/settings/telegram-reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public TelegramReportScheduleSettingsResponse getTelegramReportSettings() {
        return telegramReportScheduleSettingsService.settings();
    }

    @PutMapping("/settings/telegram-reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public TelegramReportScheduleSettingsResponse updateTelegramReportSettings(
            @RequestBody TelegramReportScheduleSettingsRequest request
    ) {
        try {
            return telegramReportScheduleSettingsService.updateSettings(request);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @GetMapping("/settings/whatsapp-group-sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public WhatsAppGroupSyncSettingsResponse getWhatsAppGroupSyncSettings() {
        return whatsAppGroupLinkSyncService.settings();
    }

    @PutMapping("/settings/whatsapp-group-sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public WhatsAppGroupSyncSettingsResponse updateWhatsAppGroupSyncSettings(
            @RequestBody WhatsAppGroupSyncSettingsRequest request
    ) {
        try {
            return whatsAppGroupLinkSyncService.updateSettings(request);
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @PostMapping("/settings/whatsapp-group-sync/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public WhatsAppGroupSyncSettingsResponse runWhatsAppGroupSync() {
        return whatsAppGroupLinkSyncService.runNow();
    }

    @GetMapping("/settings/client-publication-progress-reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientPublicationProgressReportSettingsResponse getClientPublicationProgressReportSettings() {
        return new ClientPublicationProgressReportSettingsResponse(
                appSettingService.getBoolean(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, true)
        );
    }

    @PutMapping("/settings/client-publication-progress-reports")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientPublicationProgressReportSettingsResponse updateClientPublicationProgressReportSettings(
            @RequestBody ClientPublicationProgressReportSettingsRequest request
    ) {
        boolean enabled = request == null || request.enabled();
        return new ClientPublicationProgressReportSettingsResponse(
                appSettingService.setBoolean(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, enabled)
        );
    }

    @GetMapping("/settings/client-messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageSettingsResponse getClientMessageSettings() {
        return clientMessageSettings();
    }

    @PutMapping("/settings/client-messages")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ClientMessageSettingsResponse updateClientMessageSettings(@RequestBody ClientMessageSettingsRequest request) {
        if (request == null) {
            throw badRequest("Настройки автоответчика не переданы");
        }

        String businessWindows = requiredSettingText(request.businessWindows(), "Укажите рабочие окна автоответчика");
        if (!ClientMessageSlotPlanner.isValidWindowsSpec(businessWindows)) {
            throw badRequest("Рабочие окна должны быть в формате 10:00-12:00,14:00-17:00 без пересечений");
        }

        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, request.workerEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, request.liveEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED, request.monitorEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, request.reviewCheckEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, request.paymentReminderEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, request.badReviewInvoiceEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, request.paymentOverdueEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, request.paymentOverdueLiveEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED, request.archiveReorderEnabled());
        appSettingService.setBoolean(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_ENABLED, request.errorProtectionEnabled());

        saveIntSetting(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS, request.reviewCheckIntervalDays(), 1, 365, "Интервал проверки отзывов");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS, request.paymentReminderIntervalDays(), 1, 365, "Интервал напоминания об оплате");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS, request.paymentOverdueDays(), 1, 365, "Срок просрочки оплаты");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS, request.archiveReorderMonths(), 1, 36, "Интервал архивного предложения");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_THRESHOLD, request.errorProtectionThreshold(), 1, 10000, "Порог массовых ошибок");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_WINDOW_MINUTES, request.errorProtectionWindowMinutes(), 1, 1440, "Окно массовых ошибок");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_COOLDOWN_MINUTES, request.errorProtectionCooldownMinutes(), 1, 1440, "Пауза после массовых ошибок");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_RETENTION_DAYS, request.retentionDays(), 1, 3650, "Хранение журнала");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_TICK_BATCH_SIZE, request.tickBatchSize(), 1, 100, "Размер пачки");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_CANDIDATE_LIMIT, request.candidateLimit(), 1, 5000, "Лимит кандидатов");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_DAILY_LIMIT, request.dailyLimit(), 1, 5000, "Дневной лимит");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_DEFAULT_GAP_SECONDS, request.defaultGapSeconds(), 30, 86400, "Общая пауза");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_WHATSAPP_GAP_SECONDS, request.whatsAppGapSeconds(), 30, 86400, "Пауза WhatsApp");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_TELEGRAM_GAP_SECONDS, request.telegramGapSeconds(), 30, 86400, "Пауза Telegram");
        saveIntSetting(AppSettingService.CLIENT_MESSAGES_MAX_GAP_SECONDS, request.maxGapSeconds(), 30, 86400, "Пауза MAX");

        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS, businessWindows);
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES, requiredSettingText(request.reviewCheckStatuses(), "Укажите статусы проверки отзывов"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES, requiredSettingText(request.paymentReminderStatuses(), "Укажите статусы напоминаний об оплате"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES, requiredSettingText(request.paymentOverdueStatuses(), "Укажите статусы просрочки оплаты"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES, requiredSettingText(request.closedOrderStatuses(), "Укажите закрытые статусы заказов"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_TARGET_STATUS, requiredSettingText(request.paymentOverdueTargetStatus(), "Укажите целевой статус просрочки"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS, requiredSettingText(request.archiveCompanyStatus(), "Укажите архивный статус компании"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES, requiredSettingText(request.archiveInactiveOrderStatuses(), "Укажите неактивные статусы заказов"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES, requiredSettingText(request.openNextOrderRequestStatuses(), "Укажите открытые статусы заявок"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_REVIEW_LINK_BASE_URL, requiredSettingText(request.reviewLinkBaseUrl(), "Укажите базовую ссылку проверки отзывов"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_REVIEW_REMINDER_TEXT, requiredSettingText(request.reviewReminderText(), "Укажите текст проверки отзывов"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE, requiredPaymentInstructionSource(request.paymentInstructionSource()));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT, requiredSettingText(request.paymentReminderText(), "Укажите текст оплаты"));
        appSettingService.setString(AppSettingService.CLIENT_MESSAGES_ARCHIVE_OFFER_TEXT, requiredSettingText(request.archiveOfferText(), "Укажите текст архивного предложения"));

        return clientMessageSettings();
    }

    @PostMapping("/settings/shared-chat-links/sync")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public SharedChatLinkSyncResponse runSharedChatLinkSync() {
        return sharedChatLinkSyncService.syncSharedChatIds();
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

    private List<PromoTextResponse> toPromoTextResponses(List<PromoText> texts) {
        List<PromoTextResponse> response = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            PromoText text = texts.get(index);
            response.add(new PromoTextResponse(
                    text.getId(),
                    index + 1,
                    toDisplayPromoText(text.getPromoText())
            ));
        }
        return response;
    }

    private PromoAssignmentResponse toPromoAssignmentResponse(PromoTextAssignment assignment) {
        Slot slot = PromoButtonCatalog.find(assignment.getSectionCode(), assignment.getButtonKey())
                .orElse(new Slot(
                        assignment.getSectionCode(),
                        assignment.getSectionCode(),
                        assignment.getButtonKey(),
                        assignment.getButtonKey(),
                        0,
                        0
                ));
        PromoText promoText = assignment.getPromoText();
        Manager manager = assignment.getManager();

        return new PromoAssignmentResponse(
                assignment.getId(),
                manager == null ? null : manager.getId(),
                managerTitle(manager),
                slot.sectionCode(),
                slot.sectionTitle(),
                slot.buttonKey(),
                slot.buttonLabel(),
                slot.outputIndex() + 1,
                promoText == null ? null : promoText.getId(),
                promoText == null ? "" : promoTextLabel(positionOfPromoText(promoText.getId()))
        );
    }

    private PromoButtonResponse toPromoButtonResponse(Slot slot, List<PromoText> promoTexts) {
        Long defaultPromoTextId = promoTextIdAtPosition(promoTexts, slot.defaultPosition());
        return new PromoButtonResponse(
                slot.sectionCode(),
                slot.sectionTitle(),
                slot.buttonKey(),
                slot.buttonLabel(),
                slot.outputIndex() + 1,
                slot.defaultPosition(),
                defaultPromoTextId
        );
    }

    private List<ManagerTextResponse> managerTextResponses() {
        return managerRepository.findAllWithUserAndImage().stream()
                .sorted(Comparator.comparing(this::managerTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::toManagerTextResponse)
                .toList();
    }

    private ManagerTextResponse toManagerTextResponse(Manager manager) {
        return new ManagerTextResponse(
                manager.getId(),
                managerTitle(manager),
                safe(manager.getPayText()),
                safe(manager.getBeginText()),
                safe(manager.getOfferText()),
                safe(manager.getReminderText()),
                safe(manager.getStartText())
        );
    }

    private List<OptionResponse> productCategoryOptions() {
        return productCategoryRepository.findAll().stream()
                .sorted(Comparator.comparing(ProductCategory::getTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(category -> new OptionResponse(category.getId(), safe(category.getTitle())))
                .toList();
    }

    private List<OptionResponse> managerOptions() {
        return managerRepository.findAllWithUserAndImage().stream()
                .sorted(Comparator.comparing(this::managerTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(manager -> new OptionResponse(manager.getId(), managerTitle(manager)))
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

    private int requiredCooldownMinutes(Integer value) {
        if (value == null) {
            throw badRequest("Укажите время между выгулами");
        }
        if (value < 0 || value > 1440) {
            throw badRequest("Время между выгулами должно быть от 0 до 1440 минут");
        }
        return value;
    }

    private int requiredLookaheadDays(Integer value) {
        if (value == null) {
            throw badRequest("Укажите горизонт выдачи выгула");
        }
        if (value < 0 || value > 365) {
            throw badRequest("Горизонт выдачи выгула должен быть от 0 до 365 дней");
        }
        return value;
    }

    private int nagulCooldownMinutes() {
        return appSettingService.getInt(AppSettingService.NAGUL_COOLDOWN_MINUTES, defaultNagulCooldownMinutes);
    }

    private int nagulLookaheadDays() {
        return appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, defaultNagulLookaheadDays);
    }

    private ClientMessageSettingsResponse clientMessageSettings() {
        return new ClientMessageSettingsResponse(
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_WORKER_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_LIVE_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_MONITOR_ENABLED, false),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_BAD_REVIEW_INVOICE_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_LIVE_ENABLED, false),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_ENABLED, true),
                appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_ENABLED, true),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS,
                        ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_INTERVAL_DAYS,
                        ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_DAYS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_REORDER_MONTHS,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_REORDER_MONTHS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_THRESHOLD,
                        ScheduledClientMessageService.DEFAULT_ERROR_PROTECTION_THRESHOLD
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_WINDOW_MINUTES,
                        ScheduledClientMessageService.DEFAULT_ERROR_PROTECTION_WINDOW_MINUTES
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_ERROR_PROTECTION_COOLDOWN_MINUTES,
                        ScheduledClientMessageService.DEFAULT_ERROR_PROTECTION_COOLDOWN_MINUTES
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_RETENTION_DAYS,
                        ScheduledClientMessageService.DEFAULT_RETENTION_DAYS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_TICK_BATCH_SIZE,
                        ScheduledClientMessageService.DEFAULT_TICK_BATCH_SIZE
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_CANDIDATE_LIMIT,
                        ScheduledClientMessageService.DEFAULT_CANDIDATE_LIMIT
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_DAILY_LIMIT,
                        ScheduledClientMessageService.DEFAULT_DAILY_LIMIT
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_DEFAULT_GAP_SECONDS,
                        ScheduledClientMessageService.DEFAULT_DEFAULT_GAP_SECONDS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_WHATSAPP_GAP_SECONDS,
                        ScheduledClientMessageService.DEFAULT_WHATSAPP_GAP_SECONDS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_TELEGRAM_GAP_SECONDS,
                        ScheduledClientMessageService.DEFAULT_TELEGRAM_GAP_SECONDS
                ),
                appSettingService.getInt(
                        AppSettingService.CLIENT_MESSAGES_MAX_GAP_SECONDS,
                        ScheduledClientMessageService.DEFAULT_MAX_GAP_SECONDS
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_BUSINESS_WINDOWS,
                        ClientMessageSlotPlanner.DEFAULT_WINDOWS_SPEC
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_STATUSES,
                        ScheduledClientMessageService.DEFAULT_REVIEW_CHECK_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_STATUSES,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_STATUSES,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_CLOSED_ORDER_STATUSES,
                        ScheduledClientMessageService.DEFAULT_CLOSED_ORDER_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_TARGET_STATUS,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_OVERDUE_TARGET_STATUS
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_COMPANY_STATUS,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_COMPANY_STATUS
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_INACTIVE_ORDER_STATUSES,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_OPEN_NEXT_ORDER_REQUEST_STATUSES,
                        ScheduledClientMessageService.DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_LINK_BASE_URL,
                        ScheduledClientMessageService.DEFAULT_REVIEW_LINK_BASE_URL
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_REVIEW_REMINDER_TEXT,
                        ScheduledClientMessageService.DEFAULT_REVIEW_REMINDER_TEXT
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_INSTRUCTION_SOURCE,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_INSTRUCTION_SOURCE
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_PAYMENT_REMINDER_TEXT,
                        ScheduledClientMessageService.DEFAULT_PAYMENT_REMINDER_TEXT
                ),
                appSettingService.getString(
                        AppSettingService.CLIENT_MESSAGES_ARCHIVE_OFFER_TEXT,
                        ScheduledClientMessageService.DEFAULT_ARCHIVE_OFFER_TEXT
                )
        );
    }

    private int saveIntSetting(String key, Integer value, int min, int max, String title) {
        if (value == null) {
            throw badRequest(title + ": значение обязательно");
        }
        if (value < min || value > max) {
            throw badRequest(title + ": допустимо от " + min + " до " + max);
        }
        return appSettingService.setInt(key, value);
    }

    private String requiredText(String value, String message) {
        String text = safe(value).trim();
        if (text.isBlank()) {
            throw badRequest(message);
        }
        return text;
    }

    private String requiredSettingText(String value, String message) {
        String text = requiredText(value, message);
        if (text.length() > 500) {
            throw badRequest(message + ": не больше 500 символов");
        }
        return text;
    }

    private String requiredPaymentInstructionSource(String value) {
        String source = safe(value).trim().toUpperCase(Locale.ROOT);
        if (source.isBlank()) {
            return ScheduledClientMessageService.DEFAULT_PAYMENT_INSTRUCTION_SOURCE;
        }
        if ("MANAGER_TEXT".equals(source) || "TBANK_LINK".equals(source)) {
            return source;
        }
        throw badRequest("Источник оплаты должен быть MANAGER_TEXT или TBANK_LINK");
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

    private String managerTitle(Manager manager) {
        if (manager == null) {
            return "";
        }

        User user = manager.getUser();
        if (user == null) {
            return "Manager #" + manager.getId();
        }

        String fio = safe(user.getFio()).trim();
        if (!fio.isBlank()) {
            return fio;
        }

        String username = safe(user.getUsername()).trim();
        return username.isBlank() ? "Manager #" + manager.getId() : username;
    }

    private Long promoTextIdAtPosition(List<PromoText> promoTexts, int position) {
        int index = position - 1;
        if (index < 0 || index >= promoTexts.size()) {
            return null;
        }
        return promoTexts.get(index).getId();
    }

    private int positionOfPromoText(Long promoTextId) {
        List<PromoText> promoTexts = promoTextRepository.findAllByOrderByIdAsc();
        for (int index = 0; index < promoTexts.size(); index++) {
            if (promoTexts.get(index).getId().equals(promoTextId)) {
                return index + 1;
            }
        }
        return 0;
    }

    private String promoTextLabel(int position) {
        return switch (position) {
            case 1 -> "предложение";
            case 2 -> "напоминание";
            case 3 -> "данные";
            case 4 -> "ответы";
            case 5 -> "ссылка на проверку";
            case 6 -> "напоминание заказа";
            case 7 -> "угроза";
            case 10 -> "рассылка";
            case 11 -> "пояснение";
            case 12 -> "текст повторного заказа";
            default -> position > 0 ? "Текст #" + position : "Промо-текст";
        };
    }

    private boolean matches(String keyword, String value) {
        String query = safe(keyword).trim().toLowerCase(Locale.ROOT);
        return query.isBlank() || safe(value).toLowerCase(Locale.ROOT).contains(query);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String toDisplayPromoText(String value) {
        return safe(value).replace("lineSep", System.lineSeparator());
    }

    private String toStoredPromoText(String value) {
        return safe(value)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "lineSep");
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

    public record PromoTextRequest(String text) {
    }

    public record PromoTextResponse(
            Long id,
            int position,
            String text
    ) {
    }

    public record PromoTextManagementResponse(
            List<PromoTextResponse> texts,
            List<OptionResponse> managers,
            List<PromoAssignmentResponse> assignments,
            List<PromoButtonResponse> buttons
    ) {
    }

    public record PromoAssignmentRequest(
            Long managerId,
            String section,
            String buttonKey,
            Long promoTextId
    ) {
    }

    public record PromoAssignmentResponse(
            Long id,
            Long managerId,
            String managerTitle,
            String section,
            String sectionTitle,
            String buttonKey,
            String buttonLabel,
            int outputPosition,
            Long promoTextId,
            String promoTextLabel
    ) {
    }

    public record PromoButtonResponse(
            String section,
            String sectionTitle,
            String buttonKey,
            String buttonLabel,
            int outputPosition,
            int defaultPromoPosition,
            Long defaultPromoTextId
    ) {
    }

    public record ManagerTextRequest(
            String payText,
            String beginText,
            String offerText,
            String reminderText,
            String startText
    ) {
    }

    public record ManagerTextResponse(
            Long managerId,
            String managerTitle,
            String payText,
            String beginText,
            String offerText,
            String reminderText,
            String startText
    ) {
    }

    public record NagulSettingsRequest(Integer cooldownMinutes, Integer lookaheadDays) {
    }

    public record NagulSettingsResponse(int cooldownMinutes, int lookaheadDays) {
    }

    public record ClientPublicationProgressReportSettingsRequest(boolean enabled) {
    }

    public record ClientPublicationProgressReportSettingsResponse(boolean enabled) {
    }

    public record ClientMessageSettingsRequest(
            boolean workerEnabled,
            boolean liveEnabled,
            boolean monitorEnabled,
            boolean reviewCheckEnabled,
            boolean paymentReminderEnabled,
            boolean badReviewInvoiceEnabled,
            boolean paymentOverdueEnabled,
            boolean paymentOverdueLiveEnabled,
            boolean archiveReorderEnabled,
            boolean errorProtectionEnabled,
            Integer reviewCheckIntervalDays,
            Integer paymentReminderIntervalDays,
            Integer paymentOverdueDays,
            Integer archiveReorderMonths,
            Integer errorProtectionThreshold,
            Integer errorProtectionWindowMinutes,
            Integer errorProtectionCooldownMinutes,
            Integer retentionDays,
            Integer tickBatchSize,
            Integer candidateLimit,
            Integer dailyLimit,
            Integer defaultGapSeconds,
            Integer whatsAppGapSeconds,
            Integer telegramGapSeconds,
            Integer maxGapSeconds,
            String businessWindows,
            String reviewCheckStatuses,
            String paymentReminderStatuses,
            String paymentOverdueStatuses,
            String closedOrderStatuses,
            String paymentOverdueTargetStatus,
            String archiveCompanyStatus,
            String archiveInactiveOrderStatuses,
            String openNextOrderRequestStatuses,
            String reviewLinkBaseUrl,
            String reviewReminderText,
            String paymentInstructionSource,
            String paymentReminderText,
            String archiveOfferText
    ) {
    }

    public record ClientMessageSettingsResponse(
            boolean workerEnabled,
            boolean liveEnabled,
            boolean monitorEnabled,
            boolean reviewCheckEnabled,
            boolean paymentReminderEnabled,
            boolean badReviewInvoiceEnabled,
            boolean paymentOverdueEnabled,
            boolean paymentOverdueLiveEnabled,
            boolean archiveReorderEnabled,
            boolean errorProtectionEnabled,
            int reviewCheckIntervalDays,
            int paymentReminderIntervalDays,
            int paymentOverdueDays,
            int archiveReorderMonths,
            int errorProtectionThreshold,
            int errorProtectionWindowMinutes,
            int errorProtectionCooldownMinutes,
            int retentionDays,
            int tickBatchSize,
            int candidateLimit,
            int dailyLimit,
            int defaultGapSeconds,
            int whatsAppGapSeconds,
            int telegramGapSeconds,
            int maxGapSeconds,
            String businessWindows,
            String reviewCheckStatuses,
            String paymentReminderStatuses,
            String paymentOverdueStatuses,
            String closedOrderStatuses,
            String paymentOverdueTargetStatus,
            String archiveCompanyStatus,
            String archiveInactiveOrderStatuses,
            String openNextOrderRequestStatuses,
            String reviewLinkBaseUrl,
            String reviewReminderText,
            String paymentInstructionSource,
            String paymentReminderText,
            String archiveOfferText
    ) {
    }
}
