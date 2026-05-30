package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppAuthAlertService {

    private static final String STATE_DOWN = "DOWN";
    private static final String STATE_UP = "UP";
    private static final int DEFAULT_ALERT_COOLDOWN_HOURS = 12;

    private final TelegramService telegramService;
    private final AppSettingService appSettingService;

    public void notifyAuthIssue(
            String clientId,
            String companyTitle,
            String source,
            String code,
            String readable,
            LocalDateTime now,
            LocalDateTime retryAtIrkutsk,
            Collection<Manager> managers
    ) {
        String safeClientId = displayClientId(clientId);
        LocalDateTime alertNow = normalizeNow(now);
        markState(safeClientId, STATE_DOWN);

        LocalDateTime lastSentAt = parseLocalDateTime(appSettingService.getString(alertKey(safeClientId), null));
        if (lastSentAt != null && lastSentAt.plusHours(alertCooldownHours()).isAfter(alertNow)) {
            log.debug("WhatsApp auth alert skipped by cooldown clientId={}", safeClientId);
            return;
        }

        String text = authIssueText(safeClientId, companyTitle, source, code, readable, retryAtIrkutsk);
        boolean sentToManagers = sendToManagers(managers, text);
        if (!sentToManagers) {
            telegramService.sendAlertToAdmins(markdownSafe(text + "\n\nМенеджерский Telegram не найден или не принял сообщение."));
            log.warn("WhatsApp auth alert sent to admins by fallback clientId={}", safeClientId);
        }

        appSettingService.setString(alertKey(safeClientId), alertNow.toString());
    }

    public void notifyRecovered(
            String clientId,
            String source,
            LocalDateTime now,
            Collection<Manager> managers
    ) {
        String safeClientId = displayClientId(clientId);
        String stateKey = stateKey(safeClientId);
        String currentState = appSettingService.getString(stateKey, STATE_UP);
        if (!STATE_DOWN.equalsIgnoreCase(currentState)) {
            return;
        }

        LocalDateTime alertNow = normalizeNow(now);
        String text = """
                WhatsApp-клиент снова авторизован.

                Клиент: %s
                Источник: %s

                Автоответчик продолжит отправки по обычной очереди.
                """.formatted(safeClientId, hasText(source) ? source : "мониторинг");

        boolean sentToManagers = sendToManagers(managers, text);
        if (!sentToManagers) {
            telegramService.sendAlertToAdmins(markdownSafe(text + "\n\nМенеджерский Telegram не найден или не принял сообщение."));
            log.warn("WhatsApp auth recovery alert sent to admins by fallback clientId={}", safeClientId);
        }

        appSettingService.setString(stateKey, STATE_UP);
        appSettingService.setString(alertKey(safeClientId), "");
        appSettingService.setString(recoveredKey(safeClientId), alertNow.toString());
        log.info("WhatsApp auth recovery marked clientId={} source={}", safeClientId, source);
    }

    private boolean sendToManagers(Collection<Manager> managers, String text) {
        if (managers == null || managers.isEmpty()) {
            return false;
        }

        Set<Long> sentChatIds = new HashSet<>();
        boolean sentAny = false;
        for (Manager manager : managers) {
            Long chatId = manager == null || manager.getUser() == null
                    ? null
                    : manager.getUser().getTelegramChatId();
            if (chatId == null || !sentChatIds.add(chatId)) {
                continue;
            }
            try {
                sentAny = telegramService.sendMessage(chatId, text) || sentAny;
            } catch (Exception e) {
                log.warn("WhatsApp auth alert failed managerId={} chatId={}",
                        manager == null ? null : manager.getId(), chatId, e);
            }
        }
        return sentAny;
    }

    private String authIssueText(
            String clientId,
            String companyTitle,
            String source,
            String code,
            String readable,
            LocalDateTime retryAtIrkutsk
    ) {
        String retryLine = retryAtIrkutsk == null
                ? "Автоответчик сам повторит попытку по ближайшему расписанию."
                : "Автоответчик сам повторит попытку примерно в "
                + retryAtIrkutsk.toString().replace('T', ' ')
                + " по Иркутску.";
        return """
                WhatsApp-клиент не авторизован или ждет QR.

                Клиент: %s
                Компания: %s
                Источник: %s
                Ошибка: %s - %s

                %s
                Проверьте QR/авторизацию WhatsApp, если это рабочий клиент.
                """.formatted(
                clientId,
                hasText(companyTitle) ? companyTitle.trim() : "не определена",
                hasText(source) ? source.trim() : "автоответчик",
                hasText(code) ? code : "whatsapp_not_ready",
                limit(readable, 300),
                retryLine
        );
    }

    private void markState(String clientId, String state) {
        String key = stateKey(clientId);
        if (!state.equalsIgnoreCase(appSettingService.getString(key, ""))) {
            appSettingService.setString(key, state);
        }
    }

    private int alertCooldownHours() {
        int value = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_WHATSAPP_AUTH_ALERT_COOLDOWN_HOURS,
                DEFAULT_ALERT_COOLDOWN_HOURS
        );
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 168);
    }

    private String alertKey(String clientId) {
        return "client.msgs.wa-auth-alert." + safeKeyClientId(clientId);
    }

    private String stateKey(String clientId) {
        return "client.msgs.wa-auth-state." + safeKeyClientId(clientId);
    }

    private String recoveredKey(String clientId) {
        return "client.msgs.wa-auth-recovered." + safeKeyClientId(clientId);
    }

    private String safeKeyClientId(String clientId) {
        String safeClient = displayClientId(clientId).replaceAll("[^A-Za-z0-9_-]", "_");
        if (safeClient.length() > 45) {
            safeClient = safeClient.substring(0, 45);
        }
        return safeClient;
    }

    private String displayClientId(String clientId) {
        return hasText(clientId) ? clientId.trim() : "unknown";
    }

    private LocalDateTime normalizeNow(LocalDateTime now) {
        return (now == null ? LocalDateTime.now() : now).withNano(0);
    }

    private LocalDateTime parseLocalDateTime(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String markdownSafe(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("`", "\\`")
                .replace("[", "\\[");
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
