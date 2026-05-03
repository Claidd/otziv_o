package com.hunt.otziv.manager.controller;

import com.hunt.otziv.c_categories.dto.CategoryDTO;
import com.hunt.otziv.c_categories.dto.SubCategoryDTO;
import com.hunt.otziv.c_categories.services.CategoryService;
import com.hunt.otziv.c_categories.services.SubCategoryService;
import com.hunt.otziv.c_cities.dto.CityDTO;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.sevices.CityService;
import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.dto.CompanyStatusDTO;
import com.hunt.otziv.c_companies.dto.FilialDTO;
import com.hunt.otziv.c_companies.dto.CompanyListDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.p_products.dto.OrderDetailsDTO;
import com.hunt.otziv.p_products.dto.OrderDTO;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.dto.OrderStatusDTO;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.services.service.OrderCreationService;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.p_products.services.service.ProductService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.dto.ReviewDTOOne;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.AmountService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.s3.service.S3UploadService;
import com.hunt.otziv.text_generator.service.AutoTextService;
import com.hunt.otziv.u_users.dto.ManagerDTO;
import com.hunt.otziv.u_users.dto.WorkerDTO;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.ManagerService;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
import java.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/manager")
public class ApiManagerBoardController {

    private static final String SECTION_COMPANIES = "companies";
    private static final String SECTION_ORDERS = "orders";
    private static final int MAX_PAGE_SIZE = 50;

    private final CompanyService companyService;
    private final OrderService orderService;
    private final PromoTextService promoTextService;
    private final UserService userService;
    private final ManagerService managerService;
    private final WorkerService workerService;
    private final CategoryService categoryService;
    private final SubCategoryService subCategoryService;
    private final CityService cityService;
    private final CompanyStatusService companyStatusService;
    private final OrderDetailsService orderDetailsService;
    private final OrderCreationService orderCreationService;
    private final ProductService productService;
    private final AmountService amountService;
    private final ReviewService reviewService;
    private final AutoTextService autoTextService;
    private final S3UploadService s3UploadService;
    private final PerformanceMetrics performanceMetrics;

    private final List<String> companyStatuses = List.of(
            "Все",
            "Новая",
            "В работе",
            "Новый заказ",
            "К рассылке",
            "Ожидание",
            "На стопе",
            "Бан"
    );

    private final List<String> orderStatuses = List.of(
            "Все",
            "Новый",
            "В проверку",
            "На проверке",
            "Коррекция",
            "Публикация",
            "Опубликовано",
            "Выставлен счет",
            "Напоминание",
            "Не оплачено",
            "Архив",
            "Оплачено"
    );

