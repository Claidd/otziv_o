package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.manager_control.service.ManagerControlOrderAutomationDiagnostics.WorkerClientTextDecision;
import com.hunt.otziv.manager_control.service.ManagerControlOrderAutomationDiagnostics.WorkerOrderControlEntry;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlOverdueStatusResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemType;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskResolutionAction;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlProblemExamples {

    private final ManagerControlClientMessageText clientMessageText;

    private final ManagerControlConcretePresenter concretePresenter;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    static final int DETAIL_EXAMPLE_LIMIT = 5;

    static final int OVERDUE_NOTIFICATION_DAYS = 4;

    static final int WORKER_ORDER_UNCHANGED_DAYS = ManagerControlOrderAutomationDiagnostics.WORKER_ORDER_UNCHANGED_DAYS;

    static final String ENTITY_PUBLISH_REVIEW = ManagerControlWorkerTaskLookup.ENTITY_PUBLISH_REVIEW;

    static final String ENTITY_PUBLICATION_DATE_REVIEW = "PUBLICATION_DATE_REVIEW";

    static final String ENTITY_NAGUL_REVIEW = ManagerControlWorkerTaskLookup.ENTITY_NAGUL_REVIEW;

    static final String ENTITY_WORKER_ORDER_NEW = ManagerControlWorkerTaskLookup.ENTITY_WORKER_ORDER_NEW;

    static final String ENTITY_WORKER_ORDER_CORRECT = ManagerControlWorkerTaskLookup.ENTITY_WORKER_ORDER_CORRECT;

    static final String ENTITY_TELEGRAM_CHAT = "TELEGRAM_CHAT";

    static final String ENTITY_CLIENT_CHAT_UNANSWERED = "CLIENT_CHAT_UNANSWERED";

    static final String ENTITY_CLIENT_CHAT_AUDIT = "CLIENT_CHAT_AUDIT";

    static final String ENTITY_ORDER_PAYMENT_INTEGRITY = OrderPaymentIntegrityService.ENTITY_TYPE;

    static final Set<String> OVERDUE_IGNORED_STATUSES = Set.of("Оплачено", "Архив", "Публикация", "Не оплачено", "Бан");

    static final List<String> ORDER_STATUS_DISPLAY_ORDER = List.of("Новый", "В проверку", "На проверке", "Коррекция", "Публикация", "Опубликовано", "Ожидает общего счета", "Выставлен счет", "Напоминание", "Требует внимания", "Не оплачено", "Бан");

    static final Set<String> PAYMENT_AUTOMATION_STATUSES = Set.of("Опубликовано", "Выставлен счет", "Напоминание", "Не оплачено");

    static final Set<ClientMessageScenario> PAYMENT_AUTOMATION_SCENARIOS = Set.of(ClientMessageScenario.PAYMENT_INVOICE_RETRY, ClientMessageScenario.PAYMENT_REMINDER, ClientMessageScenario.PAYMENT_OVERDUE_ESCALATION);

    static final Set<String> REVIEW_CHECK_AUTOMATION_STATUSES = Set.of("На проверке");

    static final Set<ClientMessageScenario> REVIEW_CHECK_SCENARIOS = Set.of(ClientMessageScenario.REVIEW_CHECK_REMINDER);

    static final Set<String> DELIVERY_RETRY_AUTOMATION_STATUSES = Set.of("В проверку");

    static final Set<ClientMessageScenario> DELIVERY_RETRY_SCENARIOS = Set.of(ClientMessageScenario.REVIEW_CHECK_DELIVERY_RETRY);

    static final Set<String> CLIENT_TEXT_AUTOMATION_STATUSES = Set.of("Новый");

    static final Set<ClientMessageScenario> CLIENT_TEXT_SCENARIOS = Set.of(ClientMessageScenario.CLIENT_TEXT_REMINDER);

    static final Set<CommonInvoiceStatus> COMMON_INVOICE_CONTROL_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING, CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER, CommonInvoiceStatus.PARTIALLY_PAID, CommonInvoiceStatus.NEEDS_ATTENTION, CommonInvoiceStatus.UNPAID, CommonInvoiceStatus.BAN);

    private final OrderService orderService;

    private final ClientMessageOrderStatusService clientMessageOrderStatusService;

    private final AppSettingService appSettingService;

    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    private final BadReviewTaskService badReviewTaskService;

    private final ReviewRecoveryTaskService reviewRecoveryTaskService;

    private final ReviewRepository reviewRepository;

    private final OrderRepository orderRepository;

    private final CompanyRepository companyRepository;

    private final PaymentLinkRepository paymentLinkRepository;

    private final ManagerControlInvoiceDiagnostics invoiceDiagnostics;

    private final ManagerAutomationFailureService managerAutomationFailureService;

    private final WorkerRiskIncidentRepository riskIncidentRepository;

    private final ManagerDailyControlRepository dailyControlRepository;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    List<ManagerControlConcreteItemResponse> detailExamples(Manager manager, ManagerDailyControlItem item, LocalDate today) {
        if (item == null || item.getCount() <= 0) {
            return List.of();
        }
        int limit = concreteSyncLimit(item);
        if (item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS || "OVERDUE_ORDERS".equals(item.getReasonCode())) {
            String status = item.getItemType() == ManagerDailyControlItemType.ORDER_STATUS ? item.getReasonCode() : "Все";
            return overdueOrderExamples(manager, status, today, limit);
        }
        if ("REQUIRES_ATTENTION".equals(item.getReasonCode())) {
            return orderStatusExamples(manager, "Требует внимания", limit);
        }
        if ("AUTOMATION_FAILURES".equals(item.getReasonCode())) {
            return automationFailureExamples(manager, limit);
        }
        if ("COMMON_INVOICES".equals(item.getReasonCode())) {
            return invoiceDiagnostics.examples(manager, today, limit, managerAutomationFailureService.representedCommonInvoiceIds(manager));
        }
        if ("PAYMENT_INTEGRITY".equals(item.getReasonCode())) {
            return paymentIntegrityIssueExamples(manager, today, limit);
        }
        if ("PUBLICATION_DATE_ISSUES".equals(item.getReasonCode())) {
            return publicationDateIssueExamples(manager, limit);
        }
        if ("CHAT_BINDING_ISSUES".equals(item.getReasonCode())) {
            return chatBindingIssueExamples(manager, today, limit);
        }
        if ("TELEGRAM_CHAT_MIGRATION".equals(item.getReasonCode())) {
            return telegramChatIssueExamples(manager, limit);
        }
        if ("UNANSWERED_CLIENT_MESSAGES".equals(item.getReasonCode())) {
            return unansweredClientMessageExamples(manager, limit);
        }
        if ("SUSPICIOUS_CLIENT_CLOSURES".equals(item.getReasonCode())) {
            return suspiciousClientClosureExamples(manager, limit);
        }
        if ("OPEN_RISKS".equals(item.getReasonCode()) || "risk".equals(item.getSectionCode())) {
            return riskExamples(manager, limit);
        }
        if ("WORKER_ACTIONS".equals(item.getReasonCode())) {
            return workerActionExamples(manager, today, limit);
        }
        if ("new_overdue".equals(item.getSectionCode())) {
            return workerStaleOrderExamples(manager, "Новый", today, limit);
        }
        if ("correct_overdue".equals(item.getSectionCode())) {
            return workerStaleOrderExamples(manager, "Коррекция", today, limit);
        }
        if ("nagul_overdue".equals(item.getSectionCode())) {
            return nagulReviewExamples(manager, today, limit);
        }
        if ("recovery".equals(item.getSectionCode())) {
            return recoveryTaskExamples(manager, today, limit);
        }
        if ("publish".equals(item.getSectionCode())) {
            return publishReviewExamples(manager, today, limit);
        }
        if ("bad".equals(item.getSectionCode())) {
            return badReviewTaskExamples(manager, today, limit);
        }
        return List.of();
    }

    int concreteSyncLimit(ManagerDailyControlItem item) {
        long requested = Math.max(DETAIL_EXAMPLE_LIMIT, item == null ? 0 : item.getCount());
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, requested));
    }

    List<ManagerControlConcreteItemResponse> overdueOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        Set<Long> snoozedOrderIds = snoozedOrderIds(manager, today);
        List<String> statuses = safe(status).isBlank() || "Все".equalsIgnoreCase(status) ? overdueStatuses(manager, today).stream().map(ManagerControlOverdueStatusResponse::status).toList() : List.of(status);
        Map<Long, OrderDTOList> uniqueOrders = new LinkedHashMap<>();
        for (String currentStatus : statuses) {
            if (uniqueOrders.size() >= limit) {
                break;
            }
            int remaining = Math.max(1, limit - uniqueOrders.size());
            LocalDate cutoff = managerControlOrderCutoff(currentStatus, today);
            orderService.getManagerControlOverdueOrdersByManager(manager, "", currentStatus, cutoff, OVERDUE_IGNORED_STATUSES, COMMON_INVOICE_CONTROL_STATUSES, PAYMENT_AUTOMATION_STATUSES, PAYMENT_AUTOMATION_SCENARIOS, REVIEW_CHECK_AUTOMATION_STATUSES, REVIEW_CHECK_SCENARIOS, DELIVERY_RETRY_AUTOMATION_STATUSES, DELIVERY_RETRY_SCENARIOS, CLIENT_TEXT_AUTOMATION_STATUSES, CLIENT_TEXT_SCENARIOS, ScheduledMessageStateStatus.ACTIVE, ScheduledMessageStateStatus.DONE, 0, remaining, "desc").getContent().stream().filter(order -> order.getId() != null).forEach(order -> uniqueOrders.putIfAbsent(order.getId(), order));
        }
        List<OrderDTOList> orders = new ArrayList<>(uniqueOrders.values());
        clientMessageOrderStatusService.enrichOrderList(orders);
        return orders.stream().filter(order -> order.getId() == null || !snoozedOrderIds.contains(order.getId())).filter(order -> !hasHealthyActiveClientMessageQueue(order)).map(order -> orderExample(order, today, orderManagerReason(order, today), manager)).limit(limit).toList();
    }

    List<ManagerControlConcreteItemResponse> orderStatusExamples(Manager manager, String status, int limit) {
        List<OrderDTOList> orders = orderService.getAllOrderDTOAndKeywordByManager(manager, "", status, 0, limit, "desc").getContent();
        clientMessageOrderStatusService.enrichOrderList(orders);
        return orders.stream().filter(order -> !hasHealthyActiveClientMessageQueue(order)).map(order -> orderExample(order, LocalDate.now(), orderManagerReason(order, LocalDate.now()), manager)).toList();
    }

    ManagerControlConcreteItemResponse orderExample(OrderDTOList order, LocalDate today, String reason, Manager manager) {
        LocalDate changed = order.getChanged();
        return new ManagerControlConcreteItemResponse(null, "ORDER", order.getId(), safe(order.getCompanyTitle()).isBlank() ? "Заказ #" + order.getId() : order.getCompanyTitle(), orderSubtitle(order), safe(order.getStatus()), changed == null ? null : daysSince(changed, today), reason, orderTargetUrl(order, manager), order.getOrderDetailsId() == null ? null : order.getOrderDetailsId().toString(), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, clientMessageText.orderContactText(order)).withSla(orderControlStartedAt(order), null, null, null);
    }

    List<ManagerControlConcreteItemResponse> paymentIntegrityIssueExamples(Manager manager, LocalDate today, int limit) {
        return orderRepository.findPaymentIntegrityIssuesByManager(manager, PAYMENT_AUTOMATION_STATUSES, PageRequest.of(0, Math.max(1, limit))).stream().map(order -> {
            String status = order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
            String paidAt = order.getPayDay() == null ? "ранее" : order.getPayDay().toString();
            String reason = "Проблема: заказ №" + order.getId() + " полностью оплачен " + paidAt + ", но повторно находится в статусе «" + status + "». " + "Есть риск повторного счета клиенту. Решение: нажмите «Починить» — " + "система остановит платежные очереди, закроет только лишние неоплаченные ссылки " + "и восстановит статус «Оплачено». Следующий заказ не изменяется.";
            return new ManagerControlConcreteItemResponse(null, ENTITY_ORDER_PAYMENT_INTEGRITY, order.getId(), orderTitle(order, "Заказ #" + order.getId()), "Оплачен " + paidAt + " · заказ №" + order.getId(), status, order.getPayDay() == null ? null : daysSince(order.getPayDay(), today), reason, orderTargetUrl(order), null, orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(order.getStatusChangedAt(), null, null, null);
        }).toList();
    }

    LocalDateTime orderControlStartedAt(OrderDTOList order) {
        if (order == null) {
            return null;
        }
        LocalDateTime statusChangedAt = order.getStatusChangedAt();
        if (statusChangedAt == null && order.getChanged() != null) {
            statusChangedAt = order.getChanged().atStartOfDay();
        }
        return statusChangedAt == null ? null : statusChangedAt.plusDays(managerControlOrderThresholdDays(order.getStatus()));
    }

    String orderManagerReason(OrderDTOList order, LocalDate today) {
        String status = safe(order == null ? null : order.getStatus());
        long days = order == null || order.getChanged() == null ? 0 : daysSince(order.getChanged(), today);
        String age = days > 0 ? days + " дн" : "сегодня";
        String controlReason = orderControlReason(order);
        return switch(status) {
            case "На проверке" ->
                "Клиент не проверил шаблоны " + age + ". " + controlReason + " Если доступна кнопка «Починить», сначала нажмите ее: система попробует восстановить автоответчик. " + "Если починка недоступна или не помогла, скопируйте текст, откройте чат, отправьте ссылку на проверку и нажмите «Отправлено».";
            case "Опубликовано" ->
                "Заказ опубликован " + age + ", нужна ручная проверка оплаты/счета. " + controlReason + " Отправьте клиенту сообщение или закройте причину.";
            case "Ожидает общего счета" ->
                "Заказ ожидает общего счета " + age + ". " + controlReason + " Проверьте, что заказ попал в общий счет или почему счет не сформирован.";
            case "Выставлен счет" ->
                "Оплаты нет. " + controlReason + " Отправьте напоминание клиенту; после отправки заказ уйдет в «Напоминание».";
            case "Напоминание" ->
                "Клиент не оплатил после напоминания. " + controlReason + " Повторите напоминание или укажите, почему откладываем.";
            case "Требует внимания" ->
                "Заказ требует внимания менеджера " + age + ". " + controlReason + " Откройте заказ, устраните причину и зафиксируйте действие.";
            case "Не оплачено" ->
                "Заказ отмечен как неоплаченный " + age + ". " + controlReason + " Проверьте историю общения и решите: повторить контакт, оставить в работе или архивировать.";
            case "В проверку" ->
                "Доставка/ссылка на проверку зависла " + age + ". " + controlReason + " Проверьте чат и отправку ссылки, затем отметьте действие.";
            case "Новый" ->
                "Новый заказ без движения " + age + ". " + controlReason + " Проверьте, что клиенту отправлен первый текст/запрос и задача не потерялась.";
            default ->
                "Статус «" + (status.isBlank() ? "не указан" : status) + "» без движения " + age + ". " + controlReason + " Откройте заказ, проверьте следующий шаг и зафиксируйте действие.";
        };
    }

    String orderControlReason(OrderDTOList order) {
        if (order == null) {
            return "Почему в контроле: заказ попал в просрочку, но детали автоответчика недоступны.";
        }
        if (order.getClientMessageStatus() != null) {
            var clientMessageStatus = order.getClientMessageStatus();
            String label = safe(clientMessageStatus.label());
            String errorCode = safe(clientMessageStatus.errorCode()).toLowerCase(Locale.ROOT);
            String error = safe(clientMessageStatus.errorMessage());
            if ("rate_limited".equals(errorCode) && hasHealthyActiveClientMessageQueue(order)) {
                String nextAttempt = clientMessageStatus.nextAttemptAt().toString();
                return "Очередь автоответчика исправна. Следующий слот отправки: " + nextAttempt + ". Ручное действие до этого времени не требуется.";
            }
            if (!error.isBlank()) {
                return clientMessageControlErrorReason(error);
            }
            if (!label.isBlank()) {
                return "Почему в контроле: автоответчик не закрыл задачу — " + label + ".";
            }
        }
        String bindingReason = chatBindingControlReason(order);
        if (!bindingReason.isBlank()) {
            return "Почему в контроле: автоответчик не может отправить сообщение — " + bindingReason + ".";
        }
        String status = safe(order.getStatus());
        if (PAYMENT_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активного или успешного автонапоминания об оплате.";
        }
        if (REVIEW_CHECK_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активного или успешного автонапоминания о проверке шаблонов.";
        }
        if (DELIVERY_RETRY_AUTOMATION_STATUSES.contains(status)) {
            return "Почему в контроле: для заказа нет активной или успешной автодоставки ссылки.";
        }
        if (CLIENT_TEXT_AUTOMATION_STATUSES.contains(status) && order.isWaitingForClient()) {
            return "Почему в контроле: клиентский текст ожидается, но нет активного или успешного автозапроса.";
        }
        return "Почему в контроле: заказ просрочен, автоматическое действие не найдено или не применимо.";
    }

    boolean hasHealthyActiveClientMessageQueue(OrderDTOList order) {
        if (order == null || order.getClientMessageStatus() == null) {
            return false;
        }
        var status = order.getClientMessageStatus();
        if (!"scheduled".equalsIgnoreCase(safe(status.state())) || status.nextAttemptAt() == null || status.consecutiveFailures() > 0) {
            return false;
        }
        String errorCode = safe(status.errorCode()).toLowerCase(Locale.ROOT);
        return errorCode.isBlank() || "rate_limited".equals(errorCode);
    }

    String clientMessageControlErrorReason(String error) {
        String cleaned = safe(error);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.contains("чат") && lower.contains("не привязан")) {
            return "Почему в контроле: автоответчик не может отправить сообщение — чат компании не привязан к боту. " + "Автопочинка недоступна: привяжите чат компании к боту или отправьте сообщение вручную.";
        }
        return "Почему в контроле: автоответчик не обработал заказ — " + cleaned + ".";
    }

    String chatBindingControlReason(OrderDTOList order) {
        String chat = safe(order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (chat.isBlank()) {
            return "";
        }
        if (isWhatsAppChat(chat) && safe(order.getGroupId()).isBlank()) {
            return "WhatsApp-группа из ссылки не привязана к компании";
        }
        if (isTelegramChat(chat) && order.getTelegramGroupChatId() == null) {
            return "Telegram-группа из ссылки не привязана к компании";
        }
        if (isMaxChat(chat) && order.getMaxGroupChatId() == null) {
            return "MAX-группа из ссылки не привязана к компании";
        }
        return "";
    }

    List<ManagerControlConcreteItemResponse> chatBindingIssueExamples(Manager manager, LocalDate today, int limit) {
        return companyRepository.findChatBindingIssuesByManager(manager).stream().limit(Math.max(1, limit)).map(company -> companyChatBindingIssueExample(company, today, manager)).toList();
    }

    ManagerControlConcreteItemResponse companyChatBindingIssueExample(Company company, LocalDate today, Manager manager) {
        String status = company == null || company.getStatus() == null ? "" : safe(company.getStatus().getTitle());
        String specialistName = concretePresenter.companySpecialistName(company);
        return new ManagerControlConcreteItemResponse(null, "COMPANY_CHAT_BINDING", company.getId(), safe(company.getTitle()).isBlank() ? "Компания #" + company.getId() : company.getTitle(), specialistName.isBlank() ? "Специалист не назначен" : specialistName, status, company.getUpdateStatus() == null ? null : daysSince(company.getUpdateStatus(), today), "Почему в контроле: " + companyChatBindingReason(company) + ". Сохраните правильную ссылку на чат и нажмите «Починить».", companyTargetUrl(manager, company), null, company.getUrlChat(), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null, null, null, null, null, null, null, null, specialistName);
    }

    String companyChatBindingReason(Company company) {
        String chat = safe(company == null ? null : company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat))
            return "WhatsApp-группа не привязана к компании";
        if (isTelegramChat(chat))
            return "Telegram-группа не привязана к компании";
        if (isMaxChat(chat))
            return "MAX-группа не привязана к компании";
        return "ссылка на чат не распознана";
    }

    boolean isWhatsAppChat(String chat) {
        return orderAutomationDiagnostics.isWhatsAppChat(chat);
    }

    boolean isTelegramChat(String chat) {
        return orderAutomationDiagnostics.isTelegramChat(chat);
    }

    boolean isMaxChat(String chat) {
        return orderAutomationDiagnostics.isMaxChat(chat);
    }

    String orderChatUrl(OrderDTOList order) {
        String chat = safe(order.getCompanyUrlChat());
        if (!chat.isBlank()) {
            return chat;
        }
        String phone = safe(order.getCompanyTelephone());
        return phone.isBlank() ? null : "tel:" + phone;
    }

    String orderTargetUrl(OrderDTOList order, Manager manager) {
        if (order == null) {
            return "/orders";
        }
        String keyword = safe(order.getCompanyTitle());
        if (keyword.isBlank()) {
            keyword = order.getId() == null ? "" : String.valueOf(order.getId());
        }
        List<String> params = new ArrayList<>();
        params.add("status=" + encode(safe(order.getStatus()).isBlank() ? "Все" : order.getStatus()));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        if (!keyword.isBlank()) {
            params.add("keyword=" + encode(keyword));
        }
        if (manager != null && manager.getId() != null) {
            params.add("managerId=" + manager.getId());
        }
        params.add("control=manager-overdue");
        return "/orders?" + String.join("&", params);
    }

    String orderSubtitle(OrderDTOList order) {
        List<String> parts = new ArrayList<>();
        if (!safe(order.getFilialTitle()).isBlank()) {
            parts.add(order.getFilialTitle());
        }
        if (order.getAmount() != null && order.getAmount() > 0) {
            parts.add(order.getAmount() + " шт.");
        }
        if (order.getSum() != null) {
            parts.add(order.getSum() + " руб.");
        }
        if (order.isWaitingForClient()) {
            parts.add("ждет клиента");
        }
        return String.join(" · ", parts);
    }

    List<ManagerControlConcreteItemResponse> workerActionExamples(Manager manager, LocalDate today, int limit) {
        List<ManagerControlConcreteItemResponse> examples = new ArrayList<>();
        examples.addAll(workerStaleOrderExamples(manager, "Новый", today, limit));
        examples.addAll(workerStaleOrderExamples(manager, "Коррекция", today, limit));
        examples.addAll(nagulReviewExamples(manager, today, limit));
        examples.addAll(recoveryTaskExamples(manager, today, limit));
        examples.addAll(publishReviewExamples(manager, today, limit));
        examples.addAll(badReviewTaskExamples(manager, today, limit));
        return examples.stream().sorted(Comparator.comparing((ManagerControlConcreteItemResponse item) -> item.ageDays() == null ? 0L : item.ageDays(), Comparator.reverseOrder()).thenComparing(ManagerControlConcreteItemResponse::title, String.CASE_INSENSITIVE_ORDER)).limit(limit).toList();
    }

    List<ManagerControlConcreteItemResponse> workerStaleOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return workerStaleOrderEntriesForControl(workerIds, status, today).stream().map(entry -> workerStaleOrderExample(entry.order(), status, today, entry.clientTextDecision())).limit(limit).toList();
    }

    ManagerControlConcreteItemResponse workerStaleOrderExample(Order order, String status, LocalDate today, WorkerClientTextDecision clientTextDecision) {
        String entityType = "Коррекция".equals(status) ? ENTITY_WORKER_ORDER_CORRECT : ENTITY_WORKER_ORDER_NEW;
        String contactText = ENTITY_WORKER_ORDER_NEW.equals(entityType) && order != null && order.isWaitingForClient() ? clientMessageText.clientTextContactText(order) : null;
        String reason = clientTextDecision == null || safe(clientTextDecision.reason()).isBlank() ? workerOrderReason(order, status, today) : clientTextDecision.reason();
        return new ManagerControlConcreteItemResponse(null, entityType, order.getId(), orderTitle(order, "Заказ #" + order.getId()), workerOrderSubtitle(order, today), status, daysSince(order.getChanged(), today), reason, orderTargetUrl(order), clientMessageText.orderDetailsId(null, order), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, contactText).withSla(workerOrderControlStartedAt(order), null, null, null);
    }

    LocalDateTime workerOrderControlStartedAt(Order order) {
        if (order == null) {
            return null;
        }
        LocalDateTime statusChangedAt = order.getStatusChangedAt();
        if (statusChangedAt == null && order.getChanged() != null) {
            statusChangedAt = order.getChanged().atStartOfDay();
        }
        return statusChangedAt == null ? null : statusChangedAt.plusDays(WORKER_ORDER_UNCHANGED_DAYS);
    }

    List<WorkerOrderControlEntry> workerStaleOrderEntriesForControl(List<Long> workerIds, String status, LocalDate today) {
        return orderAutomationDiagnostics.workerStaleOrderEntriesForControl(workerIds, status, today);
    }

    String workerOrderSubtitle(Order order, LocalDate today) {
        List<String> parts = new ArrayList<>();
        String workerName = workerName(order == null ? null : order.getWorker());
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (order != null && order.getChanged() != null) {
            parts.add("без изменений " + daysSince(order.getChanged(), today) + " дн.");
        }
        if (order != null && order.isWaitingForClient()) {
            parts.add("ждет клиента");
        }
        if (order != null && order.getAmount() > 0) {
            parts.add(order.getAmount() + " шт.");
        }
        return String.join(" · ", parts);
    }

    String workerOrderReason(Order order, String status, LocalDate today) {
        long days = daysSince(order == null ? null : order.getChanged(), today);
        return "Заказ специалиста в статусе \"" + status + "\" без изменений " + days + " дн. Проверьте работу специалиста и устраните просрочку.";
    }

    List<ManagerControlConcreteItemResponse> recoveryTaskExamples(Manager manager, LocalDate today, int limit) {
        return reviewRecoveryTaskService.getDueTasksToManager(manager, managerControlWorkerTaskOverdueDate(today), "", PageRequest.of(0, Math.max(1, limit))).getContent().stream().map(task -> recoveryTaskExample(task, today)).toList();
    }

    ManagerControlConcreteItemResponse recoveryTaskExample(ReviewRecoveryTask task, LocalDate today) {
        Order order = task.getOrder();
        return new ManagerControlConcreteItemResponse(null, "RECOVERY_TASK", task.getId(), orderTitle(order, "Восстановление #" + task.getId()), taskSubtitle("Восстановление", task.getWorker(), task.getScheduledDate(), today), task.getStatus() == null ? null : task.getStatus().name(), daysSince(task.getScheduledDate(), today), "Задача восстановления требует проверки менеджера", orderTargetUrl(order), null, orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(startOfDay(task.getScheduledDate()), null, null, null);
    }

    List<ManagerControlConcreteItemResponse> nagulReviewExamples(Manager manager, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findManagerControlNagulReviewsByWorkerIds(workerIds, managerControlPublicationOverdueDate(today), PageRequest.of(0, Math.max(1, limit))).stream().map(review -> nagulReviewExample(review, today)).toList();
    }

    ManagerControlConcreteItemResponse nagulReviewExample(Review review, LocalDate today) {
        Order order = reviewOrder(review);
        return new ManagerControlConcreteItemResponse(null, ENTITY_NAGUL_REVIEW, review.getId(), orderTitle(order, "Отзыв #" + review.getId()), taskSubtitle("Выгул", review.getWorker(), review.getPublishedDate(), today), "Выгул", daysSince(review.getPublishedDate(), today), nagulReviewReason(review, today), orderTargetUrl(order), review.getOrderDetails() == null || review.getOrderDetails().getId() == null ? null : review.getOrderDetails().getId().toString(), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(startOfDay(review.getPublishedDate()), null, null, null);
    }

    String nagulReviewReason(Review review, LocalDate today) {
        long days = daysSince(review == null ? null : review.getPublishedDate(), today);
        return "Выгул просрочен " + days + " дн. Проверьте карточку отзыва и специалиста.";
    }

    List<ManagerControlConcreteItemResponse> publishReviewExamples(Manager manager, LocalDate today, int limit) {
        List<Long> workerIds = workerIds(manager);
        if (workerIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findManagerControlPublishReviewsByWorkerIds(workerIds, managerControlPublicationOverdueDate(today), PageRequest.of(0, Math.max(1, limit))).stream().map(review -> publishReviewExample(review, today)).toList();
    }

    List<ManagerControlConcreteItemResponse> publicationDateIssueExamples(Manager manager, int limit) {
        return reviewRepository.findPublicationDateIssuesByManager(manager, PageRequest.of(0, Math.max(1, limit))).stream().map(this::publicationDateIssueExample).toList();
    }

    ManagerControlConcreteItemResponse publicationDateIssueExample(Review review) {
        Order order = reviewOrder(review);
        LocalDateTime observedAt = order == null ? null : order.getStatusChangedAt();
        String reason = "Проблема: заказ находится в «Публикации», но у неопубликованного отзыва не назначена дата. " + "Решение: нажмите «Починить» — система проверит тексты и аккаунты и назначит даты. " + "Если починка не пройдет, откройте заказ по ссылке и выполните указанную в ошибке рекомендацию.";
        return new ManagerControlConcreteItemResponse(null, ENTITY_PUBLICATION_DATE_REVIEW, review.getId(), orderTitle(order, "Отзыв #" + review.getId()), taskSubtitle("Публикация", review.getWorker(), null, LocalDate.now()), "Нет даты публикации", observedAt == null ? null : daysSince(observedAt.toLocalDate(), LocalDate.now()), reason, orderTargetUrl(order), review.getOrderDetails() == null || review.getOrderDetails().getId() == null ? null : review.getOrderDetails().getId().toString(), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(observedAt, null, null, null);
    }

    ManagerControlConcreteItemResponse publishReviewExample(Review review, LocalDate today) {
        Order order = reviewOrder(review);
        return new ManagerControlConcreteItemResponse(null, ENTITY_PUBLISH_REVIEW, review.getId(), orderTitle(order, "Отзыв #" + review.getId()), publishReviewSubtitle(review, order, today), "Публикация", daysSince(review.getPublishedDate(), today), publishReviewReason(review, today), orderTargetUrl(order), review.getOrderDetails() == null || review.getOrderDetails().getId() == null ? null : review.getOrderDetails().getId().toString(), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(startOfDay(review.getPublishedDate()), null, null, null);
    }

    Order reviewOrder(Review review) {
        return review == null || review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
    }

    String publishReviewSubtitle(Review review, Order order, LocalDate today) {
        List<String> parts = new ArrayList<>();
        String workerName = workerName(review == null ? null : review.getWorker());
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (review != null && review.getPublishedDate() != null) {
            parts.add("план " + review.getPublishedDate());
            long days = daysSince(review.getPublishedDate(), today);
            if (days > 0) {
                parts.add(days + " дн.");
            }
        }
        if (order != null && order.getStatus() != null && !safe(order.getStatus().getTitle()).isBlank()) {
            parts.add("заказ " + order.getStatus().getTitle());
        }
        return String.join(" · ", parts);
    }

    String publishReviewReason(Review review, LocalDate today) {
        long days = daysSince(review == null ? null : review.getPublishedDate(), today);
        String overdue = days > 0 ? days + " дн." : "сегодня";
        return "Публикация просрочена " + overdue + ". Проверьте карточку отзыва и специалиста.";
    }

    List<ManagerControlConcreteItemResponse> badReviewTaskExamples(Manager manager, LocalDate today, int limit) {
        return badReviewTaskService.getDueTasksToManager(manager, managerControlWorkerTaskOverdueDate(today), "", PageRequest.of(0, Math.max(1, limit))).getContent().stream().map(task -> badReviewTaskExample(task, today)).toList();
    }

    ManagerControlConcreteItemResponse badReviewTaskExample(BadReviewTask task, LocalDate today) {
        Order order = task.getOrder();
        return new ManagerControlConcreteItemResponse(null, "BAD_REVIEW_TASK", task.getId(), orderTitle(order, "Плохой отзыв #" + task.getId()), taskSubtitle("Плохие", task.getWorker(), task.getScheduledDate(), today), task.getStatus() == null ? null : task.getStatus().name(), daysSince(task.getScheduledDate(), today), badReviewTaskReason(task, today), orderTargetUrl(order), null, orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null).withSla(startOfDay(task.getScheduledDate()), null, null, null);
    }

    LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    String badReviewTaskReason(BadReviewTask task, LocalDate today) {
        List<String> parts = new ArrayList<>();
        long days = daysSince(task == null ? null : task.getScheduledDate(), today);
        if (task != null && task.getScheduledDate() != null) {
            parts.add(days > 0 ? "Плохой отзыв просрочен " + days + " дн., план был " + task.getScheduledDate() : "Плохой отзыв запланирован на сегодня");
        } else {
            parts.add("Плохой отзыв без плановой даты");
        }
        if (task != null && (task.getOriginalRating() != null || task.getTargetRating() != null)) {
            String from = task.getOriginalRating() == null ? "?" : task.getOriginalRating().toString();
            String to = task.getTargetRating() == null ? "?" : task.getTargetRating().toString();
            parts.add("рейтинг " + from + " -> " + to);
        }
        String comment = compact(task == null ? null : task.getComment(), 140);
        if (!comment.isBlank()) {
            parts.add("комментарий: " + comment);
        }
        parts.add("Проверьте карточку отзыва и работу специалиста.");
        return String.join(". ", parts);
    }

    String taskSubtitle(String type, Worker worker, LocalDate scheduledDate, LocalDate today) {
        List<String> parts = new ArrayList<>();
        parts.add(type);
        String workerName = workerName(worker);
        if (!workerName.isBlank()) {
            parts.add(workerName);
        }
        if (scheduledDate != null) {
            parts.add("план " + scheduledDate);
            long days = daysSince(scheduledDate, today);
            if (days > 0) {
                parts.add(days + " дн.");
            }
        }
        return String.join(" · ", parts);
    }

    String workerName(Worker worker) {
        if (worker == null || worker.getUser() == null) {
            return "";
        }
        String fio = safe(worker.getUser().getFio());
        return fio.isBlank() ? safe(worker.getUser().getUsername()) : fio;
    }

    String companyWorkerName(Company company) {
        if (company == null || company.getWorkers() == null || company.getWorkers().isEmpty()) {
            return "Исполнитель не назначен";
        }
        return company.getWorkers().stream().map(this::workerName).filter(value -> !safe(value).isBlank()).sorted(String.CASE_INSENSITIVE_ORDER).findFirst().orElse("Исполнитель не назначен");
    }

    String orderTitle(Order order, String fallback) {
        if (order == null) {
            return fallback;
        }
        String company = order.getCompany() == null ? "" : safe(order.getCompany().getTitle());
        String filial = order.getFilial() == null ? "" : safe(order.getFilial().getTitle());
        String title = List.of(company, filial).stream().filter(value -> !value.isBlank()).collect(Collectors.joining(" - "));
        return title.isBlank() ? "Заказ #" + order.getId() : title;
    }

    String orderTargetUrl(Order order) {
        if (order == null || order.getId() == null) {
            return "/worker";
        }
        Long companyId = order.getCompany() == null ? null : order.getCompany().getId();
        if (companyId != null) {
            return "/orders/" + companyId + "/" + order.getId();
        }
        return "/orders?keyword=" + encode(String.valueOf(order.getId()));
    }

    String orderChatUrl(Order order) {
        if (order == null || order.getCompany() == null) {
            return null;
        }
        String chat = safe(order.getCompany().getUrlChat());
        if (!chat.isBlank()) {
            return chat;
        }
        String phone = safe(order.getCompany().getTelephone());
        return phone.isBlank() ? null : "tel:" + phone;
    }

    List<ManagerControlConcreteItemResponse> riskExamples(Manager manager, int limit) {
        List<Long> userIds = workerUserIds(manager);
        if (userIds.isEmpty()) {
            return List.of();
        }
        return riskIncidentRepository.findByWorkerUserIdInAndStatusOrderByCreatedAtDesc(userIds, WorkerRiskIncidentStatus.OPEN, PageRequest.of(0, limit)).getContent().stream().map(this::riskExample).toList();
    }

    ManagerControlConcreteItemResponse riskExample(WorkerRiskIncident incident) {
        Long targetId = incident.getOrderId() != null ? incident.getOrderId() : incident.getEntityId();
        boolean explanationRequested = incident.getResolutionAction() == WorkerRiskResolutionAction.EXPLANATION_REQUESTED || incident.getExplanationRequestedAt() != null || incident.getExplanationPromptedAt() != null || incident.getWorkerExplanationAt() != null;
        LocalDateTime notificationStartedAt = explanationRequested ? firstNonNullTime(incident.getExplanationRequestedAt(), incident.getCreatedAt()) : null;
        return new ManagerControlConcreteItemResponse(null, "RISK", incident.getId(), safe(incident.getTitle()).isBlank() ? "Риск специалиста #" + incident.getId() : incident.getTitle(), safe(incident.getWorkerName()).isBlank() ? incident.getWorkerUsername() : incident.getWorkerName(), incident.getLevel() == null ? null : incident.getLevel().name(), incident.getCreatedAt() == null ? null : Math.max(0, ChronoUnit.DAYS.between(incident.getCreatedAt().toLocalDate(), LocalDate.now())), limit(safe(incident.getMessage()), 500), targetId == null ? "/worker/risk" : "/worker/risk?targetId=" + targetId, null, null, null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, notificationStartedAt, notificationStartedAt, incident.getExplanationAcceptedAt(), incident.getExplanationAcceptedAt() == null ? null : incident.getWorkerUserId(), null, null, incident.getResolutionAction() == null ? null : incident.getResolutionAction().name(), incident.getWorkerExplanation(), incident.getWorkerExplanationAt(), incident.getPenaltyPoints(), incident.getRollbackStatus() == null ? null : incident.getRollbackStatus().name(), incident.getRollbackMessage(), concretePresenter.canRollbackRiskIncident(incident), safe(incident.getWorkerName()).isBlank() ? incident.getWorkerUsername() : incident.getWorkerName(), null, null, null, null).withSla(incident.getCreatedAt(), null, null, null);
    }

    List<Company> telegramChatIssueCompanies(Manager manager, int limit) {
        Map<Long, Company> companies = new LinkedHashMap<>();
        companyRepository.findTelegramChatIssueCompanies(manager, PageRequest.of(0, Math.max(1, limit))).forEach(company -> addTelegramIssueCompany(companies, company));
        if (companies.size() < limit) {
            paymentLinkRepository.findTelegramSuccessNotificationErrorsByManager(manager).stream().map(PaymentLink::getOrder).filter(Objects::nonNull).map(Order::getCompany).filter(Objects::nonNull).limit(Math.max(0, limit - companies.size())).forEach(company -> addTelegramIssueCompany(companies, company));
        }
        return new ArrayList<>(companies.values());
    }

    void addTelegramIssueCompany(Map<Long, Company> companies, Company company) {
        if (company == null || company.getId() == null || company.getTelegramGroupChatId() == null) {
            return;
        }
        companies.putIfAbsent(company.getId(), company);
    }

    List<ManagerControlConcreteItemResponse> telegramChatIssueExamples(Manager manager, int limit) {
        return telegramChatIssueCompanies(manager, limit).stream().map(company -> telegramChatIssueExample(manager, company)).toList();
    }

    ManagerControlConcreteItemResponse telegramChatIssueExample(Manager manager, Company company) {
        String specialistName = companyWorkerName(company);
        return new ManagerControlConcreteItemResponse(null, ENTITY_TELEGRAM_CHAT, company.getId(), safe(company.getTitle()).isBlank() ? "Компания #" + company.getId() : company.getTitle(), specialistName, "Telegram", null, "Telegram-отправка по компании получила ошибку. Если группа стала супергруппой, нажмите «Починить»: система запросит новый chat_id у Telegram и обновит привязку.", companyTargetUrl(manager, company), null, normalizedChatUrl(company.getUrlChat()), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null, null, null, null, null, null, specialistName);
    }

    List<ManagerControlConcreteItemResponse> unansweredClientMessageExamples(Manager manager, int limit) {
        return clientChatMessageTrackerService.dueExamples(manager, limit).stream().map(this::unansweredClientMessageExample).toList();
    }

    ManagerControlConcreteItemResponse unansweredClientMessageExample(ClientChatUnansweredExample example) {
        String companyTitle = safe(example.companyTitle()).isBlank() ? "Компания не определена" : example.companyTitle();
        String sender = safe(example.senderName()).isBlank() ? "Клиент" : example.senderName();
        String waiting = waitingLabel(example.waitingMinutes());
        LocalDateTime firstObservedAt = LocalDateTime.now().minusMinutes(Math.max(0, example.waitingMinutes()));
        return new ManagerControlConcreteItemResponse(null, ENTITY_CLIENT_CHAT_UNANSWERED, example.id(), companyTitle, platformLabel(example.platform()) + " · " + safe(example.chatTitle()), waiting, Math.max(0, example.waitingMinutes() / (60L * 24L)), sender + " написал " + waiting + ". Последнее сообщение: " + compact(example.lastMessageText(), 260), example.targetUrl(), null, example.chatUrl(), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null, null, null, null, null, compact(example.lastMessageText(), 1000), example.specialistName()).withSla(firstObservedAt, null, null, null);
    }

    String platformLabel(com.hunt.otziv.client_chat_control.model.ClientChatPlatform platform) {
        if (platform == null) {
            return "Чат";
        }
        return switch(platform) {
            case TELEGRAM ->
                "Telegram";
            case WHATSAPP ->
                "WhatsApp";
            case MAX ->
                "MAX";
        };
    }

    String waitingLabel(long minutes) {
        long safeMinutes = Math.max(0, minutes);
        if (safeMinutes < 60) {
            return safeMinutes + " мин. без ответа";
        }
        long hours = safeMinutes / 60;
        long restMinutes = safeMinutes % 60;
        if (hours < 24) {
            return restMinutes == 0 ? hours + " ч. без ответа" : hours + " ч. " + restMinutes + " мин. без ответа";
        }
        long days = hours / 24;
        long restHours = hours % 24;
        return restHours == 0 ? days + " дн. без ответа" : days + " дн. " + restHours + " ч. без ответа";
    }

    String companyTargetUrl(Manager manager, Company company) {
        StringBuilder url = new StringBuilder(ordersUrl(manager, null));
        String title = safe(company == null ? null : company.getTitle());
        if (!title.isBlank()) {
            url.append("&keyword=").append(encode(title));
        }
        return url.toString();
    }

    List<ManagerControlConcreteItemResponse> automationFailureExamples(Manager manager, int limit) {
        return managerAutomationFailureService.issues(manager, limit).stream().map(issue -> new ManagerControlConcreteItemResponse(null, issue.entityType(), issue.entityId(), issue.title(), issue.subtitle(), issue.status(), issue.firstObservedAt() == null ? null : Math.max(0, ChronoUnit.DAYS.between(issue.firstObservedAt().toLocalDate(), LocalDate.now())), issue.reason(), issue.targetUrl(), null, issue.chatUrl(), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, issue.lastAttemptAt(), null, null).withSla(issue.firstObservedAt(), null, null, null)).toList();
    }

    List<ManagerControlConcreteItemResponse> suspiciousClientClosureExamples(Manager manager, int limit) {
        return clientChatMessageTrackerService.auditExamples(manager, limit).stream().map(this::suspiciousClientClosureExample).toList();
    }

    ManagerControlConcreteItemResponse suspiciousClientClosureExample(ClientChatUnansweredExample example) {
        String companyTitle = safe(example.companyTitle()).isBlank() ? "Компания не определена" : example.companyTitle();
        String sender = safe(example.senderName()).isBlank() ? "Клиент" : example.senderName();
        return new ManagerControlConcreteItemResponse(null, ENTITY_CLIENT_CHAT_AUDIT, example.id(), companyTitle, platformLabel(example.platform()) + " · проверьте полноту ответа", "Нужен аудит", Math.max(0, example.waitingMinutes() / (60L * 24L)), sender + ": " + compact(example.lastMessageText(), 300) + ". Укажите в комментарии найденный ответ или выполненное действие.", example.targetUrl(), null, example.chatUrl(), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null, null, null, null, null, compact(example.lastMessageText(), 1000), example.specialistName());
    }

    String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    String compact(String value, int maxLength) {
        String trimmed = safe(value).replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    LocalDate managerControlWorkerTaskOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(1);
    }

    LocalDate managerControlPublicationOverdueDate(LocalDate today) {
        return (today == null ? LocalDate.now() : today).minusDays(1);
    }

    LocalDate managerControlOrderCutoff(String status, LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;
        return base.minusDays(managerControlOrderThresholdDays(status));
    }

    int managerControlOrderThresholdDays(String status) {
        if (REVIEW_CHECK_AUTOMATION_STATUSES.contains(safe(status))) {
            return reviewCheckIntervalDays();
        }
        return OVERDUE_NOTIFICATION_DAYS + 1;
    }

    int reviewCheckIntervalDays() {
        if (appSettingService == null) {
            return ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS;
        }
        return Math.max(1, appSettingService.getInt(AppSettingService.CLIENT_MESSAGES_REVIEW_CHECK_INTERVAL_DAYS, ScheduledClientMessageService.DEFAULT_REMINDER_INTERVAL_DAYS));
    }

    List<ManagerControlOverdueStatusResponse> overdueStatuses(Manager manager, LocalDate today) {
        LocalDate cutoff = today.minusDays(OVERDUE_NOTIFICATION_DAYS + 1L);
        Map<String, Long> snoozedByStatus = snoozedOrderCountsByStatus(manager, today);
        Map<String, ManagerControlOverdueStatusResponse> statusesByName = orderRepository.summarizeManagerControlOverdueOrdersByManager(manager, cutoff, OVERDUE_IGNORED_STATUSES, COMMON_INVOICE_CONTROL_STATUSES, PAYMENT_AUTOMATION_STATUSES, PAYMENT_AUTOMATION_SCENARIOS, REVIEW_CHECK_AUTOMATION_STATUSES, REVIEW_CHECK_SCENARIOS, DELIVERY_RETRY_AUTOMATION_STATUSES, DELIVERY_RETRY_SCENARIOS, CLIENT_TEXT_AUTOMATION_STATUSES, CLIENT_TEXT_SCENARIOS, ScheduledMessageStateStatus.ACTIVE, ScheduledMessageStateStatus.DONE).stream().map(row -> {
            String status = rowString(row, 0, "Без статуса");
            long adjustedCount = Math.max(0, rowLong(row, 1) - snoozedByStatus.getOrDefault(status, 0L));
            return new ManagerControlOverdueStatusResponse(status, adjustedCount, daysSince(rowDate(row, 2), today), ordersUrl(manager, status));
        }).filter(status -> status.count() > 0).collect(Collectors.toMap(ManagerControlOverdueStatusResponse::status, Function.identity(), (left, right) -> right, LinkedHashMap::new));
        addDynamicOverdueStatus(statusesByName, manager, "На проверке", today, snoozedByStatus);
        return statusesByName.values().stream().sorted(Comparator.comparingInt((ManagerControlOverdueStatusResponse status) -> orderStatusDisplayRank(status.status())).thenComparing(ManagerControlOverdueStatusResponse::status, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    void addDynamicOverdueStatus(Map<String, ManagerControlOverdueStatusResponse> statusesByName, Manager manager, String status, LocalDate today, Map<String, Long> snoozedByStatus) {
        LocalDate cutoff = managerControlOrderCutoff(status, today);
        Page<OrderDTOList> page = orderService.getManagerControlOverdueOrdersByManager(manager, "", status, cutoff, OVERDUE_IGNORED_STATUSES, COMMON_INVOICE_CONTROL_STATUSES, PAYMENT_AUTOMATION_STATUSES, PAYMENT_AUTOMATION_SCENARIOS, REVIEW_CHECK_AUTOMATION_STATUSES, REVIEW_CHECK_SCENARIOS, DELIVERY_RETRY_AUTOMATION_STATUSES, DELIVERY_RETRY_SCENARIOS, CLIENT_TEXT_AUTOMATION_STATUSES, CLIENT_TEXT_SCENARIOS, ScheduledMessageStateStatus.ACTIVE, ScheduledMessageStateStatus.DONE, 0, 1, "desc");
        if (page == null) {
            return;
        }
        long adjustedCount = Math.max(0, page.getTotalElements() - snoozedByStatus.getOrDefault(status, 0L));
        if (adjustedCount <= 0) {
            statusesByName.remove(status);
            return;
        }
        LocalDate oldestChanged = page.getContent().stream().map(OrderDTOList::getChanged).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(cutoff);
        statusesByName.put(status, new ManagerControlOverdueStatusResponse(status, adjustedCount, daysSince(oldestChanged, today), ordersUrl(manager, status)));
    }

    Map<String, Long> snoozedOrderCountsByStatus(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager).map(control -> dailyControlConcreteItemRepository.findByControlAndEntityTypeAndFollowUpAtAfter(control, "ORDER", LocalDateTime.now()).stream().filter(item -> !safe(item.getStatusLabel()).isBlank()).collect(Collectors.groupingBy(ManagerDailyControlConcreteItem::getStatusLabel, Collectors.counting()))).orElse(Map.of());
    }

    Set<Long> snoozedOrderIds(Manager manager, LocalDate today) {
        return dailyControlRepository.findByControlDateAndManager(today, manager).map(control -> dailyControlConcreteItemRepository.findByControlAndEntityTypeAndFollowUpAtAfter(control, "ORDER", LocalDateTime.now()).stream().map(ManagerDailyControlConcreteItem::getEntityId).filter(Objects::nonNull).collect(Collectors.toSet())).orElse(Set.of());
    }

    int orderStatusDisplayRank(String status) {
        int index = ORDER_STATUS_DISPLAY_ORDER.indexOf(status);
        return index >= 0 ? index : ORDER_STATUS_DISPLAY_ORDER.size();
    }

    List<Long> workerIds(Manager manager) {
        User user = manager.getUser();
        if (user == null || user.getWorkers() == null) {
            return List.of();
        }
        return user.getWorkers().stream().filter(Objects::nonNull).map(Worker::getId).filter(Objects::nonNull).distinct().toList();
    }

    List<Long> workerUserIds(Manager manager) {
        User user = manager.getUser();
        if (user == null || user.getWorkers() == null) {
            return List.of();
        }
        return user.getWorkers().stream().filter(Objects::nonNull).map(Worker::getUser).filter(Objects::nonNull).map(User::getId).filter(Objects::nonNull).distinct().toList();
    }

    long rowLong(Object[] row, int index) {
        if (row == null || index < 0 || index >= row.length) {
            return 0;
        }
        Object value = row[index];
        return value instanceof Number number ? number.longValue() : 0;
    }

    String rowString(Object[] row, int index, String fallback) {
        if (row == null || index < 0 || index >= row.length || row[index] == null) {
            return fallback;
        }
        String value = String.valueOf(row[index]).trim();
        return value.isBlank() ? fallback : value;
    }

    LocalDate rowDate(Object[] row, int index) {
        if (row == null || index < 0 || index >= row.length || row[index] == null) {
            return null;
        }
        Object value = row[index];
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return null;
    }

    long daysSince(LocalDate date, LocalDate today) {
        return date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(date, today));
    }

    String ordersUrl(Manager manager, String status) {
        StringBuilder url = new StringBuilder("/orders?managerId=").append(manager.getId()).append("&control=manager-overdue").append("&sortDirection=desc");
        if (status != null && !status.isBlank()) {
            url.append("&status=").append(encode(status));
        }
        return url.toString();
    }

    String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    String normalizedChatUrl(String value) {
        String url = safe(value);
        if (url.isBlank()) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    String safe(String value) {
        return value == null ? "" : value.trim();
    }

    LocalDateTime firstNonNullTime(LocalDateTime... values) {
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
