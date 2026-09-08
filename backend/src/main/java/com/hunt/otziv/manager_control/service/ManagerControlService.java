package com.hunt.otziv.manager_control.service;

import static com.hunt.otziv.manager_control.service.ManagerControlRepairWorkflow.*;
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
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.dto.ClientChatReconciliationResult;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplyRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlClientReplySuggestionResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlCloseResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlEventResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlItemActionRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlManagerDetailResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlOverdueStatusResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlSectionResponse;
import com.hunt.otziv.manager_control.dto.ManagerControlStageRequest;
import com.hunt.otziv.manager_control.dto.ManagerControlSummaryResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlEvent;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.dto.OrderDTOList;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.u_users.model.Manager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlService {

    private final ManagerControlRepairWorkflow repairWorkflow;

    private final ManagerControlItemActions itemActions;

    private final ManagerControlReminderWorkflow reminderWorkflow;

    private final ManagerControlDayActions dayActions;

    private final ManagerControlBoardWorkflow boardWorkflow;

    private final ManagerControlDailySnapshotWorkflow dailySnapshot;

    private final ManagerControlDayLifecycle dayLifecycle;

    private final ManagerControlConcreteSnapshotWorkflow concreteSnapshot;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlClientSendWorkflow clientSendWorkflow;

    private final ManagerControlClientReplyWorkflow clientReplyWorkflow;

    private final ManagerControlClientConversationWorkflow clientConversationWorkflow;

    public ManagerControlConcreteItemResponse sendClientMessage(Long concreteItemId, Principal principal, Authentication authentication) {
        return clientSendWorkflow.sendClientMessage(concreteItemId, principal, authentication);
    }

    private final ManagerControlQualityQueries qualityQueries;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    private final TelegramGroupLinkService telegramGroupLinkService;

    private final MaxGroupLinkService maxGroupLinkService;

    //ok
    public ManagerControlSummaryResponse today(Principal principal, Authentication authentication) {
        return boardWorkflow.today(principal, authentication);
    }

    public ManagerControlSummaryResponse syncToday(Principal principal, Authentication authentication) {
        return boardWorkflow.syncToday(principal, authentication);
    }

    public void synchronizeDailySnapshot(LocalDate date) {
        boardWorkflow.synchronizeDailySnapshot(date);
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 120_000L)
    public void runTestModeNotifications() {
        reminderWorkflow.runTestModeNotifications();
    }

    public void actionItem(Long itemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        itemActions.actionItem(itemId, request, principal, authentication);
    }

    public ManagerControlConcreteItemResponse actionConcreteItem(Long concreteItemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        return itemActions.actionConcreteItem(concreteItemId, request, principal, authentication);
    }

    public ManagerControlConcreteItemResponse replyToClientMessage(Long concreteItemId, ManagerControlClientReplyRequest request, Principal principal, Authentication authentication) {
        return clientReplyWorkflow.reply(concreteItemId, request, principal, authentication);
    }

    public ManagerControlClientReplySuggestionResponse suggestClientReply(Long concreteItemId, Principal principal, Authentication authentication) {
        return clientConversationWorkflow.suggestClientReply(concreteItemId, principal, authentication);
    }

    public ManagerControlConcreteItemResponse markClientMessageMisclassified(Long concreteItemId, ManagerControlItemActionRequest request, Principal principal, Authentication authentication) {
        return clientConversationWorkflow.markClientMessageMisclassified(concreteItemId, request, principal, authentication);
    }

    public ManagerControlConcreteItemResponse repairConcreteItem(Long concreteItemId, Principal principal, Authentication authentication) {
        return repairWorkflow.repairConcreteItem(concreteItemId, principal, authentication);
    }

    private String clientMessageError(ClientMessageSendResult result) {
        if (result == null) {
            return "нет ответа от сервиса отправки";
        }
        String message = safe(result.errorMessage());
        if (!message.isBlank()) {
            return message;
        }
        String code = safe(result.errorCode());
        return code.isBlank() ? "сервис отправки не подтвердил доставку" : code;
    }

    private String readableException(Exception e) {
        if (e == null) {
            return "неизвестная ошибка";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public ManagerControlManagerDetailResponse markStage(Long controlId, ManagerControlStageRequest request, Principal principal, Authentication authentication) {
        return dayActions.markStage(controlId, request, principal, authentication);
    }

    public ManagerControlCloseResponse closeDay(Long controlId, ManagerControlCloseRequest request, Principal principal, Authentication authentication) {
        return dayActions.closeDay(controlId, request, principal, authentication);
    }

    public ManagerControlManagerDetailResponse managerDetails(Long managerId, Principal principal, Authentication authentication) {
        return boardWorkflow.managerDetails(managerId, principal, authentication);
    }

    public ClientChatReconciliationResult reconcileClientMessages(Long managerId, Principal principal, Authentication authentication) {
        return clientConversationWorkflow.reconcileClientMessages(managerId, principal, authentication);
    }

    public ManagerControlManagerDetailResponse syncManagerDetails(Long managerId, Principal principal, Authentication authentication) {
        return boardWorkflow.syncManagerDetails(managerId, principal, authentication);
    }

    public ManagerControlManagerDetailResponse acceptControl(Long controlId, Principal principal, Authentication authentication) {
        return dayActions.acceptControl(controlId, principal, authentication);
    }

    private boolean updateQuality(ManagerDailyControl control, List<ManagerDailyControlItem> items) {
        return dayLifecycle.updateQuality(control, items);
    }

    private boolean isManagerClientMessageResolutionEvent(ManagerDailyControl control, ManagerDailyControlEvent event) {
        return qualityQueries.isManagerClientMessageResolutionEvent(control, event);
    }

    private List<ManagerControlEventResponse> events(ManagerDailyControl control) {
        return qualityQueries.events(control);
    }

    private List<ManagerControlConcreteItemResponse> syncConcreteExamples(ManagerDailyControlItem parentItem, List<ManagerControlConcreteItemResponse> examples) {
        return concreteSnapshot.syncConcreteExamples(parentItem, examples);
    }

    private boolean applyConcreteItemSnapshot(ManagerDailyControlConcreteItem item, ManagerControlConcreteItemResponse example) {
        return concreteSnapshot.applyConcreteItemSnapshot(item, example);
    }

    private List<ManagerControlConcreteItemResponse> overdueOrderExamples(Manager manager, String status, LocalDate today, int limit) {
        return problemExamples.overdueOrderExamples(manager, status, today, limit);
    }

    private LocalDateTime orderControlStartedAt(OrderDTOList order) {
        return problemExamples.orderControlStartedAt(order);
    }

    private boolean hasHealthyActiveClientMessageQueue(OrderDTOList order) {
        return problemExamples.hasHealthyActiveClientMessageQueue(order);
    }

    private String chatBindingControlReason(OrderDTOList order) {
        return problemExamples.chatBindingControlReason(order);
    }

    private OrderDTOList orderDtoFromOrder(Order order) {
        Company company = order == null ? null : order.getCompany();
        var filial = order == null ? null : order.getFilial();
        var city = filial == null ? null : filial.getCity();
        var status = order == null ? null : order.getStatus();
        return OrderDTOList.builder().id(order == null ? null : order.getId()).companyId(company == null ? null : company.getId()).companyTitle(company == null ? null : company.getTitle()).companyStatus(company == null || company.getStatus() == null ? null : company.getStatus().getTitle()).filialTitle(filial == null ? null : filial.getTitle()).filialUrl(filial == null ? null : filial.getUrl()).filialCity(city == null ? null : city.getTitle()).status(status == null ? null : status.getTitle()).sum(order == null ? null : order.getSum()).companyUrlChat(company == null ? null : company.getUrlChat()).companyTelephone(company == null ? null : company.getTelephone()).companyComments(company == null ? null : company.getCommentsCompany()).amount(order == null ? null : order.getAmount()).counter(order == null ? null : order.getCounter()).waitingForClient(order != null && order.isWaitingForClient()).created(order == null ? null : order.getCreated()).changed(order == null ? null : order.getChanged()).statusChangedAt(order == null ? null : order.getStatusChangedAt()).payDay(order == null ? null : order.getPayDay()).orderComments(order == null ? null : order.getZametka()).groupId(company == null ? null : company.getGroupId()).telegramGroupChatId(company == null ? null : company.getTelegramGroupChatId()).telegramBotInviteUrl(company == null ? null : telegramGroupLinkService.buildInviteUrl(company)).maxGroupChatId(company == null ? null : company.getMaxGroupChatId()).maxBotInviteUrl(company == null ? null : maxGroupLinkService.buildInviteUrl(company)).build();
    }

    private ManagerControlConcreteItemResponse chatBindingIssueExample(OrderDTOList order, LocalDate today, Manager manager) {
        LocalDate changed = order.getChanged();
        return new ManagerControlConcreteItemResponse(null, "ORDER", order.getId(), safe(order.getCompanyTitle()).isBlank() ? "Заказ #" + order.getId() : order.getCompanyTitle(), orderSubtitle(order), safe(order.getStatus()), changed == null ? null : daysSince(changed, today), chatBindingIssueReason(order), companyBoardUrl(order), order.getOrderDetailsId() == null ? null : order.getOrderDetailsId().toString(), orderChatUrl(order), null, null, ManagerDailyControlItemStatus.OPEN.name(), null, null, null, null, null);
    }

    private String companyBoardUrl(OrderDTOList order) {
        Long companyId = order == null ? null : order.getCompanyId();
        String keyword = safe(order == null ? null : order.getCompanyTitle());
        if (keyword.isBlank() && companyId != null) {
            keyword = String.valueOf(companyId);
        }
        List<String> params = new ArrayList<>();
        params.add("section=companies");
        params.add("status=" + encode("Все"));
        params.add("pageNumber=0");
        params.add("pageSize=10");
        params.add("sortDirection=desc");
        if (!keyword.isBlank()) {
            params.add("keyword=" + encode(keyword));
        }
        return "/companies?" + String.join("&", params);
    }

    private String chatBindingIssueReason(OrderDTOList order) {
        String reason = chatBindingControlReason(order);
        if (reason.isBlank()) {
            reason = "чат компании не готов к отправке сообщений";
        }
        return "Почему в контроле: " + reason + ". Автоответчик не сможет отправить сообщение клиенту. " + chatBindingRepairInstructionLead(order) + " " + chatBindingManualInstruction(order);
    }

    private String chatBindingRepairInstructionLead(OrderDTOList order) {
        String chat = safe(order == null ? null : order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "Нажмите «Починить»: система проверит уже известные WhatsApp-привязки по этой ссылке.";
        }
        if (isTelegramChat(chat)) {
            return "Нажмите «Починить»: система перепроверит, не появилась ли Telegram-привязка, и закроет карточку, если бот уже добавлен.";
        }
        if (isMaxChat(chat)) {
            return "Нажмите «Починить»: система перепроверит, не появилась ли MAX-привязка, и закроет карточку, если бот уже добавлен.";
        }
        return "Нажмите «Починить»: система перепроверит привязку чата.";
    }

    private String chatBindingManualInstruction(OrderDTOList order) {
        String chat = safe(order == null ? null : order.getCompanyUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "Как перепривязать: 1) откройте карточку компании и проверьте, что WhatsApp-ссылка ведет в нужную группу; " + "2) если ссылка устарела, замените ее на актуальную; " + "3) убедитесь, что хотя бы один подключенный WhatsApp-аккаунт состоит в группе; " + "4) отправьте любое сообщение в группу; 5) вернитесь в замечание и нажмите «Починить».";
        }
        if (isTelegramChat(chat)) {
            String invite = safe(order == null ? null : order.getTelegramBotInviteUrl());
            return "Если починка не помогла: откройте ссылку добавления Telegram-бота" + (invite.isBlank() ? "" : " " + invite) + ", выберите нужную группу и добавьте бота администратором. После добавления нажмите «Починить» еще раз.";
        }
        if (isMaxChat(chat)) {
            String invite = safe(order == null ? null : order.getMaxBotInviteUrl());
            return "Если починка не помогла: откройте ссылку привязки MAX" + (invite.isBlank() ? "" : " " + invite) + ", запустите бота, затем добавьте его администратором в нужную группу. После добавления нажмите «Починить» еще раз.";
        }
        return "Если починка не помогла: проверьте ссылку на чат компании, привяжите нужную группу к боту или временно отправьте сообщение клиенту вручную.";
    }

    private boolean isWhatsAppChat(String chat) {
        return orderAutomationDiagnostics.isWhatsAppChat(chat);
    }

    private boolean isTelegramChat(String chat) {
        return orderAutomationDiagnostics.isTelegramChat(chat);
    }

    private boolean isMaxChat(String chat) {
        return orderAutomationDiagnostics.isMaxChat(chat);
    }

    private String orderChatUrl(OrderDTOList order) {
        return problemExamples.orderChatUrl(order);
    }

    private String orderSubtitle(OrderDTOList order) {
        return problemExamples.orderSubtitle(order);
    }

    private String orderChatUrl(Order order) {
        return problemExamples.orderChatUrl(order);
    }

    private boolean hasText(String value) {
        return !safe(value).isBlank();
    }

    private List<ManagerControlOverdueStatusResponse> overdueStatuses(Manager manager, LocalDate today) {
        return problemExamples.overdueStatuses(manager, today);
    }

    private ManagerControlSectionResponse section(String code, String label, long count, String severity, String group, String targetUrl) {
        return dailySnapshot.section(code, label, count, severity, group, targetUrl);
    }

    private long sum(Map<String, Integer> counts, List<String> statuses) {
        return dailySnapshot.sum(counts, statuses);
    }

    private long daysSince(LocalDate date, LocalDate today) {
        return problemExamples.daysSince(date, today);
    }

    private String encode(String value) {
        return problemExamples.encode(value);
    }

    private String managerName(Manager manager) {
        return dailySnapshot.managerName(manager);
    }

    private String safe(String value) {
        return problemExamples.safe(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safe(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Long firstNonNullLong(Long... values) {
        if (values == null) {
            return null;
        }
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
