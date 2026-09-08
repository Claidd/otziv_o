package com.hunt.otziv.manager_control.service;

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
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.c_companies.service.CompanyChatBindingPolicy;
import com.hunt.otziv.c_companies.service.SharedChatLinkSyncService;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControl;
import com.hunt.otziv.manager_control.model.ManagerDailyControlConcreteItem;
import com.hunt.otziv.maxbot.service.MaxGroupLinkService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.dto.TelegramChatMigrationResult;
import com.hunt.otziv.t_telegrambot.service.TelegramGroupLinkService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.WhatsAppGroupLinkSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.util.Locale;
import java.util.Optional;
@Service
@Slf4j
@RequiredArgsConstructor
public class ManagerControlChatRepairWorkflow {

    private final ManagerControlRepairOutcome repairOutcome;

    private final ManagerControlProblemExamples problemExamples;

    private final ManagerControlOrderAutomationDiagnostics orderAutomationDiagnostics;

    private final TelegramService telegramService;

    private final ScheduledClientMessageStateRepository scheduledClientMessageStateRepository;

    private final OrderRepository orderRepository;

    private final CompanyRepository companyRepository;

    private final WhatsAppGroupLinkSyncService whatsAppGroupLinkSyncService;

    private final SharedChatLinkSyncService sharedChatLinkSyncService;

    private final TelegramGroupLinkService telegramGroupLinkService;

    private final MaxGroupLinkService maxGroupLinkService;

