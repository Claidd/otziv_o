package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.dto.ClientChatUnansweredExample;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatMessage;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.model.ClientChatSenderRole;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredItem;
import com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus;
import com.hunt.otziv.client_chat_control.repository.ClientChatMessageRepository;
import com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.manager_control.model.ManagerDailyControlActionType;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientChatMessageTrackerService {

    private static final String ENABLED = "manager-control.unanswered-client-messages.enabled";
    private static final String WARNING_MINUTES = "manager-control.unanswered-client-messages.warning-minutes";
    private static final String EXAMPLE_LIMIT = "manager-control.unanswered-client-messages.example-limit";

    private final ClientChatMessageRepository messageRepository;
    private final ClientChatUnansweredItemRepository unansweredRepository;
    private final ClientChatParticipantClassifier participantClassifier;
    private final ClientChatAutoIgnoreService autoIgnoreService;
    private final CompanyRepository companyRepository;
    private final AppSettingService appSettingService;

    @Transactional
    public void track(ClientChatMessageCommand command) {
        if (!enabled() || command == null || command.platform() == null || !hasText(command.chatId())) {
            return;
        }
        String messageText = safe(command.messageText());
        if (messageText.isBlank()) {
            return;
        }
        if (hasText(command.externalMessageId())
                && messageRepository.findByPlatformAndChatIdAndExternalMessageId(
                        command.platform(),
                        limit(command.chatId(), 160),
                        limit(command.externalMessageId(), 255)
                ).isPresent()) {
            return;
        }

        Company company = companyFor(command.platform(), command.chatId()).orElse(null);
        Manager manager = company == null ? null : company.getManager();
        ClientChatDirection direction = command.direction() == null ? ClientChatDirection.INCOMING : command.direction();
        ClientChatSenderRole senderRole = normalizeSenderRole(command, participantClassifier.classify(
                command.platform(),
                direction,
                command.senderExternalId()
        ));

        ClientChatMessage message = new ClientChatMessage();
        message.setPlatform(command.platform());
        message.setDirection(direction);
        message.setSenderRole(senderRole);
        message.setChatId(limit(command.chatId(), 160));
        message.setChatTitle(limit(command.chatTitle(), 255));
        message.setExternalMessageId(limit(command.externalMessageId(), 255));
        message.setCompany(company);
        message.setManager(manager);
        message.setSenderExternalId(limit(command.senderExternalId(), 160));
        message.setSenderName(limit(command.senderName(), 255));
        message.setMessageText(messageText);
        message.setMessageAt(command.messageAt() == null ? LocalDateTime.now() : command.messageAt());
        ClientChatMessage savedMessage = messageRepository.save(message);

        if (senderRole == ClientChatSenderRole.BOT || direction == ClientChatDirection.OUTGOING) {
            log.debug("Client chat system/outgoing message ignored for unanswered control: platform={}, chatId={}, messageId={}",
                    command.platform(), command.chatId(), savedMessage.getId());
            return;
        }
        if (senderRole == ClientChatSenderRole.STAFF) {
            closeOpenItems(command.platform(), command.chatId(), ClientChatUnansweredStatus.ANSWERED, "Ответ сотрудника");
            return;
        }

        if (manager == null) {
            log.debug("Client chat message tracked without manager: platform={}, chatId={}", command.platform(), command.chatId());
            return;
        }
        if (autoIgnoreService.shouldIgnore(savedMessage.getMessageText())) {
            log.debug("Client chat message auto-ignored: platform={}, chatId={}, messageId={}",
                    command.platform(), command.chatId(), savedMessage.getId());
            return;
        }
        openOrRefresh(savedMessage, company, manager);
    }

    private ClientChatSenderRole normalizeSenderRole(ClientChatMessageCommand command, ClientChatSenderRole senderRole) {
        if (isKnownSystemSender(command)) {
            return ClientChatSenderRole.BOT;
        }
        return senderRole == null ? ClientChatSenderRole.UNKNOWN : senderRole;
    }

    private boolean isKnownSystemSender(ClientChatMessageCommand command) {
        if (command == null) {
            return false;
        }
        String senderExternalId = safe(command.senderExternalId());
        String senderName = safe(command.senderName()).toLowerCase(Locale.ROOT);
        if (command.platform() == ClientChatPlatform.TELEGRAM) {
            return "1087968824".equals(senderExternalId) || senderName.contains("groupanonymousbot");
        }
        return false;
    }

    @Transactional(readOnly = true)
    public long countDue(Manager manager) {
        if (!enabled() || manager == null) {
            return 0;
        }
        return unansweredRepository.countByManagerAndStatusAndLastClientMessageAtLessThanEqual(
                manager,
                ClientChatUnansweredStatus.OPEN,
                dueCutoff()
        );
    }

    @Transactional(readOnly = true)
    public List<ClientChatUnansweredExample> dueExamples(Manager manager, int requestedLimit) {
        if (!enabled() || manager == null) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(requestedLimit, appSettingService.getInt(EXAMPLE_LIMIT, 50)));
        LocalDateTime now = LocalDateTime.now();
        return unansweredRepository.findDueByManager(
                        manager,
                        ClientChatUnansweredStatus.OPEN,
                        dueCutoff(),
                        PageRequest.of(0, limit)
                ).stream()
                .map(item -> example(item, now))
                .toList();
    }

    @Transactional
    public void markFromManagerControl(Long unansweredItemId, ManagerDailyControlActionType actionType, String comment) {
        if (unansweredItemId == null) {
            return;
        }
        unansweredRepository.findById(unansweredItemId).ifPresent(item -> {
            if (item.getStatus() != ClientChatUnansweredStatus.OPEN) {
                return;
            }
            ClientChatUnansweredStatus status = actionType == ManagerDailyControlActionType.ACKNOWLEDGED
                    ? ClientChatUnansweredStatus.NO_RESPONSE_NEEDED
                    : ClientChatUnansweredStatus.ANSWERED;
            close(item, status, hasText(comment) ? comment : "Закрыто из контроля менеджера");
        });
    }

    private void openOrRefresh(ClientChatMessage message, Company company, Manager manager) {
        ClientChatUnansweredItem item = unansweredRepository
                .findFirstByPlatformAndChatIdAndStatusOrderByLastClientMessageAtDesc(
                        message.getPlatform(),
                        message.getChatId(),
                        ClientChatUnansweredStatus.OPEN
                )
                .orElseGet(() -> {
                    ClientChatUnansweredItem created = new ClientChatUnansweredItem();
                    created.setPlatform(message.getPlatform());
                    created.setChatId(message.getChatId());
                    created.setFirstOpenedAt(message.getMessageAt());
                    created.setStatus(ClientChatUnansweredStatus.OPEN);
                    return created;
                });
        item.setChatTitle(message.getChatTitle());
        item.setCompany(company);
        item.setManager(manager);
        item.setLastClientMessage(message);
        item.setSenderExternalId(message.getSenderExternalId());
        item.setSenderName(message.getSenderName());
        item.setLastMessageText(limit(message.getMessageText(), 4000));
        item.setLastClientMessageAt(message.getMessageAt());
        item.setClosedAt(null);
        item.setCloseReason(null);
        unansweredRepository.save(item);
    }

    private void closeOpenItems(ClientChatPlatform platform, String chatId, ClientChatUnansweredStatus status, String reason) {
        unansweredRepository.findByPlatformAndChatIdAndStatus(
                        platform,
                        limit(chatId, 160),
                        ClientChatUnansweredStatus.OPEN
                ).forEach(item -> close(item, status, reason));
    }

    private void close(ClientChatUnansweredItem item, ClientChatUnansweredStatus status, String reason) {
        item.setStatus(status);
        item.setClosedAt(LocalDateTime.now());
        item.setCloseReason(limit(reason, 255));
        unansweredRepository.save(item);
    }

    private ClientChatUnansweredExample example(ClientChatUnansweredItem item, LocalDateTime now) {
        Company company = item.getCompany();
        String title = hasText(item.getChatTitle()) ? item.getChatTitle() : company == null ? item.getChatId() : company.getTitle();
        return new ClientChatUnansweredExample(
                item.getId(),
                item.getPlatform(),
                company == null ? null : company.getId(),
                company == null ? null : company.getTitle(),
                item.getChatId(),
                title,
                item.getSenderName(),
                item.getLastMessageText(),
                item.getLastClientMessageAt(),
                Math.max(0, ChronoUnit.MINUTES.between(item.getLastClientMessageAt(), now)),
                targetUrl(company),
                chatUrl(item),
                specialistName(company)
        );
    }

    private Optional<Company> companyFor(ClientChatPlatform platform, String chatId) {
        return switch (platform) {
            case WHATSAPP -> companyRepository.findAllByGroupId(chatId).stream().findFirst();
            case TELEGRAM -> parseLong(chatId).flatMap(id -> companyRepository.findAllByTelegramGroupChatIdOrderById(id).stream().findFirst());
            case MAX -> parseLong(chatId).flatMap(id -> companyRepository.findAllByMaxGroupChatIdOrderById(id).stream().findFirst());
        };
    }

    private LocalDateTime dueCutoff() {
        return LocalDateTime.now().minusMinutes(Math.max(1, appSettingService.getInt(WARNING_MINUTES, 30)));
    }

    private boolean enabled() {
        return appSettingService.getBoolean(ENABLED, true);
    }

    private String targetUrl(Company company) {
        if (company == null || company.getId() == null) {
            return "/companies";
        }
        return "/companies/" + company.getId();
    }

    private String chatUrl(ClientChatUnansweredItem item) {
        if (item == null || item.getPlatform() == null) {
            return null;
        }
        String companyChatUrl = normalizedChatUrl(item.getCompany() == null ? null : item.getCompany().getUrlChat());
        if (hasText(companyChatUrl)) {
            return companyChatUrl;
        }
        if (item.getPlatform() == ClientChatPlatform.TELEGRAM) {
            return telegramChatUrl(item.getChatId());
        }
        if (item.getPlatform() == ClientChatPlatform.MAX) {
            return "https://max.ru/";
        }
        return null;
    }

    private String specialistName(Company company) {
        if (company == null || company.getWorkers() == null || company.getWorkers().isEmpty()) {
            return "Исполнитель не назначен";
        }
        return company.getWorkers().stream()
                .map(this::workerName)
                .filter(ClientChatMessageTrackerService::hasText)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .findFirst()
                .orElse("Исполнитель не назначен");
    }

    private String workerName(Worker worker) {
        User user = worker == null ? null : worker.getUser();
        String fio = safe(user == null ? null : user.getFio());
        if (hasText(fio)) {
            return fio;
        }
        return safe(user == null ? null : user.getUsername());
    }

    private String normalizedChatUrl(String value) {
        String url = safe(value);
        if (!hasText(url)) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    private String telegramChatUrl(String chatId) {
        String value = safe(chatId);
        if (!hasText(value)) {
            return null;
        }
        if (value.startsWith("-100") && value.length() > 4) {
            return "https://t.me/c/" + encode(value.substring(4));
        }
        return "https://t.me/c/" + encode(value.replaceFirst("^-", ""));
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
