package com.hunt.otziv.manager.services;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.manager.dto.api.BadReviewSummaryResponse;
import com.hunt.otziv.manager.dto.api.BadReviewTaskDetailsResponse;
import com.hunt.otziv.manager.dto.api.CompanyEditResponse;
import com.hunt.otziv.manager.dto.api.CompanyOrderCreateRequest;
import com.hunt.otziv.manager.dto.api.CompanyOrderCreateResponse;
import com.hunt.otziv.manager.dto.api.FilialResponse;
import com.hunt.otziv.manager.dto.api.OptionResponse;
import com.hunt.otziv.manager.dto.api.OrderDetailsResponse;
import com.hunt.otziv.manager.dto.api.OrderEditResponse;
import com.hunt.otziv.manager.dto.api.OrderProductResponse;
import com.hunt.otziv.manager.dto.api.ProductOptionResponse;
import com.hunt.otziv.manager.dto.api.ReviewDetailsResponse;
import com.hunt.otziv.manager.dto.api.ReviewRecoveryBatchDetailsResponse;
import com.hunt.otziv.manager.dto.api.ReviewRecoveryTaskDetailsResponse;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.deletion.OrderDeletionPolicy;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagerBoardEditAssembler {

    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final CityService cityService;
    private final CompanyStatusService companyStatusService;
    private final OrderService orderService;
    private final ProductService productService;
    private final AmountService amountService;
    private final ReviewService reviewService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final ManagerPermissionService managerPermissionService;
    private final OrderDeletionPolicy orderDeletionPolicy;

    public CompanyEditResponse buildCompanyEditResponse(
            CompanyDTO company,
            Principal principal,
            Authentication authentication
    ) {
        Long categoryId = idOf(company.getCategoryCompany());
        return new CompanyEditResponse(
                company.getId(),
                safe(company.getTitle()),
                safe(company.getUrlChat()),
                safe(company.getGroupId()),
                company.getTelegramGroupChatId(),
                company.getMaxGroupChatId(),
                safe(company.getUrlSite()),
                safe(company.getTelephone()),
                safe(company.getCity()),
                safe(company.getEmail()),
                safe(company.getCommentsCompany()),
                company.isActive(),
                dateValue(company.getCreateDate()),
                dateValue(company.getUpdateStatus()),
                dateValue(company.getDateNewTry()),
                option(company.getStatus()),
                option(company.getCategoryCompany()),
                option(company.getSubCategory()),
                option(company.getManager()),
                categoryOptions(),
                subCategoryOptions(categoryId),
                statusOptions(),
                managerOptions(principal, authentication),
                workerOptions(company),
                currentWorkerOptions(company),
                filialOptions(company),
                cityOptions(),
                managerPermissionService.hasRole(authentication, "ADMIN")
        );
    }

    public CompanyOrderCreateResponse buildCompanyOrderCreateResponse(CompanyDTO company) {
        List<OrderProductResponse> products = orderProductOptions();
        List<Integer> amounts = amountOptions();
        List<OptionResponse> workers = currentWorkerOptions(company);
        List<FilialResponse> filials = filialOptions(company);

        return new CompanyOrderCreateResponse(
                company.getId(),
                safe(company.getTitle()),
                products,
                amounts,
                workers,
                filials,
                products.isEmpty() ? null : products.get(0).id(),
                amounts.isEmpty() ? 1 : amounts.get(0),
                workers.isEmpty() ? null : workers.get(0).id(),
                filials.isEmpty() ? null : filials.get(0).id()
        );
    }

    public Product validateCompanyOrderCreateRequest(CompanyDTO company, CompanyOrderCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные заказа не переданы");
        }

        if (request.productId() == null || request.productId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Продукт не выбран");
        }

        if (request.amount() == null || request.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Количество отзывов не выбрано");
        }

        if (request.workerId() == null || request.workerId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не выбран");
        }

        if (request.filialId() == null || request.filialId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не выбран");
        }

        if (company.getManager() == null || company.getManager().getManagerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У компании не указан менеджер");
        }

        Product product = productService.findById(request.productId());
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Продукт не найден");
        }

        List<Integer> amounts = amountOptions();
        if (!amounts.isEmpty() && !amounts.contains(request.amount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое количество отзывов");
        }

        boolean workerBelongsToCompany = currentWorkerOptions(company).stream()
                .anyMatch(worker -> Objects.equals(worker.id(), request.workerId()));
        if (!workerBelongsToCompany) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не назначен на компанию");
        }

        boolean filialBelongsToCompany = filialOptions(company).stream()
                .anyMatch(filial -> Objects.equals(filial.id(), request.filialId()));
        if (!filialBelongsToCompany) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не найден у компании");
        }

        return product;
    }

    public OrderEditResponse buildOrderEditResponse(
            OrderDTO order,
            Principal principal,
            Authentication authentication
    ) {
        boolean adminOrOwner = managerPermissionService.hasRole(authentication, "ADMIN") || managerPermissionService.hasRole(authentication, "OWNER");
        boolean canDelete = orderDeletionPolicy.canDelete(
                managerPermissionService.primaryReviewRole(authentication),
                optionLabel(order.getStatus())
        );
        OptionResponse currentManager = option(order.getManager());
        List<OptionResponse> managers = managerPermissionService.hasOnlyWorkerRole(authentication)
                ? currentManager == null ? List.of() : List.of(currentManager)
                : managerOptions(principal, authentication);

        return new OrderEditResponse(
                order.getId(),
                order.getCompany() != null ? order.getCompany().getId() : null,
                order.getCompany() != null ? safe(order.getCompany().getTitle()) : "",
                optionLabel(order.getStatus()),
                order.getSum(),
                order.getAmount(),
                order.getCounter(),
                dateValue(order.getCreated()),
                dateValue(order.getChanged()),
                dateValue(order.getPayDay()),
                safe(order.getOrderComments()),
                safe(order.getCommentsCompany()),
                order.isComplete(),
                option(order.getFilial()),
                option(order.getManager()),
                option(order.getWorker()),
                filialOptions(order),
                managers,
                workerOptions(order),
                adminOrOwner,
                canDelete
        );
    }

    public OrderDetailsResponse buildOrderDetailsResponse(Long orderId, Authentication authentication) {
        OrderDTO order = orderService.getOrderDTO(orderId);
        List<ReviewDTOOne> reviews = reviewService.getReviewsAllByOrderId(orderId);
        BadReviewTaskSummary summary = badReviewTaskService.getSummaryForOrder(orderId);
        BadReviewTaskSummary safeSummary = summary == null ? BadReviewTaskSummary.empty() : summary;
        List<BadReviewTask> badReviewTasks = badReviewTaskService.getTasksByOrderId(orderId);
        List<ReviewRecoveryTask> recoveryTasks = reviewRecoveryTaskService.getTasksByOrderId(orderId);
        ReviewDTOOne firstReview = reviews.isEmpty() ? null : reviews.get(0);
        BigDecimal orderSum = order.getSum() == null ? BigDecimal.ZERO : order.getSum();

        Long companyId = order.getCompany() != null
                ? order.getCompany().getId()
                : firstReview != null ? firstReview.getCompanyId() : null;
        String companyTitle = order.getCompany() != null
                ? safe(order.getCompany().getTitle())
                : firstReview != null ? safe(firstReview.getCompanyTitle()) : "";
        String productTitle = firstReview != null && !isBlank(firstReview.getProductTitle())
                ? safe(firstReview.getProductTitle())
                : companyTitle;
        UUID orderDetailsId = firstReview != null ? firstReview.getOrderDetailsId() : order.getOrderDetailsId();
        if (orderDetailsId == null && order.getDetails() != null && !order.getDetails().isEmpty()) {
            orderDetailsId = order.getDetails().get(0).getId();
        }

        return new OrderDetailsResponse(
                order.getId(),
                companyId,
                orderDetailsId,
                "Детали заказа " + (isBlank(productTitle) ? "#" + order.getId() : productTitle),
                companyTitle,
                productTitle,
                optionLabel(order.getStatus()),
                order.getAmount(),
                order.getCounter(),
                order.getSum(),
                orderSum.add(safeSummary.doneSum()),
                toBadReviewSummaryResponse(safeSummary, orderSum),
                firstReview != null && !isBlank(firstReview.getOrderComments())
                        ? safe(firstReview.getOrderComments())
                        : safe(order.getOrderComments()),
                firstReview != null && !isBlank(firstReview.getCommentCompany())
                        ? safe(firstReview.getCommentCompany())
                        : safe(order.getCommentsCompany()),
                dateValue(order.getCreated()),
                dateValue(order.getChanged()),
                reviews.stream().map(this::toReviewDetailsResponse).toList(),
                badReviewTasks.stream().map(this::toBadReviewTaskResponse).toList(),
                recoveryTasks.stream().map(this::toReviewRecoveryTaskResponse).toList(),
                productOptions(),
                managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER", "WORKER"),
                managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"),
                managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"),
                managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER"),
                canEditReviewVigul(authentication),
                managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER", "WORKER")
        );
    }

    public ReviewDetailsResponse buildReviewDetailsResponse(Long orderId, Long reviewId) {
        Review review = reviewService.getReviewById(reviewId);
        if (review == null || review.getOrderDetails() == null || review.getOrderDetails().getOrder() == null
                || !Objects.equals(orderId, review.getOrderDetails().getOrder().getId())) {
            throw new IllegalArgumentException("Отзыв не найден в этом заказе");
        }

        return toReviewDetailsResponse(reviewService.toReviewDTOOne(review));
    }

    private boolean canEditReviewVigul(Authentication authentication) {
        return managerPermissionService.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER")
                || managerPermissionService.hasOnlyWorkerRole(authentication);
    }

    public List<OptionResponse> subCategoryOptions(Long categoryId) {
        if (categoryId == null || categoryId == 0L) {
            return subCategoryService.getAllSubCategories().stream()
                    .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                    .map(this::option)
                    .filter(Objects::nonNull)
                    .toList();
        }

        return subCategoryService.getSubcategoriesByCategoryId(categoryId).stream()
                .sorted(Comparator.comparing(SubCategoryDTO::getSubCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private BadReviewSummaryResponse toBadReviewSummaryResponse(BadReviewTaskSummary summary, BigDecimal orderSum) {
        BigDecimal baseSum = orderSum == null ? BigDecimal.ZERO : orderSum;
        BadReviewTaskSummary safeSummary = summary == null ? BadReviewTaskSummary.empty() : summary;
        return new BadReviewSummaryResponse(
                safeSummary.total(),
                safeSummary.pending(),
                safeSummary.done(),
                safeSummary.canceled(),
                safeSummary.doneSum(),
                safeSummary.pendingSum(),
                baseSum.add(safeSummary.doneSum())
        );
    }

    private BadReviewTaskDetailsResponse toBadReviewTaskResponse(BadReviewTask task) {
        Review review = task.getSourceReview();
        Bot taskBot = task.getBot();
        Bot sourceBot = review != null ? review.getBot() : null;
        Long sourceReviewId = review != null ? review.getId() : null;
        Long botId = taskBot != null ? taskBot.getId() : sourceBot != null ? sourceBot.getId() : null;
        String botFio = firstNonBlank(
                task.getBotFioSnapshot(),
                taskBot != null ? taskBot.getFio() : null,
                sourceBot != null ? sourceBot.getFio() : null
        );
        String botLogin = firstNonBlank(
                task.getBotLoginSnapshot(),
                taskBot != null ? taskBot.getLogin() : null,
                sourceBot != null ? sourceBot.getLogin() : null
        );
        String botPassword = firstNonBlank(
                task.getBotPasswordSnapshot(),
                taskBot != null ? taskBot.getPassword() : null,
                sourceBot != null ? sourceBot.getPassword() : null
        );
        String workerFio = task.getWorker() != null && task.getWorker().getUser() != null
                ? safe(task.getWorker().getUser().getFio())
                : "";
        return new BadReviewTaskDetailsResponse(
                task.getId(),
                sourceReviewId,
                taskStatusLabel(task.getStatus()),
                task.getStatus() == null ? "" : task.getStatus().name(),
                task.getOriginalRating(),
                task.getTargetRating(),
                task.getPrice(),
                dateValue(task.getScheduledDate()),
                dateValue(task.getCompletedDate()),
                workerFio,
                botId,
                botFio,
                botLogin,
                botPassword,
                firstNonBlank(task.getTaskText(), review != null ? review.getText() : null),
                safe(task.getComment())
        );
    }

    private ReviewRecoveryTaskDetailsResponse toReviewRecoveryTaskResponse(ReviewRecoveryTask task) {
        Review review = task.getSourceReview();
        ReviewRecoveryBatch batch = task.getBatch();
        Long sourceReviewId = review != null ? review.getId() : null;
        Long botId = task.getBot() != null ? task.getBot().getId() : null;
        String botFio = task.getBot() != null && !isBlank(task.getBot().getFio())
                ? safe(task.getBot().getFio())
                : safe(task.getBotFioSnapshot());
        String botLogin = task.getBot() != null && !isBlank(task.getBot().getLogin())
                ? safe(task.getBot().getLogin())
                : safe(task.getBotLoginSnapshot());
        String botPassword = task.getBot() != null && !isBlank(task.getBot().getPassword())
                ? safe(task.getBot().getPassword())
                : safe(task.getBotPasswordSnapshot());
        String workerFio = task.getWorker() != null && task.getWorker().getUser() != null
                ? safe(task.getWorker().getUser().getFio())
                : "";

        return new ReviewRecoveryTaskDetailsResponse(
                task.getId(),
                batch != null ? batch.getId() : null,
                sourceReviewId,
                recoveryTaskStatusLabel(task.getStatus()),
                task.getStatus() == null ? "" : task.getStatus().name(),
                safe(task.getRecoveryText()),
                safe(task.getRecoveryAnswer()),
                dateValue(task.getScheduledDate()),
                dateValue(task.getCompletedDate()),
                workerFio,
                botId,
                botFio,
                botLogin,
                botPassword,
                toReviewRecoveryBatchResponse(batch)
        );
    }

    private ReviewRecoveryBatchDetailsResponse toReviewRecoveryBatchResponse(ReviewRecoveryBatch batch) {
        if (batch == null) {
            return null;
        }

        return new ReviewRecoveryBatchDetailsResponse(
                batch.getId(),
                recoveryBatchStatusLabel(batch.getStatus()),
                batch.getStatus() == null ? "" : batch.getStatus().name(),
                dateValue(batch.getCompletedAt()),
                dateValue(batch.getClientNotifiedAt())
        );
    }

    private ReviewDetailsResponse toReviewDetailsResponse(ReviewDTOOne review) {
        return new ReviewDetailsResponse(
                review.getId(),
                review.getCompanyId(),
                review.getOrderDetailsId(),
                review.getOrderId(),
                safe(review.getText()),
                safe(review.getAnswer()),
                safe(review.getCategory()),
                safe(review.getSubCategory()),
                review.getBotId(),
                safe(review.getBotFio()),
                safe(review.getBotLogin()),
                safe(review.getBotPassword()),
                review.getBotCounter(),
                safe(review.getCompanyTitle()),
                safe(review.getCommentCompany()),
                safe(review.getOrderComments()),
                safe(review.getFilialCity()),
                safe(review.getFilialTitle()),
                safe(review.getFilialUrl()),
                review.getProductId(),
                safe(review.getProductTitle()),
                review.isProductPhoto(),
                safe(review.getWorkerFio()),
                dateValue(review.getCreated()),
                dateValue(review.getChanged()),
                dateValue(review.getPublishedDate()),
                review.isPublish(),
                review.isVigul(),
                safe(review.getComment()),
                review.getPrice(),
                safe(review.getUrl()),
                !isBlank(review.getUrlPhoto()) ? safe(review.getUrlPhoto()) : safe(review.getUrl())
        );
    }

    private List<ProductOptionResponse> productOptions() {
        return productService.findAll().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(product -> safe(product.getTitle()), String.CASE_INSENSITIVE_ORDER))
                .map(product -> new ProductOptionResponse(
                        product.getId(),
                        safe(product.getTitle()),
                        Boolean.TRUE.equals(product.getPhoto())
                ))
                .toList();
    }

    private List<OrderProductResponse> orderProductOptions() {
        return productService.findAll().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(product -> safe(product.getTitle()), String.CASE_INSENSITIVE_ORDER))
                .map(product -> new OrderProductResponse(
                        product.getId(),
                        safe(product.getTitle()),
                        product.getPrice(),
                        Boolean.TRUE.equals(product.getPhoto())
                ))
                .toList();
    }

    private List<Integer> amountOptions() {
        return amountService.getAmountDTOList().stream()
                .map(amount -> amount.getAmount())
                .filter(amount -> amount > 0)
                .sorted()
                .toList();
    }

    private List<OptionResponse> categoryOptions() {
        return categoryService.getAllCategories().stream()
                .sorted(Comparator.comparing(CategoryDTO::getCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<OptionResponse> statusOptions() {
        return ManagerBoardStatusCatalog.companyStatuses().stream()
                .filter(status -> !"Все".equals(status))
                .map(companyStatusService::getStatusByTitle)
                .filter(Objects::nonNull)
                .map(status -> new OptionResponse(status.getId(), safe(status.getTitle())))
                .toList();
    }

    private List<OptionResponse> managerOptions(Principal principal, Authentication authentication) {
        List<Manager> managers;
        if (managerPermissionService.hasRole(authentication, "ADMIN")) {
            managers = managerService.getAllManagers();
        } else if (managerPermissionService.hasRole(authentication, "OWNER")) {
            managers = new ArrayList<>(resolveOwnerManagers(principal));
        } else {
            managers = List.of(resolveManager(principal));
        }

        return managers.stream()
                .sorted(Comparator.comparing(manager -> safe(manager.getUser() != null ? manager.getUser().getFio() : "")))
                .map(manager -> new OptionResponse(manager.getId(), safe(manager.getUser() != null ? manager.getUser().getFio() : "Менеджер #" + manager.getId())))
                .toList();
    }

    private List<OptionResponse> workerOptions(CompanyDTO company) {
        if (company.getManager() == null || company.getManager().getManagerId() == null) {
            return List.of();
        }

        return workerService.getAllWorkersByManagerId(company.getManager().getManagerId()).stream()
                .sorted(Comparator.comparing(worker -> safe(worker.getUser() != null ? worker.getUser().getFio() : "")))
                .map(worker -> new OptionResponse(worker.getWorkerId(), safe(worker.getUser() != null ? worker.getUser().getFio() : "Специалист #" + worker.getWorkerId())))
                .toList();
    }

    private List<OptionResponse> currentWorkerOptions(CompanyDTO company) {
        if (company.getWorkers() == null) {
            return List.of();
        }

        return company.getWorkers().stream()
                .sorted(Comparator.comparing(worker -> safe(worker.getUser() != null ? worker.getUser().getFio() : "")))
                .map(worker -> new OptionResponse(worker.getWorkerId(), safe(worker.getUser() != null ? worker.getUser().getFio() : "Специалист #" + worker.getWorkerId())))
                .toList();
    }

    private List<FilialResponse> filialOptions(CompanyDTO company) {
        if (company.getFilials() == null) {
            return List.of();
        }

        return company.getFilials().stream()
                .sorted(Comparator.comparing(filial -> safe(filial.getTitle())))
                .map(filial -> new FilialResponse(
                        filial.getId(),
                        safe(filial.getTitle()),
                        safe(filial.getUrl()),
                        filial.getCity() != null ? filial.getCity().getId() : null,
                        filial.getCity() != null ? safe(filial.getCity().getTitle()) : ""
                ))
                .toList();
    }

    private List<OptionResponse> filialOptions(OrderDTO order) {
        if (order.getCompany() == null || order.getCompany().getFilials() == null) {
            return List.of();
        }

        return order.getCompany().getFilials().stream()
                .sorted(Comparator.comparing(filial -> safe(filial.getTitle())))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<OptionResponse> workerOptions(OrderDTO order) {
        if (order.getCompany() == null || order.getCompany().getWorkers() == null) {
            return List.of();
        }

        return order.getCompany().getWorkers().stream()
                .sorted(Comparator.comparing(worker -> safe(worker.getUser() != null ? worker.getUser().getFio() : "")))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<OptionResponse> cityOptions() {
        return cityService.getAllCities().stream()
                .sorted(Comparator.comparing(CityDTO::getCityTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(city -> new OptionResponse(city.getId(), safe(city.getCityTitle())))
                .toList();
    }

    private OptionResponse option(CategoryDTO category) {
        return category == null ? null : new OptionResponse(category.getId(), safe(category.getCategoryTitle()));
    }

    private OptionResponse option(SubCategoryDTO subCategory) {
        return subCategory == null ? null : new OptionResponse(subCategory.getId(), safe(subCategory.getSubCategoryTitle()));
    }

    private OptionResponse option(CompanyStatusDTO status) {
        return status == null ? null : new OptionResponse(status.getId(), safe(status.getTitle()));
    }

    private OptionResponse option(ManagerDTO manager) {
        if (manager == null) {
            return null;
        }

        String label = manager.getUser() != null ? manager.getUser().getFio() : "Менеджер #" + manager.getManagerId();
        return new OptionResponse(manager.getManagerId(), safe(label));
    }

    private OptionResponse option(WorkerDTO worker) {
        if (worker == null) {
            return null;
        }

        String label = worker.getUser() != null ? worker.getUser().getFio() : "Специалист #" + worker.getWorkerId();
        return new OptionResponse(worker.getWorkerId(), safe(label));
    }

    private OptionResponse option(FilialDTO filial) {
        if (filial == null) {
            return null;
        }

        return new OptionResponse(filial.getId(), safe(filial.getTitle()));
    }

    private Long idOf(CategoryDTO category) {
        return category == null ? null : category.getId();
    }

    private Manager resolveManager(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return managerService.getManagerByUserId(user.getId());
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return userService.findManagersByUserName(principal.getName());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String dateValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String optionLabel(OrderStatusDTO status) {
        return status == null ? "" : safe(status.getTitle());
    }

    private String taskStatusLabel(BadReviewTaskStatus status) {
        if (status == BadReviewTaskStatus.DONE) {
            return "Выполнено";
        }
        if (status == BadReviewTaskStatus.CANCELED) {
            return "Отменено";
        }
        return "В работе";
    }

    private String recoveryTaskStatusLabel(ReviewRecoveryTaskStatus status) {
        if (status == ReviewRecoveryTaskStatus.DONE) {
            return "Восстановлено";
        }
        if (status == ReviewRecoveryTaskStatus.CANCELLED) {
            return "Отменено";
        }
        return "Запланировано";
    }

    private String recoveryBatchStatusLabel(ReviewRecoveryBatchStatus status) {
        if (status == ReviewRecoveryBatchStatus.COMPLETED) {
            return "Все восстановления выполнены";
        }
        if (status == ReviewRecoveryBatchStatus.CLIENT_NOTIFIED) {
            return "Клиент уведомлен";
        }
        if (status == ReviewRecoveryBatchStatus.ARCHIVED) {
            return "В архиве";
        }
        return "В работе";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
