package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.model.ManagerSummaryDeliveryLog;
import com.hunt.otziv.manager_daily_summary.repository.ManagerSummaryDeliveryLogRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerSummaryNotificationService {

    private final AppSettingService appSettingService;
    private final UserRepository userRepository;
    private final TelegramService telegramService;
    private final ManagerSummaryFormatter formatter;
    private final ManagerSummaryDeliveryLogRepository deliveryRepository;

    public int send(LocalDate date, List<ManagerDailySummaryResponse> managers) {
        if (!appSettingService.getBoolean("manager.summary.enabled", false)) {
            log.info("Manager daily summary is calculated but delivery is disabled");
            return 0;
        }
        ManagerFormattedReport report = formatter.formatBoth(managers, false);
        int sent = 0;
        for (User recipient : recipients()) {
            if (recipient.getTelegramChatId() == null || alreadySent(date, recipient)) continue;
            ManagerSummaryDeliveryLog delivery = deliveryRepository
                    .findBySummaryDateAndRecipient_IdAndChannel(date, recipient.getId(), "TELEGRAM")
                    .orElseGet(ManagerSummaryDeliveryLog::new);
            delivery.setSummaryDate(date);
            delivery.setRecipient(recipient);
            delivery.setRecipientChatId(recipient.getTelegramChatId());
            delivery.setChannel("TELEGRAM");
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            try {
                boolean success = sendReport(recipient.getTelegramChatId(), report.richHtml(), report.html()) > 0;
                delivery.setStatus(success ? "SENT" : "FAILED");
                delivery.setSentAt(success ? LocalDateTime.now() : null);
                delivery.setErrorMessage(success ? null : "Telegram не подтвердил отправку");
                if (success) sent++;
            } catch (RuntimeException exception) {
                delivery.setStatus("FAILED");
                delivery.setErrorMessage(limit(exception.getMessage(), 1000));
                log.warn("Manager summary delivery failed for userId={}", recipient.getId(), exception);
            }
            deliveryRepository.save(delivery);
        }
        return sent;
    }

    /**
     * Sends an explicitly requested test report only to the user who requested it.
     * This path intentionally does not use the scheduled-recipient settings and does
     * not create a delivery log, so it cannot suppress the regular midnight report.
     */
    public int sendTest(User recipient, List<ManagerDailySummaryResponse> managers) {
        if (recipient == null || recipient.getTelegramChatId() == null) {
            throw new IllegalArgumentException("У пользователя не привязан Telegram");
        }
        ManagerFormattedReport report = formatter.formatBoth(managers, true);
        int sentMessages = sendReport(recipient.getTelegramChatId(), report.richHtml(), report.html());
        if (sentMessages == 0) {
            throw new IllegalStateException("Telegram не подтвердил отправку");
        }
        return sentMessages;
    }

    private int sendReport(long chatId, String richMessage, String fallbackMessage) {
        if (telegramService.sendRichMessage(chatId, richMessage)) {
            return 1;
        }
        log.warn("Rich Telegram-аудит недоступен для chatId={}, использован обычный HTML", chatId);
        int sent = 0;
        for (String chunk : chunks(fallbackMessage, 3900)) {
            if (!telegramService.sendMessage(chatId, chunk, "HTML")) {
                return 0;
            }
            sent++;
        }
        return sent;
    }

    private boolean alreadySent(LocalDate date, User user) {
        return deliveryRepository.findBySummaryDateAndRecipient_IdAndChannel(date, user.getId(), "TELEGRAM")
                .map(delivery -> "SENT".equals(delivery.getStatus()))
                .orElse(false);
    }

    private List<User> recipients() {
        Map<Long, User> users = new LinkedHashMap<>();
        String roles = appSettingService.getString("manager.summary.recipients", "ADMIN,OWNER");
        for (String role : roles.split(",")) {
            String normalized = role.trim().toUpperCase();
            if (!normalized.isBlank()) {
                userRepository.findAllOwners("ROLE_" + normalized).forEach(user -> users.put(user.getId(), user));
            }
        }
        Set<Long> whiteList = parseIds(appSettingService.getString("manager.summary.recipient-user-ids", ""));
        return users.values().stream()
                .filter(User::isActive)
                .filter(user -> user.getTelegramChatId() != null)
                .filter(user -> whiteList.isEmpty() || whiteList.contains(user.getId()))
                .toList();
    }

    private Set<Long> parseIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank())
                .flatMap(item -> {
                    try { return java.util.stream.Stream.of(Long.parseLong(item)); }
                    catch (NumberFormatException ignored) { return java.util.stream.Stream.empty(); }
                }).collect(Collectors.toSet());
    }

    private List<String> chunks(String text, int limit) {
        if (text.length() <= limit) return List.of(text);
        List<String> result = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > limit) {
            int split = remaining.lastIndexOf("\n\n", limit);
            if (split < limit / 2) {
                split = remaining.lastIndexOf("\n", limit);
            }
            if (split < limit / 2) split = limit;
            result.add(remaining.substring(0, split));
            remaining = remaining.substring(split).stripLeading();
        }
        if (!remaining.isBlank()) result.add(remaining);
        return result;
    }

    private String limit(String value, int max) {
        if (value == null) return "Неизвестная ошибка";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
