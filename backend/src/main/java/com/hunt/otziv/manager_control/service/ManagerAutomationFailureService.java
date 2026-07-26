package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerAutomationFailureService {

    public static final String ENTITY_AUTOMATION_FAILURE = "AUTOMATION_FAILURE";
    public static final String ENTITY_COMMON_INVOICE_AUTOMATION = "COMMON_INVOICE_AUTOMATION";

    private static final int MAX_CANDIDATES = 10_000;
    private static final Set<CommonInvoiceStatus> ACTIVE_INVOICE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID,
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );

    private final ScheduledClientMessageStateRepository stateRepository;
    private final ManagerAutomationFailurePolicy policy;
    private final AppSettingService appSettingService;
    private final OrderRepository orderRepository;
    private final CompanyRepository companyRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;

    @Transactional(readOnly = true)
    public List<AutomationFailureIssue> issues(Manager manager, int limit) {
        if (manager == null
                || manager.getId() == null
                || !appSettingService.getBoolean(
                        AppSettingService.MANAGER_CONTROL_AUTOMATION_FAILURES_ENABLED,
                        true
                )) {
            return List.of();
        }
        int threshold = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_FAILURE_THRESHOLD,
                ClientMessageOrderStatusService.DEFAULT_MANUAL_CONTROL_FAILURE_THRESHOLD
        );
        int afterMinutes = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_MANUAL_CONTROL_AFTER_MINUTES,
                ClientMessageOrderStatusService.DEFAULT_MANUAL_CONTROL_AFTER_MINUTES
        );
        LocalDateTime now = LocalDateTime.now();
        List<Long> candidateIds = stateRepository.findManagerControlCandidateIds(
                manager.getId(),
                PageRequest.of(0, MAX_CANDIDATES)
        );
        Map<String, AutomationFailureIssue> unique = new LinkedHashMap<>();
        stateRepository.findAllById(candidateIds).forEach(state -> {
            if (!policy.isActionable(state, now, threshold, afterMinutes)) {
                return;
            }
            resolveIssue(manager, state, now).ifPresent(issue ->
                    unique.merge(issue.deduplicationKey(), issue, this::moreImportant)
            );
        });
        return unique.values().stream()
                .sorted(Comparator
                        .comparing(AutomationFailureIssue::firstObservedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AutomationFailureIssue::entityId))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> representedCommonInvoiceIds(Manager manager) {
        return issues(manager, MAX_CANDIDATES).stream()
                .map(AutomationFailureIssue::commonInvoiceId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public boolean isStillActionable(Manager manager, String entityType, Long entityId) {
        if (manager == null || entityId == null) {
            return false;
        }
        return findIssue(manager, entityType, entityId).isPresent();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<AutomationFailureIssue> findIssue(Manager manager, String entityType, Long entityId) {
        if (manager == null || entityId == null) {
            return Optional.empty();
        }
        return issues(manager, MAX_CANDIDATES).stream().filter(issue ->
                Objects.equals(issue.entityType(), entityType)
                        && Objects.equals(issue.entityId(), entityId)
        ).findFirst();
    }

    private Optional<AutomationFailureIssue> resolveIssue(
            Manager manager,
            ScheduledClientMessageState state,
            LocalDateTime now
    ) {
        Order order = state.getOrderId() == null
                ? null
                : orderRepository.findByIdForOrderDto(state.getOrderId()).orElse(null);
        Company company = order == null ? null : order.getCompany();
        if (company == null && state.getCompanyId() != null) {
            company = companyRepository.findByIdForCompanyDto(state.getCompanyId()).orElse(null);
        }
        Manager owner = order != null && order.getManager() != null
                ? order.getManager()
                : company == null ? null : company.getManager();
        if (owner == null || !Objects.equals(owner.getId(), manager.getId())) {
            return Optional.empty();
        }

        CommonInvoice invoice = activeCommonInvoice(order);
        String entityType = invoice == null ? ENTITY_AUTOMATION_FAILURE : ENTITY_COMMON_INVOICE_AUTOMATION;
        Long entityId = invoice == null ? state.getId() : invoice.getId();
        String deduplicationKey = entityType + ":" + entityId;
        String companyTitle = safe(company == null ? null : company.getTitle());
        String title = invoice != null
                ? safe(invoice.getTitle()).isBlank() ? "Общий счет #" + invoice.getId() : invoice.getTitle()
                : companyTitle.isBlank() ? "Ошибка автоматизации #" + state.getId() : companyTitle;
        String subtitle = scenarioLabel(state.getScenario())
                + (order == null || order.getId() == null ? "" : " · заказ #" + order.getId());
        String errorCode = safe(state.getLastErrorCode());
        String errorMessage = safe(state.getLastErrorMessage());
        List<String> reasonParts = new ArrayList<>();
        if ("payment_instruction_failed".equalsIgnoreCase(errorCode)) {
            reasonParts.add("Почему в замечаниях: автоматика не смогла подготовить или отправить счет клиенту");
        } else {
            reasonParts.add("Почему в замечаниях: задача клиентской автоматизации завершилась ошибкой");
        }
        if (!errorCode.isBlank()) {
            reasonParts.add(errorCode);
        }
        if (!errorMessage.isBlank() && !errorMessage.equalsIgnoreCase(errorCode)) {
            reasonParts.add(errorMessage);
        }
        reasonParts.add("неудачных попыток подряд: " + state.getConsecutiveFailures());
        if (state.getNextAttemptAt() != null) {
            reasonParts.add("следующая попытка: " + state.getNextAttemptAt());
        }
        reasonParts.add("Нажмите «Починить»: система перепроверит источник и безопасно повторит задачу");
        String targetUrl = invoice != null
                ? "/admin/common-billing?invoiceId=" + invoice.getId()
                : orderTargetUrl(manager, companyTitle);
        return Optional.of(new AutomationFailureIssue(
                deduplicationKey,
                entityType,
                entityId,
                state.getId(),
                invoice == null ? null : invoice.getId(),
                title,
                subtitle,
                "Ошибка автоматизации · " + state.getConsecutiveFailures(),
                String.join(" · ", reasonParts),
                targetUrl,
                company == null ? null : company.getUrlChat(),
                state.getScenario(),
                state.getConsecutiveFailures(),
                state.getCreatedAt(),
                state.getLastAttemptAt(),
                state.getNextAttemptAt()
        ));
    }

    private CommonInvoice activeCommonInvoice(Order order) {
        if (order == null || order.getId() == null) {
            return null;
        }
        return commonInvoiceOrderRepository.findByOrderIdWithInvoice(order.getId())
                .map(CommonInvoiceOrder::getInvoice)
                .filter(invoice -> ACTIVE_INVOICE_STATUSES.contains(invoice.getStatus()))
                .orElse(null);
    }

    private AutomationFailureIssue moreImportant(
            AutomationFailureIssue left,
            AutomationFailureIssue right
    ) {
        if (right.consecutiveFailures() != left.consecutiveFailures()) {
            return right.consecutiveFailures() > left.consecutiveFailures() ? right : left;
        }
        LocalDateTime leftAt = left.firstObservedAt();
        LocalDateTime rightAt = right.firstObservedAt();
        if (leftAt == null) {
            return rightAt == null ? left : right;
        }
        return rightAt != null && rightAt.isBefore(leftAt) ? right : left;
    }

    private String orderTargetUrl(Manager manager, String companyTitle) {
        StringBuilder url = new StringBuilder("/orders?managerId=")
                .append(manager.getId())
                .append("&control=manager-overdue&sortDirection=desc");
        if (!companyTitle.isBlank()) {
            url.append("&keyword=").append(URLEncoder.encode(companyTitle, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private String scenarioLabel(ClientMessageScenario scenario) {
        if (scenario == null) {
            return "Сообщение клиенту";
        }
        return switch (scenario) {
            case CLIENT_TEXT_REMINDER -> "Напоминание о тексте";
            case REVIEW_CHECK_REMINDER -> "Напоминание о проверке";
            case REVIEW_CHECK_DELIVERY_RETRY -> "Доставка отзыва на проверку";
            case REVIEW_CHECK_AUTO_ARCHIVE -> "Автоархивация проверки";
            case PAYMENT_INVOICE_RETRY -> "Повторная отправка счета";
            case PAYMENT_REMINDER -> "Напоминание об оплате";
            case PAYMENT_OVERDUE_ESCALATION -> "Эскалация просроченной оплаты";
            case ARCHIVE_REORDER_OFFER -> "Предложение повторного заказа";
            case BAD_REVIEW_INVOICE -> "Счет за плохой отзыв";
            case BAD_REVIEW_AUTO_BAN -> "Автоблокировка плохого отзыва";
            case REVIEW_RECOVERY_NOTICE -> "Уведомление о восстановлении отзыва";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record AutomationFailureIssue(
            String deduplicationKey,
            String entityType,
            Long entityId,
            Long stateId,
            Long commonInvoiceId,
            String title,
            String subtitle,
            String status,
            String reason,
            String targetUrl,
            String chatUrl,
            ClientMessageScenario scenario,
            int consecutiveFailures,
            LocalDateTime firstObservedAt,
            LocalDateTime lastAttemptAt,
            LocalDateTime nextAttemptAt
    ) {
    }
}
