package com.hunt.otziv.p_products.application;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService;
import com.hunt.otziv.security.credentials.CredentialRevealRequest;
import com.hunt.otziv.security.credentials.CredentialRevealResponse;
import com.hunt.otziv.security.credentials.service.CredentialRevealService;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.service.WorkerCredentialPreparationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import java.security.Principal;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for Credential. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerCredentialCommands {

    private static final String SECTION_NAGUL="nagul";
    private static final String SECTION_RECOVERY="recovery";
    private static final String SECTION_PUBLISH="publish";
    private static final String SECTION_BAD="bad";

    private final ReviewService reviewService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final WorkerPublicationGateService workerPublicationGateService;
    private final WorkerActivityService workerActivityService;
    private final WorkerCredentialPreparationService credentialPreparationService;
    private final WorkerCellularAccessService workerCellularAccessService;
    private final WorkerAssignmentMutationGuardService assignmentMutationGuardService;
    private final CredentialRevealService credentialRevealService;
    private final WorkerReviewAccessPolicy reviewAccess;

    public WorkerCredentialPreparationResponse logReviewCredentialCopyClick(Long reviewId, ReviewCopyClickRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        Principal principal = authentication;
        String field = normalizeReviewCopyField(request);
        Review review = reviewService.getReviewById(reviewId);
        if (review == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Отзыв не найден");
        }
        reviewAccess.enforceReviewSourceAccess(review, request.sourceSection(), authentication);
        enforcePublicationSessionIfNeeded(request, principal, authentication);

        Order order = review.getOrderDetails() != null ? review.getOrderDetails().getOrder() : null;
        Company company = order != null ? order.getCompany() : null;
        Bot bot = review.getBot();

        log.info(
                "Специалист {} нажал кнопку \"{}\" для отзыва ID {}, заказа ID {}, компании \"{}\", бота ID {}",
                principalName(principal),
                copyFieldLabel(field),
                review.getId(),
                order != null ? order.getId() : null,
                company != null ? safe(company.getTitle()) : "",
                bot != null ? bot.getId() : null
        );
        workerActivityService.recordSafely(authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "review",
                reviewId,
                order != null ? order.getId() : null,
                reviewId,
                "copy",
                withSource(credentialCopyDetails(field, bot), request)
        );
        boolean preparationRecorded = credentialPreparationService.recordCopy(
                authentication,
                review,
                field,
                request == null ? null : request.sourcePage(),
                request == null ? null : request.sourceEntry(),
                request == null ? null : request.sourceSection()
        );
        if (credentialPreparationRequired(request) && !preparationRecorded) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST,
                    "Сервер не подтвердил подготовку аккаунта. Обновите приложение и повторите копирование."
            );
        }
        recordPublicationActivityIfNeeded(request, principal, authentication);
        return preparationRecorded
                ? activeCredentialPreparation(authentication, request == null ? null : request.sourceSection())
                : null;
    }

    @Transactional
    public CredentialRevealResponse revealReviewCredential(Long reviewId, CredentialRevealRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        Principal principal = authentication;
        ReviewCopyClickRequest source = copyRequest(request);
        String field = normalizeReviewCopyField(source);
        Review review = reviewService.getReviewById(reviewId);
        if (review == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Отзыв не найден");
        }
        reviewAccess.enforceReviewSourceAccess(review, source.sourceSection(), authentication);
        enforcePublicationSessionIfNeeded(source, principal, authentication);

        CredentialRevealResponse response = credentialRevealService.revealReview(review, request, authentication);
        boolean preparationRecorded = credentialPreparationService.recordCopy(
                authentication,
                review,
                field,
                source.sourcePage(),
                source.sourceEntry(),
                source.sourceSection()
        );
        if (credentialPreparationRequired(source) && !preparationRecorded) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST,
                    "Сервер не подтвердил подготовку аккаунта. Обновите приложение и повторите копирование."
            );
        }

        Order order = review.getOrderDetails() == null ? null : review.getOrderDetails().getOrder();
        workerActivityService.recordSafely(
                authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "review",
                reviewId,
                order == null ? null : order.getId(),
                reviewId,
                "credential_reveal",
                withSource(credentialCopyDetails(field, review.getBot()), source)
        );
        recordPublicationActivityIfNeeded(source, principal, authentication);
        WorkerCredentialPreparationResponse preparation = preparationRecorded
                ? activeCredentialPreparation(authentication, source.sourceSection())
                : null;
        return response.withCredentialPreparation(preparation);
    }

    @Transactional
    public CredentialRevealResponse revealRecoveryTaskCredential(Long taskId, CredentialRevealRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        String field = normalizeReviewCopyField(copyRequest(request));
        ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);
        CredentialRevealResponse response = credentialRevealService.revealRecoveryTask(task, request, authentication);
        workerActivityService.recordSafely(
                authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "recovery_task",
                task.getId(),
                orderId(task),
                reviewId(task),
                SECTION_RECOVERY,
                withSource(credentialCopyDetails(field, task.getBot()), copyRequest(request))
        );
        return response;
    }

    @Transactional
    public CredentialRevealResponse revealBadReviewTaskCredential(Long taskId, CredentialRevealRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        String field = normalizeReviewCopyField(copyRequest(request));
        BadReviewTask task = badReviewTaskService.getTask(taskId);
        CredentialRevealResponse response = credentialRevealService.revealBadReviewTask(task, request, authentication);
        workerActivityService.recordSafely(
                authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "bad_review_task",
                task.getId(),
                orderId(task),
                reviewId(task),
                SECTION_BAD,
                withSource(credentialCopyDetails(field, task.getBot()), copyRequest(request))
        );
        return response;
    }

    public void logRecoveryTaskCredentialCopyClick(Long taskId, ReviewCopyClickRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        Principal principal = authentication;
        workerCellularAccessService.enforceProtectedAccess(SECTION_RECOVERY, authentication);
        assignmentMutationGuardService.assertRecoveryTask(taskId, authentication);
        String field = normalizeReviewCopyField(request);
        ReviewRecoveryTask task = reviewRecoveryTaskService.getTask(taskId);

        log.info(
                "Специалист {} нажал кнопку \"{}\" для задачи восстановления ID {}, исходного отзыва ID {}, заказа ID {}, бота ID {}",
                principalName(principal),
                copyFieldLabel(field),
                task.getId(),
                reviewId(task),
                orderId(task),
                botId(task)
        );
        workerActivityService.recordSafely(
                authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "recovery_task",
                task.getId(),
                orderId(task),
                reviewId(task),
                SECTION_RECOVERY,
                withSource(credentialCopyDetails(field, task.getBot()), request)
        );
    }

    public void logBadReviewTaskCredentialCopyClick(Long taskId, ReviewCopyClickRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        Principal principal = authentication;
        workerCellularAccessService.enforceProtectedAccess(SECTION_BAD, authentication);
        assignmentMutationGuardService.assertBadTask(taskId, authentication);
        String field = normalizeReviewCopyField(request);
        BadReviewTask task = badReviewTaskService.getTask(taskId);

        log.info(
                "Специалист {} нажал кнопку \"{}\" для плохой задачи ID {}, исходного отзыва ID {}, заказа ID {}, бота ID {}",
                principalName(principal),
                copyFieldLabel(field),
                task.getId(),
                reviewId(task),
                orderId(task),
                botId(task)
        );
        workerActivityService.recordSafely(
                authentication,
                "login".equals(field) ? WorkerActivityAction.REVIEW_COPY_LOGIN : WorkerActivityAction.REVIEW_COPY_PASSWORD,
                "bad_review_task",
                task.getId(),
                orderId(task),
                reviewId(task),
                SECTION_BAD,
                withSource(credentialCopyDetails(field, task.getBot()), request)
        );
    }

    private String normalizeReviewCopyField(ReviewCopyClickRequest request) {
        String field = request == null ? "" : safe(request.field()).trim().toLowerCase(Locale.ROOT);
        if (!REVIEW_CREDENTIAL_COPY_FIELDS.contains(field)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Кнопка для логирования не поддерживается");
        }
        return field;
    }

    private ReviewCopyClickRequest copyRequest(CredentialRevealRequest request) {
        return request == null
                ? new ReviewCopyClickRequest(null, null, null, null)
                : new ReviewCopyClickRequest(
                        request.field(),
                        request.sourcePage(),
                        request.sourceEntry(),
                        request.sourceSection()
                );
    }

    private String copyFieldLabel(String field) {
        return "password".equals(field) ? "пароль" : "логин";
    }

    private String credentialCopyDetails(String field, Bot bot) {
        return "field=" + valueOrDash(field) + ";botId=" + valueOrDash(bot == null ? null : bot.getId()) + ";";
    }

    private boolean credentialPreparationRequired(ReviewCopyClickRequest source) {
        if (source == null) {
            return false;
        }
        String section = safe(source.sourceSection()).trim().toLowerCase(Locale.ROOT);
        return SECTION_PUBLISH.equals(section) || SECTION_NAGUL.equals(section);
    }

    private void appendDetail(StringBuilder result, String key, String value) {
        String cleanValue = safe(value).trim();
        if (cleanValue.isEmpty()) {
            return;
        }
        result.append(key).append("=").append(cleanValue).append(";");
    }

    private void enforcePublicationSession(Principal principal, Authentication authentication) {
        workerPublicationGateService.blockForPublication(principal, authentication)
                .ifPresent(block -> {
                    throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.CONFLICT, block.message());
                });
    }

    private WorkerCredentialPreparationResponse activeCredentialPreparation(Authentication authentication, String section) {
        if (SECTION_PUBLISH.equals(section)) {
            return credentialPreparationService.active(authentication, WorkerCredentialPreparationScope.PUBLISH);
        }
        if (SECTION_NAGUL.equals(section)) {
            return credentialPreparationService.active(authentication, WorkerCredentialPreparationScope.NAGUL);
        }
        return null;
    }

    private String withSource(String details, ReviewCopyClickRequest source) {
        return withSource(
                details,
                source == null ? null : source.sourcePage(),
                source == null ? null : source.sourceEntry(),
                source == null ? null : source.sourceSection()
        );
    }

    private String withSource(String details, String sourcePage, String sourceEntry, String sourceSection) {
        StringBuilder result = new StringBuilder(details == null ? "" : details);
        appendDetail(result, "sourcePage", sourcePage);
        appendDetail(result, "sourceEntry", sourceEntry);
        appendDetail(result, "sourceSection", sourceSection);
        return result.toString();
    }

    private void enforcePublicationSessionIfNeeded(
            ReviewCopyClickRequest source,
            Principal principal,
            Authentication authentication
    ) {
        if (source != null && SECTION_PUBLISH.equalsIgnoreCase(safe(source.sourceSection()).trim())) {
            enforcePublicationSession(principal, authentication);
        }
    }

    private void recordPublicationActivityIfNeeded(
            ReviewCopyClickRequest source,
            Principal principal,
            Authentication authentication
    ) {
        if (source != null && SECTION_PUBLISH.equalsIgnoreCase(safe(source.sourceSection()).trim())) {
            workerPublicationGateService.recordPublicationActivity(principal, authentication);
        }
    }

    private static final Set<String> REVIEW_CREDENTIAL_COPY_FIELDS=Set.of("login","password");

    public record ReviewCopyClickRequest(String field,String sourcePage,String sourceEntry,String sourceSection) {}
}
