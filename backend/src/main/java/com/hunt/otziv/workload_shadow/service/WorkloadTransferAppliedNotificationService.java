package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferExecutionRepository.AppliedNotificationProjection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadTransferAppliedNotificationService {

    private static final DateTimeFormatter TELEGRAM_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String ROLE_OWNER = "ROLE_OWNER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final WorkloadTransferExecutionRepository repository;
    private final UserService userService;
    private final TelegramService telegramService;

    public void notifyApplied(Long executionId) {
        if (executionId == null || executionId <= 0) {
            return;
        }
        try {
            repository.findAppliedNotification(executionId)
                    .ifPresentOrElse(
                            this::sendAppliedNotification,
                            () -> log.warn(
                                    "Workload applied notification skipped: execution not found or not APPLIED executionId={}",
                                    executionId
                            )
                    );
        } catch (RuntimeException exception) {
            log.warn(
                    "Workload applied notification failed before sending executionId={}: {}",
                    executionId,
                    safeMessage(exception),
                    exception
            );
        }
    }

    private void sendAppliedNotification(AppliedNotificationProjection execution) {
        Set<Long> recipients = recipientChatIds();
        if (recipients.isEmpty()) {
            log.warn(
                    "Workload applied notification skipped: no OWNER/ADMIN personal Telegram recipients executionId={}",
                    execution.getExecutionId()
            );
            return;
        }

        String message = message(execution);
        for (Long chatId : recipients) {
            try {
                boolean sent = telegramService.sendMessage(chatId, message, "HTML");
                if (!sent) {
                    log.warn(
                            "Workload applied notification was not sent chatId={} executionId={}",
                            chatId,
                            execution.getExecutionId()
                    );
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Workload applied notification send failed chatId={} executionId={}: {}",
                        chatId,
                        execution.getExecutionId(),
                        safeMessage(exception),
                        exception
                );
            }
        }
    }

    private Set<Long> recipientChatIds() {
        Set<Long> result = new LinkedHashSet<>();
        addRoleRecipients(result, ROLE_OWNER);
        addRoleRecipients(result, ROLE_ADMIN);
        return result;
    }

    private void addRoleRecipients(Set<Long> result, String roleName) {
        List<User> users;
        try {
            users = userService.getAllOwners(roleName);
        } catch (RuntimeException exception) {
            log.warn(
                    "Workload applied notification recipient lookup failed role={}: {}",
                    roleName,
                    safeMessage(exception),
                    exception
            );
            return;
        }
        if (users == null || users.isEmpty()) {
            return;
        }
        for (User user : users) {
            if (user == null || user.getTelegramChatId() == null) {
                continue;
            }
            long chatId = user.getTelegramChatId();
            if (chatId > 0) {
                result.add(chatId);
            }
        }
    }

    private String message(AppliedNotificationProjection execution) {
        StringBuilder builder = new StringBuilder()
                .append("🟢 <b>LIVE · Смена специалиста по нагрузке</b>\n")
                .append("<b>Компания:</b> «")
                .append(escaped(execution.getCompanyTitle()))
                .append("» (#")
                .append(value(execution.getCompanyId()))
                .append(")\n")
                .append("<b>Менеджер:</b> ")
                .append(escaped(execution.getManagerName()))
                .append("\n")
                .append("<b>Специалист:</b> ")
                .append(escaped(execution.getSourceWorkerName()))
                .append(" → ")
                .append(escaped(execution.getTargetWorkerName()))
                .append("\n")
                .append("<b>Перенесено:</b> ")
                .append(escaped(transferredSummary(execution)))
                .append("\n");
        String orderIds = formattedOrderIds(execution.getOrderIds());
        if (!orderIds.isBlank()) {
            builder.append("<b>Заказы:</b> ")
                    .append(escaped(orderIds))
                    .append("\n");
        }
        builder.append("<b>Применено:</b> ")
                .append(escaped(format(execution.getAppliedAt())))
                .append("\n")
                .append("<b>Откат доступен до:</b> ")
                .append(escaped(format(execution.getRollbackDeadlineAt())))
                .append("\n\n")
                .append("Workflow #")
                .append(value(execution.getWorkflowId()))
                .append(", execution #")
                .append(value(execution.getExecutionId()))
                .append(", режим ")
                .append(escaped(value(execution.getMode())));
        return builder.toString();
    }

    private String transferredSummary(AppliedNotificationProjection execution) {
        return List.of(
                        countLabel(execution.getOrderCount(), "заказ", "заказа", "заказов"),
                        countLabel(
                                execution.getReviewCount(),
                                "карточка отзыва",
                                "карточки отзывов",
                                "карточек отзывов"
                        ),
                        countLabel(
                                execution.getBadTaskCount(),
                                "задача плохого отзыва",
                                "задачи плохих отзывов",
                                "задач плохих отзывов"
                        ),
                        countLabel(
                                execution.getRecoveryTaskCount(),
                                "восстановление",
                                "восстановления",
                                "восстановлений"
                        )
                ).stream()
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse("нет изменённых сущностей");
    }

    private String countLabel(Integer count, String one, String few, String many) {
        int safeCount = count == null ? 0 : count;
        if (safeCount <= 0) {
            return "";
        }
        int mod100 = Math.abs(safeCount) % 100;
        int mod10 = Math.abs(safeCount) % 10;
        String label = mod100 >= 11 && mod100 <= 14
                ? many
                : switch (mod10) {
                    case 1 -> one;
                    case 2, 3, 4 -> few;
                    default -> many;
                };
        return safeCount + " " + label;
    }

    private String formattedOrderIds(String rawOrderIds) {
        if (rawOrderIds == null || rawOrderIds.isBlank()) {
            return "";
        }
        return rawOrderIds.lines()
                .flatMap(line -> List.of(line.split(",")).stream())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> "#" + value)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String format(LocalDateTime value) {
        return value == null ? "не указано" : TELEGRAM_TIME_FORMAT.format(value);
    }

    private String value(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    private String escaped(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }
}