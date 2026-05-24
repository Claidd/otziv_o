package com.hunt.otziv.p_products.services;

import com.hunt.otziv.c_companies.dto.CompanyDTO;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.deletion.OrderDeletionService;
import com.hunt.otziv.p_products.dto.*;
import com.hunt.otziv.p_products.editing.OrderEditService;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.OrderReviewMutationService;
import com.hunt.otziv.p_products.services.service.*;
import com.hunt.otziv.p_products.statistics.OrderStatisticsService;
import com.hunt.otziv.p_products.status.OrderBotLifecycleService;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.r_review.dto.ReviewDTO;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderDetailsRepository orderDetailsRepository;
    private final CompanyService companyService;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final OrderStatusService orderStatusService;
    private final ReviewArchiveService reviewArchiveService;
    private final CompanyStatusService companyStatusService;
    private final OrderStatusCheckerService orderStatusCheckerService;
    private final OrderDtoMapper orderDtoMapper;
    private final OrderBoardQueryService orderBoardQueryService;
    private final OrderStatisticsService orderStatisticsService;
    private final OrderDeletionService orderDeletionService;
    private final OrderEditService orderEditService;
    private final OrderBotLifecycleService orderBotLifecycleService;
    private final OrderStatusTransitionService orderStatusTransitionService;
    private final OrderReviewMutationService orderReviewMutationService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final AppSettingService appSettingService;

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String OWNER = "ROLE_OWNER";
    public static final String MANAGER = "ROLE_MANAGER";

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_TO_CHECK = "В проверку";
    public static final String STATUS_IN_CHECK = "На проверке";
    public static final String STATUS_CORRECTION = "Коррекция";
    public static final String STATUS_TO_PUBLISH = "Публикация";
    public static final String STATUS_PAYMENT = "Оплачено";
    public static final String STATUS_PUBLIC = "Опубликовано";
    public static final String STATUS_TO_PAY = "Выставлен счет";
    public static final String STATUS_NOT_PAID = "Не оплачено";
    public static final String STATUS_ARCHIVE = "Архив";

    public static final String STATUS_COMPANY_IN_WORK = "В работе";
    public static final String STATUS_COMPANY_IN_STOP = "На стопе";
    public static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";

    // =========================================================================================================
    // ======================================== ВЗЯТЬ ЗАКАЗЫ ПО РОЛЯМ ==========================================
    // =========================================================================================================

    @Override
    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOCompanyIdAndKeyword(Long companyId, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOCompanyIdAndKeyword(companyId, keyword, pageNumber, pageSize, sortDirection);
    }

    @Override
    public List<OrderDTO> getAllOrderDTO() {
        return orderDtoMapper.toOrderDTOList(orderRepository.findAll());
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeyword(String keyword, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeyword(keyword, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordAndStatus(String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordAndStatus(keyword, status, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManagerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByManagerAll(principal, keyword, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByManager(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByManager(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwnerAll(Principal principal, String keyword, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByOwnerAll(principal, keyword, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByOwner(Principal principal, String keyword, String status, int pageNumber, int pageSize, String sortDirection) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByOwner(principal, keyword, status, pageNumber, pageSize, sortDirection);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorkerAll(Principal principal, String keyword, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByWorkerAll(principal, keyword, pageNumber, pageSize);
    }

    @Override
    public Page<OrderDTOList> getAllOrderDTOAndKeywordByWorker(Principal principal, String keyword, String status, int pageNumber, int pageSize) {
        return orderBoardQueryService.getAllOrderDTOAndKeywordByWorker(principal, keyword, status, pageNumber, pageSize);
    }

    @Override
    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(List<Long> workerIds, String status) {
        return orderStatisticsService.countOrdersByWorkerIdsAndStatus(workerIds, status);
    }

    public Map<Long, Integer> countOrdersByWorkerIdsAndStatus(Collection<Long> workerIds, String status) {
        return orderStatisticsService.countOrdersByWorkerIdsAndStatus(workerIds, status);
    }

    @Override
    public Order getOrder(Long orderId) {
        return orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Заказ № '%d' не найден", orderId)));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDTO getOrderDTO(Long orderId) {
        Order order = orderRepository.findByIdForOrderDto(orderId)
                .orElseThrow(() -> new UsernameNotFoundException(String.format("Заказ № '%d' не найден", orderId)));
        orderRepository.findByIdWithCompanyWorkers(orderId);
        orderRepository.findByIdWithCompanyFilials(orderId);
        orderDetailsRepository.findAllByOrderIdForOrderDto(orderId);
        return orderDtoMapper.toOrderDTO(order);
    }

    // =========================================================================================================
    // ======================================== СОЗДАНИЕ НОВЫХ ОТЗЫВОВ ==========================================
    // =========================================================================================================

    @Override
    public OrderDTO newOrderDTO(Long id) {
        CompanyDTO companyDTO = companyService.getCompaniesDTOById(id);
        OrderDTO orderDTO = new OrderDTO();
        orderDTO.setCompany(companyDTO);
        orderDTO.setWorkers(companyDTO.getWorkers());
        orderDTO.setManager(companyDTO.getManager());
        orderDTO.setStatus(orderStatusService.getOrderStatusDTOByTitle(STATUS_NEW));
        orderDTO.setFilial(companyDTO.getFilial());
        return orderDTO;
    }

    @Override
    @Transactional
    public boolean addNewReview(Long orderId) {
        return orderReviewMutationService.addNewReview(orderId);
    }

    @Override
    @Transactional
    public boolean deleteNewReview(Long orderId, Long reviewId) {
        return orderReviewMutationService.deleteNewReview(orderId, reviewId);
    }

    // =========================================================================================================
    // ======================================== ЗАКАЗ UPDATE ====================================================
    // =========================================================================================================

    @Override
    @Transactional
    public void updateOrder(OrderDTO orderDTO, Long companyId, Long orderId) {
        orderEditService.updateOrder(orderDTO, companyId, orderId);
    }

    @Override
    @Transactional
    public void updateOrderToWorker(OrderDTO orderDTO, Long companyId, Long orderId) {
        orderEditService.updateOrderToWorker(orderDTO, companyId, orderId);
    }

    // =========================================================================================================
    // ======================================== УДАЛЕНИЕ ЗАКАЗА =================================================
    // =========================================================================================================

    @Override
    @Transactional
    public boolean deleteOrder(Long orderId, Principal principal) {
        return orderDeletionService.deleteOrder(orderId, principal);
    }

    // =========================================================================================================
    // ======================================== СЧЕТЧИКИ ========================================================
    // =========================================================================================================

    @Override
    public int getAllOrderDTOByStatus(String status) {
        return orderStatisticsService.getAllOrderDTOByStatus(status);
    }

    @Override
    public Map<String, Integer> countOrdersByStatus() {
        return orderStatisticsService.countOrdersByStatus();
    }

    @Override
    public Map<String, Integer> countActionableOrdersByStatus() {
        return orderStatisticsService.countActionableOrdersByStatus();
    }

    @Override
    public Map<String, Integer> countOrdersByStatusToManager(Manager manager) {
        return orderStatisticsService.countOrdersByStatusToManager(manager);
    }

    @Override
    public Map<String, Integer> countActionableOrdersByStatusToManager(Manager manager) {
        return orderStatisticsService.countActionableOrdersByStatusToManager(manager);
    }

    @Override
    public Map<String, Integer> countOrdersByStatusToOwner(Set<Manager> managerList) {
        return orderStatisticsService.countOrdersByStatusToOwner(managerList);
    }

    @Override
    public Map<String, Integer> countActionableOrdersByStatusToOwner(Set<Manager> managerList) {
        return orderStatisticsService.countActionableOrdersByStatusToOwner(managerList);
    }

    @Override
    public Map<String, Integer> countOrdersByStatusToWorker(Worker worker) {
        return orderStatisticsService.countOrdersByStatusToWorker(worker);
    }

    @Override
    public Map<String, Integer> countActionableOrdersByStatusToWorker(Worker worker) {
        return orderStatisticsService.countActionableOrdersByStatusToWorker(worker);
    }

    @Override
    public Map<String, Integer> countActionableOrdersByStatusToWorkerChangedOnOrBefore(
            Worker worker,
            Set<String> statuses,
            LocalDate cutoff
    ) {
        return orderStatisticsService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(worker, statuses, cutoff);
    }

    @Override
    public int countAllOrders() {
        return orderStatisticsService.countAllOrders();
    }

    @Override
    public int countAllOrdersToManager(Manager manager) {
        return orderStatisticsService.countAllOrdersToManager(manager);
    }

    @Override
    public int countAllOrdersToOwner(Set<Manager> managerList) {
        return orderStatisticsService.countAllOrdersToOwner(managerList);
    }

    @Override
    public int countOrdersByWorker(Worker worker) {
        return orderStatisticsService.countOrdersByWorker(worker);
    }

    @Override
    public int getAllOrderDTOByStatusToManager(Manager manager, String status) {
        return orderStatisticsService.getAllOrderDTOByStatusToManager(manager, status);
    }

    @Override
    public int getAllOrderDTOByStatusToOwner(Set<Manager> managerList, String status) {
        return orderStatisticsService.getAllOrderDTOByStatusToOwner(managerList, status);
    }

    public Review saveReviews(Review review, Worker newWorker) {
        if (review == null) {
            return null;
        }
        review.setWorker(newWorker);
        return reviewService.save(review);
    }

    // =========================================================================================================
    // ======================================== СМЕНА СТАТУСА ЗАКАЗА ============================================
    // =========================================================================================================

    @Override
    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title) throws Exception {
        return orderStatusTransitionService.changeStatusForOrder(orderID, title);
    }

    @Transactional
    protected void saveReviewsToArchive(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }

        for (Review review : reviews) {
            if (review != null && review.getId() != null) {
                reviewArchiveService.saveNewReviewArchive(review.getId());
            }
        }
    }

    @Override
    @Transactional
    public Company checkStatusToCompany(Company company) {
        if (company == null) {
            return null;
        }

        int result = 0;
        Collection<Order> orders = company.getOrderList();
        if (orders != null) {
            for (Order order : orders) {
                if (order != null && !order.isComplete()) {
                    result = 1;
                    break;
                }
            }
        }

        if (result == 0) {
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        }

        return company;
    }

    @Override
    @Transactional
    public boolean changeStatusAndOrderCounter(Long reviewId) throws Exception {
        try {
            ReviewPublicationTarget target = validateAndRetrievePublicationTarget(reviewId);
            Review review = target.review();
            Order order = target.order();

            log.info("Достали отзыв id={} для компании: {}", reviewId,
                    order.getCompany() != null ? order.getCompany().getTitle() : "null");

            if (reviewRepository.existsPublishedByTextExcludingReviewId(review.getText(), review.getId())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Такой текст уже публиковался ранее. Измените текст отзыва перед публикацией."
                );
            }

            if (review.getBot() != null) {
                orderBotLifecycleService.updateBotCounterAndStatus(review.getBot());
                log.info("Увеличили кол-во публикаций у бота");
            } else {
                log.warn("У отзыва id={} нет бота, счетчик бота не обновлялся", reviewId);
            }

            review.setPublish(true);
            reviewRepository.save(review);
            log.info("Сохранили отзыв, публикация установлена в true");
            reviewArchiveService.saveNewReviewArchive(review.getId());
            log.info("Сохранили опубликованный отзыв в архив текстов");

            int actualPublished = countPublishedReviews(order);
            log.info("Фактическое количество опубликованных отзывов: {}", actualPublished);

            orderStatusCheckerService.validateCounterConsistency(order, actualPublished);
            log.info("Счётчик заказа после синхронизации: {}", order.getCounter());
            notifyClientAboutPublishedReviewProgress(order, actualPublished);
            orderStatusCheckerService.checkAndMarkOrderCompleted(order);

            return true;
        } catch (ResponseStatusException e) {
            log.warn("Публикация отзыва id={} отклонена: {}", reviewId, e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("Ошибка при смене статуса отзыва id={}", reviewId, e);
            throw e;
        }
    }

    private void notifyClientAboutPublishedReviewProgress(Order order, int actualPublished) {
        try {
            if (!shouldSendPublishedReviewProgress(order, actualPublished)) {
                return;
            }

            String clientId = order != null && order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order != null && order.getCompany() != null ? order.getCompany().getGroupId() : null;
            String message = buildPublishedReviewProgressMessage(order, actualPublished);

            boolean sent = orderStatusNotificationService.sendProgressMessageToClientChat(order, clientId, groupId, message);
            if (sent) {
                log.info("Короткий отчёт о публикации отправлен клиенту: {}", message);
            } else {
                log.warn("Короткий отчёт о публикации не отправлен клиенту: {}", message);
            }
        } catch (Exception e) {
            log.warn("Короткий отчёт о публикации не отправлен из-за ошибки. Заказ продолжит обработку", e);
        }
    }

    private boolean shouldSendPublishedReviewProgress(Order order, int actualPublished) {
        if (order == null) {
            return false;
        }

        int total = order.getAmount();
        if (total > 0 && actualPublished >= total) {
            log.info("Короткий отчёт о публикации пропущен: заказ {} дошел до финального отзыва", order.getId());
            return false;
        }

        if (!appSettingService.getBoolean(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, true)) {
            log.info("Короткий отчёт о публикации пропущен: глобальная настройка выключена");
            return false;
        }

        Company company = order.getCompany();
        if (company != null && !company.isPublicationProgressReportsEnabled()) {
            log.info("Короткий отчёт о публикации пропущен: отчеты выключены для компании id={}", company.getId());
            return false;
        }

        return true;
    }

    private String buildPublishedReviewProgressMessage(Order order, int actualPublished) {
        int total = order != null && order.getAmount() > 0 ? order.getAmount() : actualPublished;
        String progress = actualPublished + " / " + total;
        String companyTitle = Optional.ofNullable(order)
                .map(Order::getCompany)
                .map(Company::getTitle)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse("");
        String filialTitle = Optional.ofNullable(order)
                .map(Order::getFilial)
                .map(Filial::getTitle)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse("");

        String subject = companyTitle;
        if (!filialTitle.isEmpty()) {
            subject = subject.isEmpty() ? filialTitle : subject + " - " + filialTitle;
        }

        if (subject.isEmpty()) {
            return "Опубликован новый отзыв " + progress + ".";
        }

        return subject + ". Опубликован новый отзыв " + progress + ".";
    }

    private ReviewPublicationTarget validateAndRetrievePublicationTarget(Long reviewId) {
        Long orderId = reviewRepository.findOrderIdByReviewId(reviewId)
                .orElseThrow(() -> new IllegalStateException("Отзыв или заказ не найден: id=" + reviewId));

        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElse(null);
        Review review = reviewRepository.findByIdForPublication(reviewId).orElse(null);

        if (review == null) {
            throw new IllegalStateException("Отзыв не найден: id=" + reviewId);
        }

        OrderDetails details = review.getOrderDetails();
        if (details == null || details.getOrder() == null || !Objects.equals(details.getOrder().getId(), orderId)) {
            throw new IllegalStateException("OrderDetails или Order отсутствуют у отзыва id=" + reviewId);
        }

        if (order == null || review.isPublish()) {
            throw new IllegalStateException("Заказ не найден или отзыв уже опубликован. id=" + reviewId);
        }

        return new ReviewPublicationTarget(review, order);
    }

    protected int countPublishedReviews(Order order) {
        if (order == null || order.getId() == null) {
            return 0;
        }
        return reviewRepository.countPublishedByOrderId(order.getId());
    }

    private record ReviewPublicationTarget(Review review, Order order) {
    }

    @Override
    public int countOrdersByWorkerAndStatus(Worker worker, String status) {
        return orderStatisticsService.countOrdersByWorkerAndStatus(worker, status);
    }

    // =========================================================================================================
    // ======================================== АГРЕГАЦИИ =======================================================
    // =========================================================================================================

    @Override
    public Map<String, Pair<Long, Long>> getNewOrderAll(String statusNew, String statusCorrect) {
        return orderStatisticsService.getNewOrderAll(statusNew, statusCorrect);
    }

    @Override
    public Map<String, Long> getAllOrdersToMonth(String status, LocalDate firstDayOfMonth, LocalDate lastDayOfMonth) {
        return orderStatisticsService.getAllOrdersToMonth(status, firstDayOfMonth, lastDayOfMonth);
    }

    @Override
    public Map<String, Map<String, Long>> getAllOrdersToMonthByStatus(
            LocalDate firstDayOfMonth,
            LocalDate lastDayOfMonth,
            String orderInNew,
            String orderToCheck,
            String orderInCheck,
            String orderInCorrect,
            String orderInPublished,
            String orderInWaitingPay1,
            String orderInWaitingPay2,
            String orderNoPay
    ) {
        return orderStatisticsService.getAllOrdersToMonthByStatus(
                firstDayOfMonth,
                lastDayOfMonth,
                orderInNew,
                orderToCheck,
                orderInCheck,
                orderInCorrect,
                orderInPublished,
                orderInWaitingPay1,
                orderInWaitingPay2,
                orderNoPay
        );
    }

    @Override
    public void save(Order order) {
        orderRepository.save(order);
    }

    // =========================================================================================================
    // ======================================== CONVERTER DTO ===================================================
    // =========================================================================================================

    @Override
    public OrderDTO convertToOrderDTOToRepeat(Order order) {
        return orderDtoMapper.toRepeatOrderDTO(order, STATUS_NEW);
    }

}