    void ensureAutomationChatReady(ManagerAutomationFailureService.AutomationFailureIssue issue) {
        if (issue == null || issue.stateId() == null) {
            return;
        }
        ScheduledClientMessageState state = scheduledClientMessageStateRepository.findById(issue.stateId()).orElse(null);
        Company company = automationFailureCompany(state);
        String errorCode = safe(state == null ? null : state.getLastErrorCode()).trim().toLowerCase(Locale.ROOT);
        String chat = safe(company == null ? null : company.getUrlChat()).trim();
        String normalizedChat = chat.toLowerCase(Locale.ROOT);
        boolean supportedChat = isWhatsAppChat(normalizedChat) || isTelegramChat(normalizedChat) || isMaxChat(normalizedChat);
        if (company != null && "chat_platform_unknown".equals(errorCode) && !supportedChat) {
            String companyName = safe(company.getTitle()).trim();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У компании" + (companyName.isBlank() ? "" : " «" + companyName + "»") + (chat.isBlank() ? " не указана ссылка на клиентский чат. " : " указана неподдерживаемая ссылка на чат: " + chat + ". ") + "Автоматическая отправка работает только с WhatsApp, Telegram и MAX. " + "Замените ссылку на поддерживаемый чат либо обработайте предложение вручную; " + "после изменения ссылки нажмите «Починить» ещё раз.");
        }
        if (company == null || !isTelegramChat(normalizedChat) || company.getTelegramGroupChatId() != null) {
            return;
        }
        company = companyRepository.findById(company.getId()).orElse(company);
        if (company.getTelegramGroupChatId() != null) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, manualChatBindingRepairInstruction(company));
    }

    Company automationFailureCompany(ScheduledClientMessageState state) {
        if (state == null) {
            return null;
        }
        if (state.getCompanyId() != null) {
            Company company = companyRepository.findById(state.getCompanyId()).orElse(null);
            if (company != null) {
                return company;
            }
        }
        if (state.getOrderId() == null) {
            return null;
        }
        return orderRepository.findByIdForOrderDto(state.getOrderId()).map(Order::getCompany).orElse(null);
    }

    ManagerControlConcreteItemResponse repairTelegramChatConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Principal principal) {
        Long companyId = concreteItem.getEntityId();
        if (companyId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки Telegram-группы нет ID компании");
        }
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Компания не найдена"));
        Long oldChatId = company.getTelegramGroupChatId();
        if (oldChatId == null) {
            return resolveRepairedConcreteItem(concreteItem, control, "Telegram-группа уже не привязана к старому chat_id", principal, "Telegram-группа уже отвязана");
        }
        Optional<TelegramChatMigrationResult> result = telegramService.repairMigratedChatId(oldChatId);
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Telegram не вернул новый chat_id. Возможно, группа еще не стала супергруппой или бот потерял доступ.");
        }
        TelegramChatMigrationResult migration = result.get();
        if (!migration.updated()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Telegram вернул новый chat_id, но в БД не нашлось записей со старым id " + oldChatId);
        }
        return resolveRepairedConcreteItem(concreteItem, control, "Telegram chat_id обновлен: " + migration.oldChatId() + " -> " + migration.newChatId(), principal, "Обновлен Telegram chat_id компании");
    }

    ManagerControlConcreteItemResponse repairChatBindingIssueConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Order order, Principal principal) {
        Company company = order == null ? null : order.getCompany();
        if (company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У заказа нет компании для привязки группы");
        }
        return repairCompanyChatBindingConcreteItem(concreteItem, control, company, principal);
    }

    ManagerControlConcreteItemResponse repairCompanyChatBindingConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, Company company, Principal principal) {
        if (company == null || company.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки нет компании для привязки группы");
        }
        if (!CompanyChatBindingPolicy.isRequired(company)) {
            return resolveRepairedConcreteItem(concreteItem, control, "Компания в статусе «Бан»: привязка группы не требуется, карточка скрыта из контроля", principal, "Закрыта лишняя карточка привязки соцсети");
        }
        String before = clientTextChatBindingProblem(company);
        if (before.isBlank()) {
            return resolveRepairedConcreteItem(concreteItem, control, "Группа уже привязана, карточка скрыта из контроля", principal, "Проверена привязка соцсети");
        }
        String chat = safe(company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            WhatsAppGroupLinkSyncService.WhatsAppGroupRepairResult repairResult = whatsAppGroupLinkSyncService.repairCompanyLink(company);
            company = companyRepository.findById(company.getId()).orElse(company);
            String after = clientTextChatBindingProblem(company);
            if (after.isBlank()) {
                return resolveRepairedConcreteItem(concreteItem, control, "WhatsApp-группа найдена и привязана к компании", principal, "Проверена синхронизация WhatsApp-групп");
            }
            String repairMessage = repairResult == null ? "" : safe(repairResult.message());
            throw new ResponseStatusException(HttpStatus.CONFLICT, repairMessage.isBlank() ? manualChatBindingRepairInstruction(company) : repairMessage);
        }
        if (isTelegramChat(chat) || isMaxChat(chat)) {
            sharedChatLinkSyncService.syncSharedChatIds();
            company = companyRepository.findById(company.getId()).orElse(company);
            String after = clientTextChatBindingProblem(company);
            if (after.isBlank()) {
                return resolveRepairedConcreteItem(concreteItem, control, "Группа уже была привязана по такой же ссылке, карточка скрыта из контроля", principal, "Проверена привязка соцсети");
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, manualChatBindingRepairInstruction(company));
    }

    ManagerControlConcreteItemResponse resolveRepairedConcreteItem(ManagerDailyControlConcreteItem concreteItem, ManagerDailyControl control, String comment, Principal principal, String eventComment) {
        return repairOutcome.resolveRepairedConcreteItem(concreteItem, control, comment, principal, eventComment);
    }

    String manualChatBindingRepairInstruction(Company company) {
        String problem = clientTextChatBindingProblem(company);
        String chat = safe(company == null ? null : company.getUrlChat()).toLowerCase(Locale.ROOT);
        if (isWhatsAppChat(chat)) {
            return "WhatsApp-починка не нашла группу автоматически: " + problem + ". Проверьте, что подключенный WhatsApp-аккаунт состоит в группе из ссылки компании, ссылка не устарела, и отправьте любое сообщение в группу. Если ссылка не открывает нужную группу, замените ее в компании вручную.";
        }
        if (isTelegramChat(chat)) {
            String invite = safe(telegramGroupLinkService.buildInviteUrl(company));
            return "Telegram-группа пока не привязана: " + problem + ". Откройте ссылку добавления бота" + (invite.isBlank() ? "" : ": " + invite) + ". В Telegram выберите нужную группу и добавьте бота администратором. Если бот уже добавлен, скопируйте из уведомления команду привязки и отправьте ее в этой группе. Если ссылка компании ведет не в эту группу или не открывается, замените ссылку вручную. После привязки система сама перепроверит и повторит задачу.";
        }
        if (isMaxChat(chat)) {
            String invite = safe(maxGroupLinkService.buildInviteUrl(company));
            return "MAX-группа пока не привязана: " + problem + ". Откройте ссылку привязки" + (invite.isBlank() ? "" : ": " + invite) + ". Запустите бота, затем добавьте его администратором в нужную группу. Если ссылка компании ведет не в эту группу или не открывается, замените ссылку вручную. После успешного добавления бота нажмите «Починить» еще раз.";
        }
        return "Чат компании не удалось привязать автоматически: " + problem + ". Проверьте ссылку на группу в компании и привяжите ее к боту вручную.";
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

    String clientTextChatBindingProblem(Company company) {
        return orderAutomationDiagnostics.clientTextChatBindingProblem(company);
    }

    String safe(String value) {
        return problemExamples.safe(value);
    }
}