    @GetMapping("/board")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public ManagerBoardResponse getBoard(
            @RequestParam(defaultValue = SECTION_COMPANIES) String section,
            @RequestParam(defaultValue = "Все") String status,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) Long companyId,
            Principal principal,
            Authentication authentication
    ) {
        return performanceMetrics.recordEndpoint("manager.board", () -> {
            String normalizedSection = normalizeSection(section);
            String normalizedStatus = normalizeStatus(status);
            String normalizedSortDirection = normalizeSortDirection(sortDirection);
            int safePageNumber = Math.max(pageNumber, 0);
            int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
            String trimmedKeyword = keyword == null ? "" : keyword.trim();

            Page<CompanyListDTO> companies = SECTION_COMPANIES.equals(normalizedSection)
                    ? loadCompanies(principal, authentication, trimmedKeyword, normalizedStatus, safePageNumber, safePageSize, normalizedSortDirection)
                    : emptyCompanyPage(safePageNumber, safePageSize);

            Page<OrderDTOList> orders = SECTION_ORDERS.equals(normalizedSection)
                    ? loadOrders(principal, authentication, trimmedKeyword, normalizedStatus, safePageNumber, safePageSize, companyId, normalizedSortDirection)
                    : emptyOrderPage(safePageNumber, safePageSize);

            return new ManagerBoardResponse(
                    normalizedSection,
                    normalizedStatus,
                    toPageResponse(companies),
                    toPageResponse(orders),
                    companyStatuses,
                    orderStatuses,
                    buildMetrics(principal, authentication),
                    promoTextService.getAllPromoTexts()
            );
        });
    }

    @PostMapping("/companies/{companyId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateCompanyStatus(
            @PathVariable Long companyId,
            @RequestBody StatusChangeRequest request
    ) {
        String status = requireStatus(request);
        boolean updated = companyService.changeStatusForCompany(companyId, status);

        if (!updated) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус компании не изменен");
        }
    }

    @GetMapping("/companies/{companyId}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse getCompanyEdit(
            @PathVariable Long companyId,
            Principal principal,
            Authentication authentication
    ) {
        return buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @GetMapping("/companies/{companyId}/order-create")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyOrderCreateResponse getCompanyOrderCreate(@PathVariable Long companyId) {
        return buildCompanyOrderCreateResponse(companyService.getCompaniesDTOById(companyId));
    }

    @PostMapping("/companies/{companyId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyOrderCreateResultResponse createCompanyOrder(
            @PathVariable Long companyId,
            @RequestBody CompanyOrderCreateRequest request
    ) {
        CompanyDTO company = companyService.getCompaniesDTOById(companyId);
        Product product = validateCompanyOrderCreateRequest(company, request);

        OrderDTO orderDTO = orderService.newOrderDTO(companyId);
        orderDTO.setAmount(request.amount());
        orderDTO.setWorker(WorkerDTO.builder().workerId(request.workerId()).build());
        orderDTO.setFilial(FilialDTO.builder().id(request.filialId()).build());

        try {
            if (!orderCreationService.createNewOrderWithReviews(companyId, request.productId(), orderDTO)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не создан");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не создан: " + exception.getMessage(), exception);
        }

        return new CompanyOrderCreateResultResponse(
                companyId,
                safe(company.getTitle()),
                request.productId(),
                safe(product.getTitle()),
                request.amount()
        );
    }

    @PutMapping("/companies/{companyId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse updateCompany(
            @PathVariable Long companyId,
            @RequestBody CompanyUpdateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        CompanyDTO current = companyService.getCompaniesDTOById(companyId);

        try {
            companyService.updateCompany(toCompanyUpdateDto(current, request, companyId, authentication), toWorkerDTO(request), companyId);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Телефон, email или филиал уже используется", exception);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Компания не сохранена: " + exception.getMessage(), exception);
        }

        return buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @DeleteMapping("/companies/{companyId}/workers/{workerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse deleteCompanyWorker(
            @PathVariable Long companyId,
            @PathVariable Long workerId,
            Principal principal,
            Authentication authentication
    ) {
        if (!companyService.deleteWorkers(companyId, workerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Специалист не удален из компании");
        }

        return buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @DeleteMapping("/companies/{companyId}/filials/{filialId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public CompanyEditResponse deleteCompanyFilial(
            @PathVariable Long companyId,
            @PathVariable Long filialId,
            Principal principal,
            Authentication authentication
    ) {
        if (!companyService.deleteFilial(companyId, filialId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Филиал не удален из компании");
        }

        return buildCompanyEditResponse(companyService.getCompaniesDTOById(companyId), principal, authentication);
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public List<OptionResponse> getSubcategories(@PathVariable Long categoryId) {
        return subCategoryOptions(categoryId);
    }

    @PostMapping("/orders/{orderId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void updateOrderStatus(
            @PathVariable Long orderId,
            @RequestBody StatusChangeRequest request
    ) throws Exception {
        String status = requireStatus(request);
        Order order = orderService.getOrder(orderId);

        if ("Опубликовано".equals(status) || "Оплачено".equals(status)) {
            requireCompleteCounter(order, status);
        }

        boolean updated = orderService.changeStatusForOrder(orderId, status);

        if (!updated) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус заказа не изменен");
        }

        if ("Публикация".equals(status)) {
            updateReviewPublishDates(orderId);
        }
    }

    @GetMapping("/orders/{orderId}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderEditResponse getOrderEdit(
            @PathVariable Long orderId,
            Principal principal,
            Authentication authentication
    ) {
        return buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
    }

    @PutMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderEditResponse updateOrder(
            @PathVariable Long orderId,
            @RequestBody OrderUpdateRequest request,
            Principal principal,
            Authentication authentication
    ) {
        OrderDTO current = orderService.getOrderDTO(orderId);
        OrderDTO update = toOrderUpdateDto(current, request, orderId, authentication);

        try {
            if (hasOnlyWorkerRole(authentication)) {
                orderService.updateOrderToWorker(update, current.getCompany().getId(), orderId);
            } else {
                orderService.updateOrder(update, current.getCompany().getId(), orderId);
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заказ не сохранен: " + exception.getMessage(), exception);
        }

        return buildOrderEditResponse(orderService.getOrderDTO(orderId), principal, authentication);
    }

    @DeleteMapping("/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public void deleteOrder(
            @PathVariable Long orderId,
            Principal principal
    ) {
        if (!orderService.deleteOrder(orderId, principal)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Заказ не удален: недостаточно прав или статус не позволяет удаление");
        }
    }

    @GetMapping("/orders/{orderId}/details")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse getOrderDetails(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse addOrderReview(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        if (!orderService.addNewReview(orderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не добавлен");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/change-text")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse changeOrderReviewText(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        if (!autoTextService.changeReviewText(reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не изменен");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse updateOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewEditorUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || isBlank(request.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }

        ReviewDTO current = requireReviewForOrder(orderId, reviewId);
        if (request.productId() != null && productService.findById(request.productId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Продукт не найден");
        }

        Product product = request.productId() != null ? Product.builder().id(request.productId()).build() : null;

        ReviewDTO reviewDTO = ReviewDTO.builder()
                .id(reviewId)
                .text(normalize(request.text()))
                .answer(normalize(request.answer()))
                .comment(normalize(request.comment()))
                .created(firstValue(request.created(), current.getCreated()))
                .changed(firstValue(request.changed(), current.getChanged()))
                .publishedDate(request.publishedDate())
                .publish(Boolean.TRUE.equals(request.publish()))
                .vigul(Boolean.TRUE.equals(request.vigul()))
                .botName(normalize(request.botName()))
                .botPassword(normalize(request.botPassword()))
                .orderDetailsId(current.getOrderDetailsId())
                .orderDetails(current.getOrderDetails())
                .product(product)
                .url(normalize(request.url()))
                .build();

        try {
            reviewService.updateReview(primaryReviewRole(authentication), reviewDTO, reviewId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не сохранен: " + exception.getMessage(), exception);
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/photo")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse uploadOrderReviewPhoto(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не выбран");
        }

        requireReviewForOrder(orderId, reviewId);
        Review review = reviewService.getReviewById(reviewId);
        if (review == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }

        String newUrl = s3UploadService.uploadFile(file, "reviews", review.getUrl(), review.getId());
        review.setUrl(newUrl);
        reviewService.save(review);

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @DeleteMapping("/orders/{orderId}/reviews/{reviewId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')")
    public OrderDetailsResponse deleteOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        requireReviewForOrder(orderId, reviewId);
        if (!orderService.deleteNewReview(orderId, reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не удален");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/text")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewText(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewTextUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || isBlank(request.text())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст отзыва не указан");
        }

        if (!reviewService.updateReviewText(orderId, reviewId, request.text())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/answer")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewAnswer(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewAnswerUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.answer() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ на отзыв не указан");
        }

        if (!reviewService.updateReviewAnswer(orderId, reviewId, request.answer())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/reviews/{reviewId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderReviewNote(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @RequestBody ReviewNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.comment() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка отзыва не указана");
        }

        if (!reviewService.updateReviewNote(orderId, reviewId, request.comment())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderNote(
            @PathVariable Long orderId,
            @RequestBody OrderNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.orderComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка заказа не указана");
        }

        Order order = orderService.getOrder(orderId);
        order.setZametka(request.orderComments());
        orderService.save(order);

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PutMapping("/orders/{orderId}/company-note")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse updateOrderCompanyNote(
            @PathVariable Long orderId,
            @RequestBody CompanyNoteUpdateRequest request,
            Authentication authentication
    ) {
        if (request == null || request.companyComments() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Заметка компании не указана");
        }

        Order order = orderService.getOrder(orderId);
        Company company = order.getCompany();
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания заказа не найдена");
        }

        company.setCommentsCompany(request.companyComments());
        companyService.save(company);

        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/change-bot")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse changeOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        reviewService.changeBot(reviewId);
        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/bots/{botId}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse deactivateOrderReviewBot(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            @PathVariable Long botId,
            Authentication authentication
    ) {
        reviewService.deActivateAndChangeBot(reviewId, botId);
        return buildOrderDetailsResponse(orderId, authentication);
    }

    @PostMapping("/orders/{orderId}/reviews/{reviewId}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    public OrderDetailsResponse publishOrderReview(
            @PathVariable Long orderId,
            @PathVariable Long reviewId,
            Authentication authentication
    ) throws Exception {
        if (!orderService.changeStatusAndOrderCounter(reviewId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отзыв не отмечен опубликованным");
        }

        return buildOrderDetailsResponse(orderId, authentication);
    }

    private Page<CompanyListDTO> loadCompanies(
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            String sortDirection
    ) {
        if (hasRole(authentication, "ADMIN")) {
            return "Все".equals(status)
                    ? companyService.getAllCompaniesDTOList(keyword, pageNumber, pageSize, sortDirection)
                    : companyService.getAllCompaniesDTOListToList(keyword, status, pageNumber, pageSize, sortDirection);
        }

        if (hasRole(authentication, "OWNER")) {
            return "Все".equals(status)
                    ? companyService.getAllCompaniesDTOListOwner(principal, keyword, pageNumber, pageSize, sortDirection)
                    : companyService.getAllCompaniesDtoToOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? companyService.getAllOrderDTOAndKeywordByManager(principal, keyword, pageNumber, pageSize, sortDirection)
                : companyService.getAllCompanyDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    private Page<OrderDTOList> loadOrders(
            Principal principal,
            Authentication authentication,
            String keyword,
            String status,
            int pageNumber,
            int pageSize,
            Long companyId,
            String sortDirection
    ) {
        if (companyId != null) {
            return orderService.getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize, sortDirection);
        }

        if (hasRole(authentication, "ADMIN")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize, sortDirection);
        }

        if (hasRole(authentication, "OWNER")) {
            return "Все".equals(status)
                    ? orderService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                    : orderService.getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
        }

        return "Все".equals(status)
                ? orderService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, sortDirection)
                : orderService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    private CompanyEditResponse buildCompanyEditResponse(
            CompanyDTO company,
            Principal principal,
            Authentication authentication
    ) {
        Long categoryId = idOf(company.getCategoryCompany());
        return new CompanyEditResponse(
                company.getId(),
                safe(company.getTitle()),
                safe(company.getUrlChat()),
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
                hasRole(authentication, "ADMIN")
        );
    }

    private OrderEditResponse buildOrderEditResponse(
            OrderDTO order,
            Principal principal,
            Authentication authentication
    ) {
        boolean adminOrOwner = hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER");
        boolean managerCanDelete = hasRole(authentication, "MANAGER") && optionLabel(order.getStatus()).equals("Новый");
        OptionResponse currentManager = option(order.getManager());
        List<OptionResponse> managers = hasOnlyWorkerRole(authentication)
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
                adminOrOwner || managerCanDelete
        );
    }

    private OrderDetailsResponse buildOrderDetailsResponse(Long orderId, Authentication authentication) {
        OrderDTO order = orderService.getOrderDTO(orderId);
        List<ReviewDTOOne> reviews = reviewService.getReviewsAllByOrderId(orderId);
        ReviewDTOOne firstReview = reviews.isEmpty() ? null : reviews.get(0);

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
                firstReview != null && !isBlank(firstReview.getOrderComments())
                        ? safe(firstReview.getOrderComments())
                        : safe(order.getOrderComments()),
                firstReview != null && !isBlank(firstReview.getCommentCompany())
                        ? safe(firstReview.getCommentCompany())
                        : safe(order.getCommentsCompany()),
                dateValue(order.getCreated()),
                dateValue(order.getChanged()),
                reviews.stream().map(this::toReviewDetailsResponse).toList(),
                productOptions(),
                hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER", "WORKER"),
                hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"),
                hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"),
                hasAnyRole(authentication, "ADMIN", "OWNER"),
                hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"),
                hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER", "WORKER")
        );
    }

    private CompanyOrderCreateResponse buildCompanyOrderCreateResponse(CompanyDTO company) {
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

    private Product validateCompanyOrderCreateRequest(CompanyDTO company, CompanyOrderCreateRequest request) {
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

    private CompanyDTO toCompanyUpdateDto(
            CompanyDTO current,
            CompanyUpdateRequest request,
            Long companyId,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные компании не переданы");
        }

        if (isBlank(request.title()) || isBlank(request.telephone()) || isBlank(request.urlChat()) || isBlank(request.city())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Название, телефон, город и ссылка на чат обязательны");
        }

        Long newFilialCityId = request.newFilialCityId();
        String newFilialTitle = normalize(request.newFilialTitle());
        String newFilialUrl = normalize(request.newFilialUrl());
        if (!newFilialTitle.isEmpty() && (newFilialCityId == null || newFilialUrl.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Для нового филиала нужны город, адрес и ссылка 2ГИС");
        }

        Long managerId = hasRole(authentication, "ADMIN")
                ? firstId(request.managerId(), idOf(current.getManager()))
                : firstId(idOf(current.getManager()), null);

        return CompanyDTO.builder()
                .id(companyId)
                .title(normalize(request.title()))
                .urlChat(normalize(request.urlChat()))
                .telephone(normalize(request.telephone()))
                .city(normalize(request.city()))
                .email(blankToNull(request.email()))
                .commentsCompany(normalize(request.commentsCompany()))
                .active(Boolean.TRUE.equals(request.active()))
                .status(CompanyStatusDTO.builder().id(firstId(request.statusId(), idOf(current.getStatus()))).build())
                .categoryCompany(CategoryDTO.builder().id(firstId(request.categoryId(), idOf(current.getCategoryCompany()))).build())
                .subCategory(SubCategoryDTO.builder().id(firstId(request.subCategoryId(), idOf(current.getSubCategory()))).build())
                .manager(ManagerDTO.builder().managerId(managerId).build())
                .filial(FilialDTO.builder()
                        .title(newFilialTitle)
                        .url(newFilialUrl)
                        .city(newFilialCityId == null ? null : City.builder().id(newFilialCityId).build())
                        .build())
                .build();
    }

    private OrderDTO toOrderUpdateDto(
            OrderDTO current,
            OrderUpdateRequest request,
            Long orderId,
            Authentication authentication
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные заказа не переданы");
        }

        if (request.counter() != null && request.counter() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Счетчик не может быть меньше нуля");
        }

        boolean canComplete = hasRole(authentication, "ADMIN") || hasRole(authentication, "OWNER");

        return OrderDTO.builder()
                .id(orderId)
                .filial(FilialDTO.builder().id(firstId(request.filialId(), idOf(current.getFilial()))).build())
                .worker(WorkerDTO.builder().workerId(firstId(request.workerId(), idOf(current.getWorker()))).build())
                .manager(ManagerDTO.builder().managerId(firstId(request.managerId(), idOf(current.getManager()))).build())
                .counter(request.counter() != null ? request.counter() : current.getCounter())
                .orderComments(normalize(request.orderComments()))
                .commentsCompany(normalize(request.commentsCompany()))
                .complete(canComplete ? Boolean.TRUE.equals(request.complete()) : current.isComplete())
                .build();
    }

    private WorkerDTO toWorkerDTO(CompanyUpdateRequest request) {
        Long workerId = request == null || request.newWorkerId() == null ? 0L : request.newWorkerId();
        return WorkerDTO.builder().workerId(workerId).build();
    }

    private List<OptionResponse> categoryOptions() {
        return categoryService.getAllCategories().stream()
                .sorted(Comparator.comparing(CategoryDTO::getCategoryTitle, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(this::option)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<OptionResponse> subCategoryOptions(Long categoryId) {
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

    private List<OptionResponse> statusOptions() {
        return companyStatuses.stream()
                .filter(status -> !"Все".equals(status))
                .map(companyStatusService::getStatusByTitle)
                .filter(Objects::nonNull)
                .map(status -> new OptionResponse(status.getId(), safe(status.getTitle())))
                .toList();
    }

    private List<OptionResponse> managerOptions(Principal principal, Authentication authentication) {
        List<Manager> managers;
        if (hasRole(authentication, "ADMIN")) {
            managers = managerService.getAllManagers();
        } else if (hasRole(authentication, "OWNER")) {
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
        if (company.getManager() == null || company.getManager().getUser() == null || company.getManager().getUser().getWorkers() == null) {
            return List.of();
        }

        return workerService.getAllWorkersByManagerId(company.getManager().getUser().getWorkers()).stream()
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

    private Long idOf(SubCategoryDTO subCategory) {
        return subCategory == null ? null : subCategory.getId();
    }

    private Long idOf(CompanyStatusDTO status) {
        return status == null ? null : status.getId();
    }

    private Long idOf(ManagerDTO manager) {
        return manager == null ? null : manager.getManagerId();
    }

    private Long idOf(WorkerDTO worker) {
        return worker == null ? null : worker.getWorkerId();
    }

    private Long idOf(FilialDTO filial) {
        return filial == null ? null : filial.getId();
    }

    private Long firstId(Long value, Long fallback) {
        return value != null ? value : fallback != null ? fallback : 0L;
    }

    private <T> T firstValue(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private String dateValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String optionLabel(OrderStatusDTO status) {
        return status == null ? "" : safe(status.getTitle());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private List<ManagerMetricResponse> buildMetrics(Principal principal, Authentication authentication) {
        List<ManagerMetricResponse> metrics = new ArrayList<>();
        Map<String, Integer> companyCounts = countCompanyMetrics(principal, authentication);
        Map<String, Integer> orderCounts = countOrderMetrics(principal, authentication);

        metrics.add(companyMetric(companyCounts, "Новые", "Новая", "fiber_new", "yellow"));
        metrics.add(companyMetric(companyCounts, "В работе", "В работе", "badge", "green"));
        metrics.add(companyMetric(companyCounts, "Новый заказ", "Новый заказ", "business_center", "teal"));
        metrics.add(companyMetric(companyCounts, "К рассылке", "К рассылке", "campaign", "blue"));
        metrics.add(companyMetric(companyCounts, "Ожидание", "Ожидание", "hourglass_top", "pink"));
        metrics.add(companyMetric(companyCounts, "На стопе", "На стопе", "pause_circle", "gray"));
        metrics.add(companyMetric(companyCounts, "Бан", "Бан", "block", "gray"));
        metrics.add(companyMetric(companyCounts, "Всего", "Все", "dashboard", "blue"));

        metrics.add(orderMetric(orderCounts, "Новые", "Новый", "fiber_new", "yellow"));
        metrics.add(orderMetric(orderCounts, "В проверку", "В проверку", "fact_check", "blue"));
        metrics.add(orderMetric(orderCounts, "На проверке", "На проверке", "manage_search", "teal"));
        metrics.add(orderMetric(orderCounts, "Коррекция", "Коррекция", "build_circle", "pink"));
        metrics.add(orderMetric(orderCounts, "Публикация", "Публикация", "published_with_changes", "green"));
        metrics.add(orderMetric(orderCounts, "Опубликовано", "Опубликовано", "task_alt", "green"));
        metrics.add(orderMetric(orderCounts, "Выставлен счет", "Выставлен счет", "receipt_long", "blue"));
        metrics.add(orderMetric(orderCounts, "Напоминание", "Напоминание", "notifications_active", "pink"));
        metrics.add(orderMetric(orderCounts, "Не оплачено", "Не оплачено", "money_off", "gray"));
        metrics.add(orderMetric(orderCounts, "Архив", "Архив", "archive", "gray"));
        metrics.add(orderMetric(orderCounts, "Оплачено", "Оплачено", "payments", "teal"));
        metrics.add(orderMetric(orderCounts, "Всего", "Все", "dashboard", "blue"));

        return metrics;
    }

    private ManagerMetricResponse companyMetric(
            Map<String, Integer> counts,
            String label,
            String status,
            String icon,
            String tone
    ) {
        return new ManagerMetricResponse(
                label,
                countStatus(counts, status),
                icon,
                tone,
                SECTION_COMPANIES,
                status
        );
    }

    private ManagerMetricResponse orderMetric(
            Map<String, Integer> counts,
            String label,
            String status,
            String icon,
            String tone
    ) {
        return new ManagerMetricResponse(
                label,
                countStatus(counts, status),
                icon,
                tone,
                SECTION_ORDERS,
                status
        );
    }

    private Map<String, Integer> countCompanyMetrics(Principal principal, Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return companyService.countCompaniesByStatus();
        }
        if (hasRole(authentication, "OWNER")) {
            return companyService.countCompaniesByStatusToOwner(resolveOwnerManagers(principal));
        }
        return companyService.countCompaniesByStatusToManager(resolveManager(principal));
    }

    private Map<String, Integer> countOrderMetrics(Principal principal, Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return orderService.countOrdersByStatus();
        }
        if (hasRole(authentication, "OWNER")) {
            return orderService.countOrdersByStatusToOwner(resolveOwnerManagers(principal));
        }
        return orderService.countOrdersByStatusToManager(resolveManager(principal));
    }

    private int countStatus(Map<String, Integer> counts, String status) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        if ("Все".equals(status)) {
            long total = counts.values().stream()
                    .mapToLong(Integer::longValue)
                    .sum();
            return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        }
        return counts.getOrDefault(status, 0);
    }

    private Manager resolveManager(Principal principal) {
        User user = userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        return managerService.getManagerByUserId(user.getId());
    }

    private Set<Manager> resolveOwnerManagers(Principal principal) {
        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"))
                .getManagers();
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        for (String role : roles) {
            if (hasRole(authentication, role)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasOnlyWorkerRole(Authentication authentication) {
        return hasRole(authentication, "WORKER") && !hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER");
    }

    private String primaryReviewRole(Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return "ROLE_ADMIN";
        }

        if (hasRole(authentication, "OWNER")) {
            return "ROLE_OWNER";
        }

        if (hasRole(authentication, "MANAGER")) {
            return "ROLE_MANAGER";
        }

        if (hasRole(authentication, "WORKER")) {
            return "ROLE_WORKER";
        }

        return "anonymous";
    }

    private ReviewDTO requireReviewForOrder(Long orderId, Long reviewId) {
        ReviewDTO review = reviewService.getReviewDTOById(reviewId);
        if (review == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }

        Long reviewOrderId = review.getOrderDetails() != null && review.getOrderDetails().getOrder() != null
                ? review.getOrderDetails().getOrder().getId()
                : null;
        if (!Objects.equals(orderId, reviewOrderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден в этом заказе");
        }

        return review;
    }

    private String requireStatus(StatusChangeRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Статус не указан");
        }
        return request.status().trim();
    }

    private void requireCompleteCounter(Order order, String status) {
        if (order.getAmount() > order.getCounter()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя перевести заказ в статус \"" + status + "\": опубликовано "
                            + order.getCounter() + " из " + order.getAmount() + " отзывов"
            );
        }
    }

    private void updateReviewPublishDates(Long orderId) {
        Order order = orderService.getOrder(orderId);
        if (order.getDetails() == null || order.getDetails().isEmpty()) {
            return;
        }

        OrderDetailsDTO orderDetails = orderDetailsService.getOrderDetailDTOById(order.getDetails().getFirst().getId());
        reviewService.updateOrderDetailAndReviewAndPublishDate(orderDetails);
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "Все" : status.trim();
    }

    private String normalizeSection(String section) {
        String normalized = section == null ? SECTION_COMPANIES : section.toLowerCase(Locale.ROOT).trim();
        return SECTION_ORDERS.equals(normalized) ? SECTION_ORDERS : SECTION_COMPANIES;
    }

    private String normalizeSortDirection(String sortDirection) {
        return "asc".equalsIgnoreCase(sortDirection) ? "asc" : "desc";
    }

    private Page<CompanyListDTO> emptyCompanyPage(int pageNumber, int pageSize) {
        return new PageImpl<>(List.of(), PageRequest.of(pageNumber, pageSize), 0);
    }

    private Page<OrderDTOList> emptyOrderPage(int pageNumber, int pageSize) {
        return new PageImpl<>(List.of(), PageRequest.of(pageNumber, pageSize), 0);
    }

    private <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    public record ManagerBoardResponse(
            String section,
            String status,
            PageResponse<CompanyListDTO> companies,
            PageResponse<OrderDTOList> orders,
            List<String> companyStatuses,
            List<String> orderStatuses,
            List<ManagerMetricResponse> metrics,
            List<String> promoTexts
    ) {
    }

    public record PageResponse<T>(
            List<T> content,
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
    }

    public record ManagerMetricResponse(
            String label,
            int value,
            String icon,
            String tone,
            String section,
            String status
    ) {
    }

    public record CompanyEditResponse(
            Long id,
            String title,
            String urlChat,
            String telephone,
            String city,
            String email,
            String commentsCompany,
            boolean active,
            String createDate,
            String updateStatus,
            String dateNewTry,
            OptionResponse status,
            OptionResponse category,
            OptionResponse subCategory,
            OptionResponse manager,
            List<OptionResponse> categories,
            List<OptionResponse> subCategories,
            List<OptionResponse> statuses,
            List<OptionResponse> managers,
            List<OptionResponse> workers,
            List<OptionResponse> currentWorkers,
            List<FilialResponse> filials,
            List<OptionResponse> cities,
            boolean canChangeManager
    ) {
    }

    public record CompanyOrderCreateResponse(
            Long companyId,
            String companyTitle,
            List<OrderProductResponse> products,
            List<Integer> amounts,
            List<OptionResponse> workers,
            List<FilialResponse> filials,
            Long defaultProductId,
            Integer defaultAmount,
            Long defaultWorkerId,
            Long defaultFilialId
    ) {
    }

    public record CompanyOrderCreateResultResponse(
            Long companyId,
            String companyTitle,
            Long productId,
            String productTitle,
            Integer amount
    ) {
    }

    public record OrderProductResponse(
            Long id,
            String label,
            BigDecimal price,
            boolean photo
    ) {
    }

    public record OrderEditResponse(
            Long id,
            Long companyId,
            String companyTitle,
            String status,
            BigDecimal sum,
            Integer amount,
            Integer counter,
            String created,
            String changed,
            String payDay,
            String orderComments,
            String commentsCompany,
            boolean complete,
            OptionResponse filial,
            OptionResponse manager,
            OptionResponse worker,
            List<OptionResponse> filials,
            List<OptionResponse> managers,
            List<OptionResponse> workers,
            boolean canComplete,
            boolean canDelete
    ) {
    }

    public record OrderDetailsResponse(
            Long orderId,
            Long companyId,
            UUID orderDetailsId,
            String title,
            String companyTitle,
            String productTitle,
            String status,
            Integer amount,
            Integer counter,
            BigDecimal sum,
            String orderComments,
            String companyComments,
            String created,
            String changed,
            List<ReviewDetailsResponse> reviews,
            List<ProductOptionResponse> products,
            boolean canEditReviews,
            boolean canSendToCheck,
            boolean canEditReviewDates,
            boolean canEditReviewPublish,
            boolean canEditReviewVigul,
            boolean canDeleteReviews
    ) {
    }

    public record ReviewDetailsResponse(
            Long id,
            Long companyId,
            UUID orderDetailsId,
            Long orderId,
            String text,
            String answer,
            String category,
            String subCategory,
            Long botId,
            String botFio,
            String botLogin,
            String botPassword,
            int botCounter,
            String companyTitle,
            String commentCompany,
            String orderComments,
            String filialCity,
            String filialTitle,
            String filialUrl,
            Long productId,
            String productTitle,
            boolean productPhoto,
            String workerFio,
            String created,
            String changed,
            String publishedDate,
            boolean publish,
            boolean vigul,
            String comment,
            BigDecimal price,
            String url,
            String urlPhoto
    ) {
    }

    public record OptionResponse(Long id, String label) {
    }

    public record ProductOptionResponse(
            Long id,
            String label,
            boolean photo
    ) {
    }

    public record FilialResponse(
            Long id,
            String title,
            String url,
            Long cityId,
            String city
    ) {
    }

    public record CompanyUpdateRequest(
            String title,
            String urlChat,
            String telephone,
            String city,
            String email,
            Long categoryId,
            Long subCategoryId,
            Long statusId,
            Long managerId,
            String commentsCompany,
            Boolean active,
            Long newWorkerId,
            Long newFilialCityId,
            String newFilialTitle,
            String newFilialUrl
    ) {
    }

    public record CompanyOrderCreateRequest(
            Long productId,
            Integer amount,
            Long workerId,
            Long filialId
    ) {
    }

    public record OrderUpdateRequest(
            Long filialId,
            Long workerId,
            Long managerId,
            Integer counter,
            String orderComments,
            String commentsCompany,
            Boolean complete
    ) {
    }

    public record StatusChangeRequest(String status) {
    }

    public record ReviewEditorUpdateRequest(
            String text,
            String answer,
            String comment,
            LocalDate created,
            LocalDate changed,
            LocalDate publishedDate,
            Boolean publish,
            Boolean vigul,
            String botName,
            String botPassword,
            Long productId,
            String url
    ) {
    }

    public record ReviewTextUpdateRequest(String text) {
    }

    public record ReviewAnswerUpdateRequest(String answer) {
    }

    public record ReviewNoteUpdateRequest(String comment) {
    }

    public record OrderNoteUpdateRequest(String orderComments) {
    }

    public record CompanyNoteUpdateRequest(String companyComments) {
    }
}
