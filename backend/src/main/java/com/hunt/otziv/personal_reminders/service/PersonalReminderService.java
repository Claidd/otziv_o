package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderRequest;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PersonalReminderService {

    private static final int DEFAULT_TIMER_MINUTES = 30;
    private static final int MAX_TIMER_MINUTES = 10_080;
    private static final String RECOVERY_COMPLETED_TITLE_PREFIX = "Восстановление завершено";
    private static final String BAD_REVIEW_TASK_TITLE_PREFIX = "Плохой отзыв выполнен";
    private static final String BAD_REVIEW_ORDER_READY_TITLE_PREFIX = "Плохие отзывы завершены";
    public static final String SOURCE_REVIEW_RECOVERY_BATCH = "REVIEW_RECOVERY_BATCH";
    public static final String SOURCE_BAD_REVIEW_TASK = "BAD_REVIEW_TASK";
    public static final String SOURCE_BAD_REVIEW_ORDER_READY = "BAD_REVIEW_ORDER_READY";
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("#(\\d+)");

    private final PersonalReminderRepository reminderRepository;
    private final OrderRepository orderRepository;
    private final ReviewRecoveryBatchRepository recoveryBatchRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<PersonalReminderResponse> list(Principal principal) {
        User user = currentUser(principal);

        return reminderRepository.findByUserIdAndCompletedAtIsNullOrderByUpdatedAtDesc(user.getId()).stream()
                .filter(this::isVisibleReminder)
                .sorted(Comparator
                        .comparingInt(this::dueRank)
                        .thenComparing(reminder -> reminder.getRemindAt() == null ? Instant.MAX : reminder.getRemindAt())
                        .thenComparing(PersonalReminder::getUpdatedAt, Comparator.reverseOrder()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PersonalReminderResponse create(Principal principal, PersonalReminderRequest request) {
        User user = currentUser(principal);
        PersonalReminder reminder = new PersonalReminder();
        reminder.setUser(user);
        applyRequest(reminder, request, Instant.now());

        return PersonalReminderResponse.from(reminderRepository.save(reminder));
    }

    @Transactional
    public PersonalReminderResponse update(Principal principal, Long reminderId, PersonalReminderRequest request) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        applyRequest(reminder, request, Instant.now());

        return PersonalReminderResponse.from(reminderRepository.save(reminder));
    }

    @Transactional
    public PersonalReminderResponse complete(Principal principal, Long reminderId) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        PersonalReminderResponse response = PersonalReminderResponse.from(reminder);
        reminderRepository.delete(reminder);

        return response;
    }

    @Transactional
    public void delete(Principal principal, Long reminderId) {
        User user = currentUser(principal);
        PersonalReminder reminder = findOwnedReminder(reminderId, user);
        reminderRepository.delete(reminder);
    }

    @Transactional
    public void createSystemReminder(User user, String title, String text) {
        createSystemReminder(user, title, text, "none", null, null);
    }

    @Transactional
    public void createSystemReminderDueNow(User user, String title, String text) {
        createSystemReminder(user, title, text, "datetime", Instant.now(), null);
    }

    @Transactional
    public void createSystemReminderDueNow(
            User user,
            String title,
            String text,
            String sourceType,
            Long sourceId,
            Long sourceOrderId
    ) {
        createSystemReminder(user, title, text, "datetime", Instant.now(), null, sourceType, sourceId, sourceOrderId);
    }

    private void createSystemReminder(
            User user,
            String title,
            String text,
            String reminderMode,
            Instant remindAt,
            Integer timerMinutes
    ) {
        createSystemReminder(user, title, text, reminderMode, remindAt, timerMinutes, null, null, null);
    }

    private void createSystemReminder(
            User user,
            String title,
            String text,
            String reminderMode,
            Instant remindAt,
            Integer timerMinutes,
            String sourceType,
            Long sourceId,
            Long sourceOrderId
    ) {
        if (user == null || user.getId() == null) {
            return;
        }

        PersonalReminder reminder = new PersonalReminder();
        reminder.setUser(user);
        reminder.setTitle(trimOrDefault(title, "Напоминание"));
        reminder.setText(trimOrDefault(text, ""));
        reminder.setReminderMode(reminderMode);
        reminder.setRemindAt(remindAt);
        reminder.setTimerMinutes(timerMinutes);
        reminder.setSourceType(trimToNull(sourceType));
        reminder.setSourceId(sourceId);
        reminder.setSourceOrderId(sourceOrderId);
        reminderRepository.save(reminder);
    }

    @Transactional
    public void deleteSystemReminder(User user, String title, String text) {
        if (user == null || user.getId() == null) {
            return;
        }

        reminderRepository.deleteByUserIdAndTitleAndTextAndCompletedAtIsNull(
                user.getId(),
                trimOrDefault(title, "Напоминание"),
                trimOrDefault(text, "")
        );
    }

    @Transactional
    public void deleteSystemRemindersByTitlePrefixAndTextFragment(User user, String titlePrefix, String textFragment) {
        if (user == null || user.getId() == null) {
            return;
        }

        reminderRepository.deleteByUserIdAndTitleStartingWithAndTextContainingAndCompletedAtIsNull(
                user.getId(),
                trimOrDefault(titlePrefix, "Напоминание"),
                trimOrDefault(textFragment, "")
        );
    }

    @Transactional
    public void deleteSystemReminderBySource(User user, String sourceType, Long sourceId) {
        if (user == null || user.getId() == null || sourceId == null || sourceId <= 0) {
            return;
        }

        reminderRepository.deleteByUserIdAndSourceTypeAndSourceIdAndCompletedAtIsNull(
                user.getId(),
                trimOrDefault(sourceType, ""),
                sourceId
        );
    }

    private void applyRequest(PersonalReminder reminder, PersonalReminderRequest request, Instant now) {
        String mode = normalizeMode(request.reminderMode());
        Instant remindAt = null;
        Integer timerMinutes = null;

        if ("datetime".equals(mode)) {
            remindAt = request.remindAt();
            if (remindAt == null) {
                mode = "none";
            }
        }

        if ("timer".equals(mode)) {
            timerMinutes = normalizeTimerMinutes(request.timerMinutes());
            remindAt = now.plus(timerMinutes, ChronoUnit.MINUTES);
        }

        reminder.setTitle(trimOrDefault(request.title(), "Заметка"));
        reminder.setText(trimOrDefault(request.text(), ""));
        reminder.setReminderMode(mode);
        reminder.setRemindAt(remindAt);
        reminder.setTimerMinutes(timerMinutes);
    }

    private PersonalReminder findOwnedReminder(Long reminderId, User user) {
        return reminderRepository.findByIdAndUserId(reminderId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заметка не найдена"));
    }

    private User currentUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден");
        }

        return userService.findByUserName(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Пользователь не найден"));
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "none" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "datetime", "timer" -> normalized;
            default -> "none";
        };
    }

    private Integer normalizeTimerMinutes(Integer value) {
        if (value == null || value < 1) {
            return DEFAULT_TIMER_MINUTES;
        }

        return Math.min(value, MAX_TIMER_MINUTES);
    }

    private String trimOrDefault(String value, String fallback) {
        if (value == null || value.trim().isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private PersonalReminderResponse toResponse(PersonalReminder reminder) {
        if (!isRecoveryCompletionReminder(reminder)) {
            if (isBadReviewReminder(reminder)) {
                return badReviewReminderResponse(reminder);
            }

            return PersonalReminderResponse.from(reminder);
        }

        Long orderId = reminder.getSourceOrderId() != null ? reminder.getSourceOrderId() : recoveryOrderId(reminder);
        if (orderId == null) {
            return PersonalReminderResponse.from(reminder);
        }

        return orderRepository.findByIdForOrderDto(orderId)
                .map(order -> recoveryReminderResponse(reminder, order, recoveryBatchId(reminder, orderId)))
                .orElseGet(() -> PersonalReminderResponse.from(reminder));
    }

    private PersonalReminderResponse recoveryReminderResponse(PersonalReminder reminder, Order order, Long recoveryBatchId) {
        String companyTitle = recoveryCompanyTitle(order);
        String chatUrl = recoveryChatUrl(order);
        String chatLine = chatUrl.isBlank() ? "Чат: не указан" : "Чат: " + chatUrl;

        return new PersonalReminderResponse(
                reminder.getId(),
                limit(RECOVERY_COMPLETED_TITLE_PREFIX + ": " + companyTitle, 120),
                limit(
                        "Компания: " + companyTitle
                                + "\nЗаказ #" + (order.getId() == null ? "-" : order.getId())
                                + "\n" + chatLine
                                + "\nВсе восстановления завершены, можно написать клиенту.",
                        1000
                ),
                reminder.getReminderMode(),
                reminder.getRemindAt(),
                reminder.getTimerMinutes(),
                reminder.getCompletedAt(),
                SOURCE_REVIEW_RECOVERY_BATCH,
                recoveryBatchId,
                order.getId(),
                null,
                reminder.getCreatedAt(),
                reminder.getUpdatedAt()
        );
    }

    private PersonalReminderResponse badReviewReminderResponse(PersonalReminder reminder) {
        Long orderId = reminder.getSourceOrderId() != null ? reminder.getSourceOrderId() : recoveryOrderId(reminder);
        if (orderId == null) {
            return PersonalReminderResponse.from(reminder);
        }

        return orderRepository.findByIdForOrderDto(orderId)
                .map(order -> badReviewReminderResponse(reminder, order))
                .orElseGet(() -> PersonalReminderResponse.from(reminder));
    }

    private PersonalReminderResponse badReviewReminderResponse(PersonalReminder reminder, Order order) {
        boolean orderReady = isBadReviewOrderReadyReminder(reminder);
        String companyTitle = recoveryCompanyTitle(order);
        String chatUrl = recoveryChatUrl(order);
        String chatLine = chatUrl.isBlank() ? "Чат: не указан" : "Чат: " + chatUrl;
        String sourceType = orderReady ? SOURCE_BAD_REVIEW_ORDER_READY : SOURCE_BAD_REVIEW_TASK;
        Long sourceId = reminder.getSourceId() != null ? reminder.getSourceId() : order.getId();
        String actionLine = orderReady
                ? "Все плохие отзывы выполнены. Если клиент не оплатит, можно перевести заказ в Бан."
                : "Плохой отзыв выполнен, можно отправить клиенту счет.";

        return new PersonalReminderResponse(
                reminder.getId(),
                limit((orderReady ? BAD_REVIEW_ORDER_READY_TITLE_PREFIX : BAD_REVIEW_TASK_TITLE_PREFIX) + ": " + companyTitle, 120),
                limit(
                        "Компания: " + companyTitle
                                + "\nЗаказ #" + (order.getId() == null ? "-" : order.getId())
                                + "\n" + chatLine
                                + "\n" + actionLine
                                + "\nК оплате: " + money(paymentAmount(order)) + " руб.",
                        1000
                ),
                reminder.getReminderMode(),
                reminder.getRemindAt(),
                reminder.getTimerMinutes(),
                reminder.getCompletedAt(),
                sourceType,
                sourceId,
                order.getId(),
                paymentCopyText(order),
                reminder.getCreatedAt(),
                reminder.getUpdatedAt()
        );
    }

    private Long recoveryBatchId(PersonalReminder reminder, Long orderId) {
        if (SOURCE_REVIEW_RECOVERY_BATCH.equals(reminder.getSourceType()) && reminder.getSourceId() != null) {
            return reminder.getSourceId();
        }

        if (orderId == null || orderId <= 0) {
            return null;
        }

        return recoveryBatchRepository.findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
                        orderId,
                        EnumSet.of(ReviewRecoveryBatchStatus.COMPLETED)
                )
                .map(ReviewRecoveryBatch::getId)
                .orElse(null);
    }

    private boolean isRecoveryCompletionReminder(PersonalReminder reminder) {
        return reminder != null
                && (SOURCE_REVIEW_RECOVERY_BATCH.equals(reminder.getSourceType())
                || titleStartsWith(reminder, RECOVERY_COMPLETED_TITLE_PREFIX));
    }

    private boolean isVisibleReminder(PersonalReminder reminder) {
        if (!isRecoveryCompletionReminder(reminder)) {
            return true;
        }

        return activeCompletedRecoveryBatchId(reminder) != null;
    }

    private Long activeCompletedRecoveryBatchId(PersonalReminder reminder) {
        if (reminder == null) {
            return null;
        }

        if (SOURCE_REVIEW_RECOVERY_BATCH.equals(reminder.getSourceType()) && reminder.getSourceId() != null) {
            return recoveryBatchRepository.findById(reminder.getSourceId())
                    .filter(batch -> batch.getStatus() == ReviewRecoveryBatchStatus.COMPLETED)
                    .map(ReviewRecoveryBatch::getId)
                    .orElse(null);
        }

        Long orderId = reminder.getSourceOrderId() != null ? reminder.getSourceOrderId() : recoveryOrderId(reminder);
        return recoveryBatchId(reminder, orderId);
    }

    private boolean isBadReviewReminder(PersonalReminder reminder) {
        return reminder != null
                && (SOURCE_BAD_REVIEW_TASK.equals(reminder.getSourceType())
                || SOURCE_BAD_REVIEW_ORDER_READY.equals(reminder.getSourceType())
                || titleStartsWith(reminder, BAD_REVIEW_TASK_TITLE_PREFIX)
                || titleStartsWith(reminder, BAD_REVIEW_ORDER_READY_TITLE_PREFIX));
    }

    private boolean isBadReviewOrderReadyReminder(PersonalReminder reminder) {
        return reminder != null
                && (SOURCE_BAD_REVIEW_ORDER_READY.equals(reminder.getSourceType())
                || titleStartsWith(reminder, BAD_REVIEW_ORDER_READY_TITLE_PREFIX));
    }

    private boolean titleStartsWith(PersonalReminder reminder, String prefix) {
        return reminder != null
                && reminder.getTitle() != null
                && reminder.getTitle().trim().toLowerCase(Locale.ROOT)
                .startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private Long recoveryOrderId(PersonalReminder reminder) {
        Matcher matcher = ORDER_ID_PATTERN.matcher(reminder.getText() == null ? "" : reminder.getText());
        if (!matcher.find()) {
            return null;
        }

        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String recoveryCompanyTitle(Order order) {
        Company company = order != null ? order.getCompany() : null;
        String title = company != null ? trimToEmpty(company.getTitle()) : "";
        return title.isBlank() ? "компания не указана" : title;
    }

    private String recoveryChatUrl(Order order) {
        Company company = order != null ? order.getCompany() : null;
        return company == null ? "" : trimToEmpty(company.getUrlChat());
    }

    private String paymentCopyText(Order order) {
        return legacyPaymentCopyText(order);
    }

    private String legacyPaymentCopyText(Order order) {
        String heading = orderHeading(order);
        String payText = order != null && order.getManager() != null
                ? trimToEmpty(order.getManager().getPayText())
                : "";
        String paymentText = (payText + " К оплате: " + money(paymentAmount(order)) + " руб.").trim();
        return heading.isBlank() ? paymentText : heading + "\n\n" + paymentText;
    }

    private String orderHeading(Order order) {
        if (order == null) {
            return "";
        }

        String companyTitle = recoveryCompanyTitle(order);
        Filial filial = order.getFilial();
        String filialTitle = filial == null ? "" : trimToEmpty(filial.getTitle());
        return filialTitle.isBlank() ? companyTitle : companyTitle + " - " + filialTitle;
    }

    private BigDecimal paymentAmount(Order order) {
        BigDecimal baseSum = order != null && order.getSum() != null ? order.getSum() : BigDecimal.ZERO;
        Long orderId = order != null ? order.getId() : null;
        if (orderId == null || orderId <= 0) {
            return baseSum;
        }

        BigDecimal doneSum = BigDecimal.ZERO;
        for (Object[] row : badReviewTaskRepository.summarizeByOrderId(orderId)) {
            if (row != null && row.length >= 3 && row[0] == BadReviewTaskStatus.DONE) {
                doneSum = doneSum.add(rowMoney(row[2]));
            }
        }
        return baseSum.add(doneSum);
    }

    private BigDecimal rowMoney(Object value) {
        if (value instanceof BigDecimal money) {
            return money;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private String money(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return value.scale() < 0 ? value.setScale(0).toPlainString() : value.toPlainString();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isBlank() ? null : trimmed;
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }

        if (maxLength <= 1) {
            return text.substring(0, Math.max(maxLength, 0));
        }

        return text.substring(0, maxLength - 1).trim() + "…";
    }

    private int dueRank(PersonalReminder reminder) {
        Instant remindAt = reminder.getRemindAt();
        if (remindAt == null) {
            return isRecoveryCompletionReminder(reminder) || isBadReviewReminder(reminder) ? 0 : 2;
        }

        return remindAt.isAfter(Instant.now()) ? 1 : 0;
    }
}
