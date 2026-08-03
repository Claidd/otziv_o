package com.hunt.otziv.security.credentials;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CredentialRevealService {

    private static final Set<String> SUPPORTED_FIELDS = Set.of("login", "password");
    private static final int SOURCE_VALUE_LIMIT = 80;

    private final BusinessAuditService businessAuditService;

    public CredentialRevealResponse revealReview(Review review, CredentialRevealRequest request) {
        if (review == null || review.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Отзыв не найден");
        }
        Bot bot = review.getBot();
        return reveal(
                "review",
                review.getId(),
                orderId(review),
                review.getId(),
                bot == null ? null : bot.getId(),
                bot == null ? null : bot.getLogin(),
                bot == null ? null : bot.getPassword(),
                request
        );
    }

    public CredentialRevealResponse revealBadReviewTask(
            BadReviewTask task,
            CredentialRevealRequest request
    ) {
        if (task == null || task.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Плохая задача не найдена");
        }
        Review review = task.getSourceReview();
        Bot taskBot = task.getBot();
        Bot sourceBot = review == null ? null : review.getBot();
        Long botId = taskBot != null ? taskBot.getId() : sourceBot == null ? null : sourceBot.getId();
        return reveal(
                "bad_review_task",
                task.getId(),
                orderId(task),
                review == null ? null : review.getId(),
                botId,
                firstNonBlank(
                        task.getBotLoginSnapshot(),
                        taskBot == null ? null : taskBot.getLogin(),
                        sourceBot == null ? null : sourceBot.getLogin()
                ),
                firstNonBlank(
                        task.getBotPasswordSnapshot(),
                        taskBot == null ? null : taskBot.getPassword(),
                        sourceBot == null ? null : sourceBot.getPassword()
                ),
                request
        );
    }

    public CredentialRevealResponse revealRecoveryTask(
            ReviewRecoveryTask task,
            CredentialRevealRequest request
    ) {
        if (task == null || task.getId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Задача восстановления не найдена");
        }
        Bot bot = task.getBot();
        Review review = task.getSourceReview();
        return reveal(
                "recovery_task",
                task.getId(),
                orderId(task),
                review != null ? review.getId() : task.getArchiveReviewId(),
                bot == null ? null : bot.getId(),
                firstNonBlank(bot == null ? null : bot.getLogin(), task.getBotLoginSnapshot()),
                firstNonBlank(bot == null ? null : bot.getPassword(), task.getBotPasswordSnapshot()),
                request
        );
    }

    private CredentialRevealResponse reveal(
            String entityType,
            Long entityId,
            Long orderId,
            Long reviewId,
            Long botId,
            String login,
            String password,
            CredentialRevealRequest request
    ) {
        String field = normalizeField(request);
        String value = "login".equals(field) ? login : password;
        if (!hasText(value)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Данные аккаунта недоступны");
        }

        // The audit transaction must commit before the decrypted value can be
        // returned to the controller. The credential itself is never audited.
        businessAuditService.recordStrict(
                "CREDENTIAL_REVEAL",
                entityType,
                entityId,
                orderId,
                reviewId,
                null,
                null,
                auditDetails(field, botId, request)
        );
        return new CredentialRevealResponse(value);
    }

    private String normalizeField(CredentialRevealRequest request) {
        String field = request == null || request.field() == null
                ? ""
                : request.field().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_FIELDS.contains(field)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Поле аккаунта не поддерживается");
        }
        return field;
    }

    private String auditDetails(String field, Long botId, CredentialRevealRequest request) {
        StringBuilder details = new StringBuilder("field=")
                .append(field)
                .append(";botId=")
                .append(botId == null ? "-" : botId)
                .append(';');
        append(details, "sourcePage", request == null ? null : request.sourcePage());
        append(details, "sourceEntry", request == null ? null : request.sourceEntry());
        append(details, "sourceSection", request == null ? null : request.sourceSection());
        return details.toString();
    }

    private void append(StringBuilder target, String key, String value) {
        String clean = cleanSourceValue(value);
        if (!clean.isEmpty()) {
            target.append(key).append('=').append(clean).append(';');
        }
    }

    private String cleanSourceValue(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(SOURCE_VALUE_LIMIT)
                .forEach(clean::appendCodePoint);
        return clean.toString().trim();
    }

    private Long orderId(Review review) {
        Order order = review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
        return order == null ? null : order.getId();
    }

    private Long orderId(BadReviewTask task) {
        if (task.getOrder() != null) {
            return task.getOrder().getId();
        }
        return task.getSourceReview() == null ? null : orderId(task.getSourceReview());
    }

    private Long orderId(ReviewRecoveryTask task) {
        if (task.getOrder() != null) {
            return task.getOrder().getId();
        }
        if (task.getSourceReview() != null) {
            return orderId(task.getSourceReview());
        }
        return task.getArchiveOrderId();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
