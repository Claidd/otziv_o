package com.hunt.otziv.p_products.deletion;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.next_order.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderDetailsService;
import com.hunt.otziv.payments.service.PaymentLinkArchiveService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.services.ReviewService;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderDeletionService {

    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_ARCHIVE = "Архив";
    private static final String STATUS_COMPANY_IN_STOP = "На стопе";
    private static final String STATUS_COMPANY_BAN = "Бан";
    private static final Set<String> NON_BLOCKING_AFTER_NEW_ORDER_DELETION_STATUSES = Set.of(
            STATUS_PAYMENT,
            STATUS_ARCHIVE
    );
    private static final List<String> ROLE_PRIORITY = List.of(
            "ROLE_ADMIN",
            "ROLE_OWNER",
            "ROLE_MANAGER",
            "ROLE_WORKER",
            "ROLE_OPERATOR",
            "ROLE_MARKETOLOG"
    );
    private static final Set<String> IGNORED_AUTHORITIES = Set.of(
            "ROLE_DEFAULT-ROLES-OTZIV",
            "ROLE_OFFLINE_ACCESS",
            "ROLE_UMA_AUTHORIZATION"
    );

    private final OrderRepository orderRepository;
    private final OrderDetailsService orderDetailsService;
    private final ReviewService reviewService;
    private final BadReviewTaskService badReviewTaskService;
    private final OrderDeletionPolicy orderDeletionPolicy;
    private final NextOrderRequestService nextOrderRequestService;
    private final NextOrderRequestRepository nextOrderRequestRepository;
    private final ReviewRecoveryTaskRepository reviewRecoveryTaskRepository;
    private final ReviewRecoveryBatchRepository reviewRecoveryBatchRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final PaymentLinkArchiveService paymentLinkArchiveService;
    private final CompanyService companyService;
    private final CompanyStatusService companyStatusService;
    private final EntityManager entityManager;

    @Transactional
    public boolean deleteOrder(Long orderId, Principal principal) {
        String userRole = getRole();
        String username = principal != null ? principal.getName() : "unknown";

        log.info("Начало удаления заказа ID: {}, инициатор: {}, роль: {}", orderId, username, userRole);

        Order orderToDelete = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Заказ ID: {} не найден", orderId);
                    return new UsernameNotFoundException(String.format("Order '%d' not found", orderId));
                });

        if (orderDeletionPolicy.canDelete(userRole, orderToDelete)) {
            try {
                Long companyId = orderToDelete.getCompany() == null ? null : orderToDelete.getCompany().getId();
                String deletedOrderStatus = safeStatusTitle(orderToDelete);
                List<OrderDetails> orderDetails = orderDetailsService.findByOrderId(orderId);
                log.info("Найдено {} деталей заказа для удаления", orderDetails.size());

                int deletedBadReviewTasks = badReviewTaskService.deleteAllByOrderId(orderId);
                int deletedReviewRecoveryTasks = reviewRecoveryTaskRepository.deleteByOrderId(orderId);
                int deletedReviewRecoveryBatches = reviewRecoveryBatchRepository.deleteByOrderId(orderId);
                int deletedNextOrderRequests = nextOrderRequestRepository.deleteBySourceOrderId(orderId);
                int deletedCommonInvoiceOrders = commonInvoiceOrderRepository.deleteByOrderId(orderId);

                log.info(
                        "Удалены зависимые записи заказа ID {}: badReviewTasks={}, recoveryTasks={}, recoveryBatches={}, nextOrderRequests={}, commonInvoiceOrders={}",
                        orderId,
                        deletedBadReviewTasks,
                        deletedReviewRecoveryTasks,
                        deletedReviewRecoveryBatches,
                        deletedNextOrderRequests,
                        deletedCommonInvoiceOrders
                );

                int totalDeletedReviews = 0;

                for (OrderDetails detail : orderDetails) {
                    List<Review> reviews = Optional.ofNullable(detail.getReviews()).orElse(Collections.emptyList());

                    if (!reviews.isEmpty()) {
                        List<Long> reviewIds = reviews.stream()
                                .map(Review::getId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());

                        log.info("Удаление отзывов для детали заказа ID: {}. Найдено отзывов: {}", detail.getId(), reviewIds.size());

                        if (!reviewIds.isEmpty()) {
                            reviewService.deleteAllByIdIn(reviewIds);
                            log.info("Успешно удалено {} отзывов для детали заказа ID: {}", reviewIds.size(), detail.getId());
                            totalDeletedReviews += reviewIds.size();
                        }
                    }
                }

                log.info("Всего удалено отзывов: {}", totalDeletedReviews);

                log.info("Удаление всех деталей заказа ID: {}", orderId);
                orderDetailsService.deleteAllByOrderId(orderId);
                log.info("Успешно удалено {} деталей заказа", orderDetails.size());

                nextOrderRequestService.cancelForDeletedCreatedOrder(orderToDelete);
                int archivedPaymentLinks = paymentLinkArchiveService.archiveForDeletedOrder(orderId);
                log.info("Архивировано и удалено {} платежных ссылок заказа ID: {}", archivedPaymentLinks, orderId);

                clearPersistenceContextBeforeOrderDelete(orderId);

                log.info("Удаление заказа ID: {}", orderId);
                orderRepository.deleteById(orderId);
                log.info("Заказ ID: {} успешно удален", orderId);

                stopCompanyIfDeletedLastNewOrder(companyId, deletedOrderStatus, orderId);

                log.info("Успешное завершение удаления заказа ID: {}. Удалено: заказ, {} деталей, {} отзывов, {} плохих задач",
                        orderId, orderDetails.size(), totalDeletedReviews, deletedBadReviewTasks);

                return true;

            } catch (Exception e) {
                log.error("Ошибка при удалении заказа ID: {}. Причина: {}", orderId, e.getMessage(), e);
                throw e;
            }
        }

        log.warn("Заказ ID: {} не удален. Недостаточно прав или некорректный статус. Роль пользователя: {}, статус заказа: {}",
                orderId, userRole, safeStatusTitle(orderToDelete));

        return false;
    }

    private String getRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "";
        }

        Object authPrincipal = authentication.getPrincipal();
        if (authPrincipal instanceof UserDetails userDetails) {
            return primaryRole(userDetails.getAuthorities());
        }

        return primaryRole(authentication.getAuthorities());
    }

    private String primaryRole(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return "";
        }

        List<String> authorityNames = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(this::normalizeAuthority)
                .toList();

        for (String role : ROLE_PRIORITY) {
            if (authorityNames.contains(role)) {
                return role;
            }
        }

        return authorityNames.stream()
                .filter(authority -> !IGNORED_AUTHORITIES.contains(authority))
                .findFirst()
                .or(() -> authorityNames.stream().findFirst())
                .orElse("");
    }

    private String normalizeAuthority(String authority) {
        return authority == null ? "" : authority.trim().toUpperCase(Locale.ROOT);
    }

    private void clearPersistenceContextBeforeOrderDelete(Long orderId) {
        entityManager.flush();
        entityManager.clear();
        log.debug("Persistence context очищен перед удалением заказа ID: {}", orderId);
    }

    private void stopCompanyIfDeletedLastNewOrder(Long companyId, String deletedOrderStatus, Long deletedOrderId) {
        if (companyId == null || !STATUS_NEW.equals(deletedOrderStatus)) {
            return;
        }

        boolean hasRemainingActiveOrders = orderRepository.existsActiveOrderByCompanyId(
                companyId,
                NON_BLOCKING_AFTER_NEW_ORDER_DELETION_STATUSES
        );
        if (hasRemainingActiveOrders) {
            log.info(
                    "Компания {} не переведена в '{}': после удаления заказа {} остались активные заказы",
                    companyId,
                    STATUS_COMPANY_IN_STOP,
                    deletedOrderId
            );
            return;
        }

        Company company = companyService.getCompaniesById(companyId);
        if (company == null) {
            log.warn("Компания {} не найдена для пересчета статуса после удаления заказа {}", companyId, deletedOrderId);
            return;
        }
        if (company.getStatus() != null && STATUS_COMPANY_BAN.equals(company.getStatus().getTitle())) {
            log.info("Компания {} уже в '{}', после удаления заказа {} статус не меняем", companyId, STATUS_COMPANY_BAN, deletedOrderId);
            return;
        }

        company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_STOP));
        companyService.save(company);
        log.info(
                "Компания {} переведена в '{}' после удаления последнего нового заказа {}",
                companyId,
                STATUS_COMPANY_IN_STOP,
                deletedOrderId
        );
    }
}
