package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewOwnerNotificationService {

    private final AppSettingService appSettingService;
    private final UserRepository userRepository;
    private final TelegramService telegramService;

    public void notifyDispute(ManagerReportReviewSession review) {
        if (review == null || review.getId() == null) return;
        String text = "⚖️ <b>Менеджер оспорил отдельное замечание аудита</b>\n\n"
                + "👤 <b>" + escape(review.getManagerName()) + "</b>\n"
                + "📅 Отчёт за <b>" + review.getSummaryDate() + "</b>\n"
                + "🧾 Разбор №<b>" + review.getId() + "</b>\n\n"
                + "<b>Выбранное замечание и объяснение менеджера:</b>\n"
                + escape(review.getDisputeText())
                + "\n\nРешение применяется только к этому пункту. Остальные замечания "
                + "и история прохождения доступны в контроле менеджеров.";
        for (User recipient : recipients()) {
            telegramService.sendMessageWithInlineKeyboard(
                    recipient.getTelegramChatId(),
                    text,
                    "HTML",
                    ManagerReportReviewTelegramService.ownerDecisionKeyboard(review.getId())
            );
        }
    }

    private java.util.List<User> recipients() {
        Map<Long, User> users = new LinkedHashMap<>();
        String roles = appSettingService.getString("manager.summary.recipients", "ADMIN,OWNER");
        for (String role : roles.split(",")) {
            String normalized = role.trim().toUpperCase();
            if (!normalized.isBlank()) {
                userRepository.findAllOwners("ROLE_" + normalized)
                        .forEach(user -> users.put(user.getId(), user));
            }
        }
        Set<Long> whiteList = parseIds(appSettingService.getString(
                "manager.summary.recipient-user-ids",
                ""
        ));
        return users.values().stream()
                .filter(User::isActive)
                .filter(user -> user.getTelegramChatId() != null)
                .filter(user -> whiteList.isEmpty() || whiteList.contains(user.getId()))
                .toList();
    }

    private Set<Long> parseIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .flatMap(item -> {
                    try {
                        return java.util.stream.Stream.of(Long.parseLong(item));
                    } catch (NumberFormatException ignored) {
                        return java.util.stream.Stream.empty();
                    }
                })
                .collect(Collectors.toSet());
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
