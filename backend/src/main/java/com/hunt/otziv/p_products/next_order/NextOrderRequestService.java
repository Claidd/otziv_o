package com.hunt.otziv.p_products.next_order;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class NextOrderRequestService {

    private static final String STATUS_COMPANY_IN_WORK = "В работе";
    private static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";
    private static final Set<String> INACTIVE_ORDER_STATUSES = Set.of("Оплачено", "Архив", "Бан");
    private static final Set<NextOrderRequestStatus> OPEN_REQUEST_STATUSES = Set.of(
            NextOrderRequestStatus.PENDING,
            NextOrderRequestStatus.FAILED
    );
    private static final int MAX_ERROR_LENGTH = 2000;

    private final NextOrderRequestRepository requestRepository;
    private final OrderRepository orderRepository;
    private final CompanyService companyService;
    private final CompanyStatusService companyStatusService;
    private final ApplicationEventPublisher eventPublisher;
    private final NextOrderFailureNotifier nextOrderFailureNotifier;

    @Transactional
    public Optional<NextOrderRequest> openForPaidOrder(Order sourceOrder) {
        if (sourceOrder == null || sourceOrder.getId() == null) {
            log.warn("Не удалось создать заявку на следующий заказ: исходный заказ не сохранен");
            return Optional.empty();
        }

        Company company = sourceOrder.getCompany();
        if (company == null || company.getId() == null) {
            log.warn("Не удалось создать заявку на следующий заказ: у заказа {} нет компании", sourceOrder.getId());
            return Optional.empty();
        }

        Long filialId = filialId(sourceOrder.getFilial());
        Set<Long> filialIds = orderFilialIds(sourceOrder);
        if (hasActiveOrderForFilials(company.getId(), filialIds, filialId, sourceOrder.getId())) {
            log.info(
                    "Следующая заявка для заказа {} не нужна: у компании {} и филиалов {} уже есть активный заказ",
                    sourceOrder.getId(),
                    company.getId(),
                    filialIds.isEmpty() ? String.valueOf(filialId) : filialIds
            );
            refreshCompanyStatusForOpenRequests(company.getId());
            return Optional.empty();
        }

        Optional<NextOrderRequest> existingForSource = requestRepository.findBySourceOrderId(sourceOrder.getId());
        if (existingForSource.isPresent()) {
            NextOrderRequest request = existingForSource.get();
            if (request.getStatus() == NextOrderRequestStatus.CREATED) {
                return Optional.of(request);
            }

            request.setStatus(NextOrderRequestStatus.PENDING);
            request.setErrorMessage(null);
            requestRepository.save(request);
            refreshCompanyStatusForOpenRequests(company.getId());
            eventPublisher.publishEvent(new NextOrderRequestedEvent(request.getId()));
            return Optional.of(request);
        }

        Optional<NextOrderRequest> existingOpen = findOpenRequest(company.getId(), filialId);
        if (existingOpen.isPresent()) {
            log.info(
                    "Для компании {} и филиала {} уже есть открытая заявка на следующий заказ: {}",
                    company.getId(),
                    filialId,
                    existingOpen.get().getId()
            );
            refreshCompanyStatusForOpenRequests(company.getId());
            return existingOpen;
        }

        NextOrderRequest request = NextOrderRequest.builder()
                .company(company)
                .filial(sourceOrder.getFilial())
                .sourceOrder(sourceOrder)
                .status(NextOrderRequestStatus.PENDING)
                .build();
        request = requestRepository.save(request);
        refreshCompanyStatusForOpenRequests(company.getId());

        log.info(
                "Создана заявка {} на следующий заказ для компании {}, филиала {}, исходный заказ {}",
                request.getId(),
                company.getId(),
                filialId,
                sourceOrder.getId()
        );
        eventPublisher.publishEvent(new NextOrderRequestedEvent(request.getId()));
        return Optional.of(request);
    }

    @Transactional
    public void completeOpenRequestForCreatedOrder(Order createdOrder) {
        if (createdOrder == null || createdOrder.getId() == null || createdOrder.getCompany() == null) {
            return;
        }

        Long companyId = createdOrder.getCompany().getId();
        Long filialId = filialId(createdOrder.getFilial());
        findOpenRequest(companyId, filialId).ifPresent(request -> {
            request.setStatus(NextOrderRequestStatus.CREATED);
            request.setCreatedOrder(createdOrder);
            request.setErrorMessage(null);
            requestRepository.save(request);
            log.info(
                    "Заявка {} на следующий заказ закрыта созданным заказом {} для компании {}, филиала {}",
                    request.getId(),
                    createdOrder.getId(),
                    companyId,
                    filialId
            );
        });
    }

    @Transactional
    public boolean cancelForDeletedCreatedOrder(Order deletedOrder) {
        if (deletedOrder == null || deletedOrder.getId() == null) {
            return false;
        }

        List<NextOrderRequest> requests = requestRepository.findByCreatedOrder_Id(deletedOrder.getId());
        if (requests.isEmpty()) {
            return false;
        }

        for (NextOrderRequest request : requests) {
            request.setCreatedOrder(null);
            if (request.getStatus() == NextOrderRequestStatus.CREATED) {
                request.setStatus(NextOrderRequestStatus.CANCELED);
                request.setErrorMessage("Автосозданный следующий заказ " + deletedOrder.getId() + " удален");
            }
            requestRepository.save(request);
            log.info(
                    "Заявка {} на следующий заказ отменена из-за удаления автосозданного заказа {}",
                    request.getId(),
                    deletedOrder.getId()
            );
        }

        return true;
    }

    @Transactional
    public void markAttemptStarted(Long requestId) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setAttempts(request.getAttempts() + 1);
            request.setStatus(NextOrderRequestStatus.PENDING);
            request.setErrorMessage(null);
            requestRepository.save(request);
        });
    }

    @Transactional
    public void markCreatedIfOpen(Long requestId) {
        requestRepository.findById(requestId).ifPresent(request -> {
            if (!OPEN_REQUEST_STATUSES.contains(request.getStatus())) {
                return;
            }
            request.setStatus(NextOrderRequestStatus.CREATED);
            request.setErrorMessage(null);
            requestRepository.save(request);
            refreshCompanyStatusForOpenRequests(request.getCompany().getId());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long requestId, Throwable cause) {
        requestRepository.findById(requestId).ifPresent(request -> {
            request.setAttempts(request.getAttempts() + 1);
            request.setStatus(NextOrderRequestStatus.FAILED);
            request.setErrorMessage(truncate(errorMessage(cause)));
            requestRepository.save(request);
            refreshCompanyStatusForOpenRequests(request.getCompany().getId());
            log.error("Автоматический следующий заказ по заявке {} не создан", requestId, cause);
            nextOrderFailureNotifier.notifyManager(
                    request.getSourceOrder(),
                    null,
                    "автосоздание следующего заказа по заявке #" + requestId,
                    cause
            );
        });
    }

    public boolean hasOpenRequests(Long companyId) {
        if (companyId == null) {
            return false;
        }
        return requestRepository.existsByCompanyIdAndStatusIn(companyId, OPEN_REQUEST_STATUSES);
    }

    public Map<Long, NextOrderRequestSummary> summariesForCompanies(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, MutableSummary> mutableSummaries = new HashMap<>();
        List<NextOrderRequest> requests = requestRepository.findByCompanyIdInAndStatusIn(companyIds, OPEN_REQUEST_STATUSES);
        for (NextOrderRequest request : requests) {
            if (request.getCompany() == null || request.getCompany().getId() == null) {
                continue;
            }
            MutableSummary summary = mutableSummaries.computeIfAbsent(request.getCompany().getId(), id -> new MutableSummary());
            summary.openCount++;
            if (isAfter(request.getUpdatedAt(), summary.latestRequestAt)) {
                summary.latestRequestAt = request.getUpdatedAt();
                summary.latestFilialTitle = request.getFilial() == null ? null : request.getFilial().getTitle();
            }
            if (request.getStatus() == NextOrderRequestStatus.FAILED) {
                summary.failedCount++;
                if (hasText(request.getErrorMessage()) && isAfter(request.getUpdatedAt(), summary.latestErrorAt)) {
                    summary.latestErrorAt = request.getUpdatedAt();
                    summary.latestError = request.getErrorMessage();
                }
            }
        }

        Map<Long, NextOrderRequestSummary> result = new HashMap<>();
        mutableSummaries.forEach((companyId, summary) -> result.put(
                companyId,
                new NextOrderRequestSummary(
                        summary.openCount,
                        summary.failedCount,
                        summary.latestFilialTitle,
                        summary.latestError
                )
        ));
        return result;
    }

    public boolean hasActiveOrderForFilial(Long companyId, Long filialId, Long excludedOrderId) {
        return orderRepository.existsActiveOrderByCompanyIdAndFilialId(
                companyId,
                filialId,
                excludedOrderId,
                INACTIVE_ORDER_STATUSES
        );
    }

    public boolean hasActiveOrderForFilials(Long companyId, Set<Long> filialIds, Long fallbackFilialId, Long excludedOrderId) {
        if (filialIds == null || filialIds.isEmpty()) {
            return hasActiveOrderForFilial(companyId, fallbackFilialId, excludedOrderId);
        }
        if (filialIds.size() == 1) {
            return hasActiveOrderForFilial(companyId, filialIds.iterator().next(), excludedOrderId);
        }
        return orderRepository.existsActiveOrderByCompanyIdAndAnyFilialId(
                companyId,
                filialIds,
                excludedOrderId,
                INACTIVE_ORDER_STATUSES
        );
    }

    public List<Order> findActiveOrdersForFilial(Long companyId, Long filialId) {
        return orderRepository.findActiveOrdersByCompanyIdAndFilialId(
                companyId,
                filialId,
                null,
                INACTIVE_ORDER_STATUSES,
                PageRequest.of(0, 1)
        );
    }

    public List<Order> findActiveOrdersForFilials(Long companyId, Set<Long> filialIds, Long fallbackFilialId) {
        if (filialIds == null || filialIds.isEmpty()) {
            return findActiveOrdersForFilial(companyId, fallbackFilialId);
        }
        if (filialIds.size() == 1) {
            return findActiveOrdersForFilial(companyId, filialIds.iterator().next());
        }
        return orderRepository.findActiveOrdersByCompanyIdAndAnyFilialId(
                companyId,
                filialIds,
                null,
                INACTIVE_ORDER_STATUSES,
                PageRequest.of(0, 1)
        );
    }

    public Set<Long> orderFilialIds(Order order) {
        Set<Long> filialIds = new LinkedHashSet<>();
        Long orderFilialId = filialId(order == null ? null : order.getFilial());
        if (orderFilialId != null) {
            filialIds.add(orderFilialId);
        }

        if (order == null || order.getDetails() == null) {
            return filialIds;
        }

        for (OrderDetails detail : order.getDetails()) {
            if (detail == null || detail.getReviews() == null) {
                continue;
            }
            for (Review review : detail.getReviews()) {
                Long reviewFilialId = filialId(review == null ? null : review.getFilial());
                if (reviewFilialId != null) {
                    filialIds.add(reviewFilialId);
                }
            }
        }

        return filialIds;
    }

    private Optional<NextOrderRequest> findOpenRequest(Long companyId, Long filialId) {
        return requestRepository.findOpenByCompanyIdAndFilialId(
                companyId,
                filialId,
                OPEN_REQUEST_STATUSES,
                PageRequest.of(0, 1)
        ).stream().findFirst();
    }

    private void refreshCompanyStatusForOpenRequests(Long companyId) {
        if (companyId == null) {
            return;
        }

        Company company = companyService.getCompaniesById(companyId);
        if (company == null) {
            return;
        }

        if (orderRepository.existsActiveOrderByCompanyId(companyId, INACTIVE_ORDER_STATUSES)) {
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_WORK));
        } else if (hasOpenRequests(companyId)) {
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        } else {
            return;
        }

        companyService.save(company);
    }

    private Long filialId(Filial filial) {
        return filial == null ? null : filial.getId();
    }

    private String errorMessage(Throwable cause) {
        if (cause == null) {
            return "Неизвестная ошибка автосоздания заказа";
        }
        String message = cause.getMessage();
        if (hasText(message)) {
            return message;
        }
        return cause.getClass().getSimpleName();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    private static class MutableSummary {
        private int openCount;
        private int failedCount;
        private LocalDateTime latestRequestAt;
        private String latestFilialTitle;
        private LocalDateTime latestErrorAt;
        private String latestError;
    }
}
