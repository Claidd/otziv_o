package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlAutomationRepairWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlChatRepairWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlRepairOutcome.*;
import static com.hunt.otziv.manager_control.service.ManagerControlItemActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlReminderWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayActions.*;
import static com.hunt.otziv.manager_control.service.ManagerControlBoardWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDailySnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlDayLifecycle.*;
import static com.hunt.otziv.manager_control.service.ManagerControlConcreteSnapshotWorkflow.*;
import static com.hunt.otziv.manager_control.service.ManagerControlProblemExamples.*;
import com.hunt.otziv.manager_control.service.ManagerControlOrderAutomationDiagnostics.WorkerClientTextDecision;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderPublicationApprovalService;
import com.hunt.otziv.payments.service.OrderPaymentIntegrityService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlRepairWorkflow {

    private final ManagerControlAutomationRepairWorkflow automationRepairWorkflow;

    private final ManagerControlChatRepairWorkflow chatRepairWorkflow;

    private final ManagerControlRepairOutcome repairOutcome;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlAccessPolicy accessPolicy;

    private final ManagerControlClientMessageText clientMessageText;

    private final ManagerControlConcretePresenter concretePresenter;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    private final ScheduledClientMessageService scheduledClientMessageService;

    private final ReviewRepository reviewRepository;

    private final OrderRepository orderRepository;

    private final CompanyRepository companyRepository;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    private final ManagerControlInvoiceRepairWorkflow invoiceRepairWorkflow;

    private final OrderPublicationApprovalService publicationApprovalService;

    private final ManagerDailyControlConcreteItemRepository dailyControlConcreteItemRepository;

    @Transactional
    public ManagerControlConcreteItemResponse repairConcreteItem(Long concreteItemId, Principal principal, Authentication authentication) {
        if (concreteItemId == null || concreteItemId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная карточка контроля");
        }
        ManagerDailyControlConcreteItem concreteItem = dailyControlConcreteItemRepository.findById(concreteItemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Карточка контроля не найдена"));
        ManagerDailyControl control = concreteItem.getControl();
        accessPolicy.requireControlAccess(control, principal, authentication);
        String entityType = safe(concreteItem.getEntityType());
        if (ManagerAutomationFailureService.ENTITY_AUTOMATION_FAILURE.equals(entityType) || ManagerAutomationFailureService.ENTITY_COMMON_INVOICE_AUTOMATION.equals(entityType)) {
            return repairAutomationFailureConcreteItem(concreteItem, control, principal);
        }
        if ("COMMON_INVOICE".equals(entityType)) {
            return repairCommonInvoiceConcreteItem(concreteItem, control, principal);
        }
        if (ENTITY_PUBLICATION_DATE_REVIEW.equals(entityType)) {
            return repairPublicationDateConcreteItem(concreteItem, control, principal);
        }
        if (ENTITY_TELEGRAM_CHAT.equals(entityType)) {
            return repairTelegramChatConcreteItem(concreteItem, control, principal);
        }
        if ("COMPANY_CHAT_BINDING".equals(entityType)) {
            Company company = companyRepository.findById(concreteItem.getEntityId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания карточки контроля не найдена"));
            return repairCompanyChatBindingConcreteItem(concreteItem, control, company, principal);
        }
        if (ENTITY_ORDER_PAYMENT_INTEGRITY.equals(entityType)) {
            OrderPaymentIntegrityService.RepairResult result = orderPaymentIntegrityService.repair(concreteItem.getEntityId());
            return resolveRepairedConcreteItem(concreteItem, control, "Заказ возвращен в «Оплачено», лишних ссылок закрыто: " + result.expiredLinks() + ", платежных очередей закрыто: " + result.closedMessageStates() + ". Следующий заказ не изменялся.", principal, "Устранен повторный платежный цикл");
        }
        if (!ENTITY_WORKER_ORDER_NEW.equals(entityType) && !ENTITY_WORKER_ORDER_CORRECT.equals(entityType) && !"ORDER".equals(entityType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Автопочинка доступна только для заказов с клиентской автоматизацией");
        }
        Order order = orderRepository.findById(concreteItem.getEntityId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ карточки контроля не найден"));
        if (concretePresenter.isChatBindingIssueConcrete(concreteItem)) {
            return repairChatBindingIssueConcreteItem(concreteItem, control, order, principal);
        }
        boolean clientTextReminderRepair = order.isWaitingForClient() && "Новый".equals(clientMessageText.orderStatusTitle(order)) && (ENTITY_WORKER_ORDER_NEW.equals(entityType) || "ORDER".equals(entityType));
        if (!clientTextReminderRepair && !order.isWaitingForClient() && ENTITY_WORKER_ORDER_NEW.equals(entityType)) {
            return resolveRepairedConcreteItem(concreteItem, control, "Заказ уже не отмечен как «ждет клиента»", principal, "Статус ожидания клиента уже снят");
        }
        if (!clientTextReminderRepair) {
            return repairOrderAutomationConcreteItem(concreteItem, control, order, principal);
        }
        LocalDate today = LocalDate.now();
        long waitingDays = daysSince(clientTextWaitingControlDate(order), today);
        if (waitingDays > ScheduledClientMessageService.DEFAULT_CLIENT_TEXT_WAITING_AUTO_CLEAR_DAYS) {
            order.setWaitingForClient(false);
            order.setWaitingForClientChangedAt(null);
            orderRepository.save(order);
            scheduledClientMessageService.synchronizeClientTextReminderForOrder(order);
            return resolveRepairedConcreteItem(concreteItem, control, "Снят зависший статус «ждет клиента» после " + waitingDays + " дн.", principal, "Снят зависший статус ожидания клиента");
        }
        scheduledClientMessageService.ensureClientTextReminderForOrder(order);
        WorkerClientTextDecision decision = workerOrderClientTextDecision(order, "Новый", today, scheduledStatesByOrderId(List.of(order)));
        if (decision.include()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Автоответчик не удалось починить: " + safe(decision.reason()));
        }
        return resolveRepairedConcreteItem(concreteItem, control, "Очередь CLIENT_TEXT_REMINDER восстановлена, автоответчик продолжит напоминания", principal, "Восстановлена очередь CLIENT_TEXT_REMINDER");
    }

    ManagerControlConcreteItemResponse repairAutomationFailureConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        return automationRepairWorkflow.repairAutomationFailureConcreteItem(concreteItem, control, principal);
    }

    ManagerControlConcreteItemResponse repairCommonInvoiceConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        Long invoiceId = concreteItem.getEntityId();
        if (invoiceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки общего счета нет ID счета");
        }
        ManagerControlInvoiceRepairWorkflow.Outcome outcome = invoiceRepairWorkflow.repair(invoiceId);
        return resolveRepairedConcreteItem(concreteItem, control, outcome.comment(), principal, outcome.eventDescription());
    }

    ManagerControlConcreteItemResponse repairTelegramChatConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        return chatRepairWorkflow.repairTelegramChatConcreteItem(concreteItem, control, principal);
    }

    ManagerControlConcreteItemResponse repairChatBindingIssueConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Order order, Principal principal) {
        return chatRepairWorkflow.repairChatBindingIssueConcreteItem(concreteItem, control, order, principal);
    }

    ManagerControlConcreteItemResponse repairCompanyChatBindingConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Company company, Principal principal) {
        return chatRepairWorkflow.repairCompanyChatBindingConcreteItem(concreteItem, control, company, principal);
    }

    ManagerControlConcreteItemResponse repairOrderAutomationConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Order order, Principal principal) {
        return automationRepairWorkflow.repairOrderAutomationConcreteItem(concreteItem, control, order, principal);
    }

    ManagerControlConcreteItemResponse resolveRepairedConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment) {
        return repairOutcome.resolveRepairedConcreteItem(concreteItem, control, comment, principal, eventComment);
    }

    WorkerClientTextDecision workerOrderClientTextDecision(Order order, String status, LocalDate today, Map<Long, List<ScheduledClientMessageState>> statesByOrderId) {
        return orderAutomationDiagnostics.workerOrderClientTextDecision(order, status, today, statesByOrderId);
    }

    LocalDate clientTextWaitingControlDate(Order order) {
        return orderAutomationDiagnostics.clientTextWaitingControlDate(order);
    }

    Map<Long, List<ScheduledClientMessageState>> scheduledStatesByOrderId(List<Order> orders) {
        return orderAutomationDiagnostics.scheduledStatesByOrderId(orders);
    }

    Order reviewOrder(Review review) {
        return problemExamples.reviewOrder(review);
    }

    ManagerControlConcreteItemResponse repairPublicationDateConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        Review review = reviewRepository.findById(concreteItem.getEntityId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв карточки контроля не найден"));
        Order order = reviewOrder(review);
        if (order == null || order.getId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У отзыва не найден заказ");
        }
        publicationApprovalService.repairMissingDates(order.getId(), "source=manager_control;controlItemId=" + concreteItem.getId());
        Review repaired = reviewRepository.findById(review.getId()).orElse(review);
        if (!repaired.isPublish() && repaired.getPublishedDate() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Дата не назначена. Откройте заказ, проверьте тексты и аккаунты отзывов, затем повторите починку.");
        }
        return resolveRepairedConcreteItem(concreteItem, control, "Дата публикации назначена автоматически", principal, "Восстановлена отсутствующая дата публикации отзыва");
    }

    long daysSince(LocalDate date, LocalDate today) {
        return problemExamples.daysSince(date, today);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
