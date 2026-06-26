package com.hunt.otziv.worker_activity;

import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentLevel;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerRiskEvaluationService {

    public static final String SOURCE_WORKER_RISK_INCIDENT = "WORKER_RISK_INCIDENT";
    private static final int DETAILS_LIMIT = 1_500;
    private static final List<WorkerActivityAction> TASK_COMPLETIONS = List.of(
            WorkerActivityAction.BAD_TASK_COMPLETE,
            WorkerActivityAction.RECOVERY_TASK_COMPLETE
    );
    private static final List<WorkerActivityAction> REVIEW_CREDENTIAL_COPIES = List.of(
            WorkerActivityAction.REVIEW_COPY_LOGIN,
            WorkerActivityAction.REVIEW_COPY_PASSWORD
    );
    private static final List<WorkerActivityAction> REVIEW_PUBLISHES = List.of(WorkerActivityAction.REVIEW_PUBLISH);
    private static final List<WorkerActivityAction> ACCOUNT_ACTIONS = List.of(
            WorkerActivityAction.REVIEW_BOT_CHANGE,
            WorkerActivityAction.REVIEW_BOT_DEACTIVATE,
            WorkerActivityAction.BAD_TASK_BOT_CHANGE,
            WorkerActivityAction.BAD_TASK_BOT_DEACTIVATE,
            WorkerActivityAction.RECOVERY_TASK_BOT_CHANGE,
            WorkerActivityAction.RECOVERY_TASK_BOT_DEACTIVATE
    );
    private static final List<WorkerActivityAction> ACCOUNT_DEACTIVATIONS = List.of(
            WorkerActivityAction.REVIEW_BOT_DEACTIVATE,
            WorkerActivityAction.BAD_TASK_BOT_DEACTIVATE,
            WorkerActivityAction.RECOVERY_TASK_BOT_DEACTIVATE
    );
    private static final List<WorkerActivityAction> NAGUL_ACTIONS = List.of(WorkerActivityAction.REVIEW_NAGUL);
    private static final List<WorkerActivityAction> TEXT_FREQUENCY_ACTIONS = List.of(
            WorkerActivityAction.REVIEW_ANSWER_UPDATE,
            WorkerActivityAction.REVIEW_NOTE_UPDATE,
            WorkerActivityAction.ORDER_NOTE_UPDATE,
            WorkerActivityAction.COMPANY_NOTE_UPDATE,
            WorkerActivityAction.BAD_TASK_UPDATE,
            WorkerActivityAction.RECOVERY_TASK_UPDATE
    );

    private final WorkerActivityEventRepository eventRepository;
    private final WorkerRiskIncidentRepository incidentRepository;
    private final PersonalReminderService personalReminderService;
    private final UserService userService;
    private final TelegramService telegramService;
    private final OrderRepository orderRepository;
    private final PlatformTransactionManager transactionManager;

    @Value("${worker.risk.duplicate-window-minutes:30}")
    private int duplicateWindowMinutes = 30;

    @Value("${worker.risk.publish-prep-window-minutes:60}")
    private int publishPrepWindowMinutes = 60;

    @Value("${worker.risk.publish-too-fast-seconds:15}")
    private int publishTooFastSeconds = 15;

    @Value("${worker.risk.close-after-account-copy-too-fast-seconds:10}")
    private int closeAfterAccountCopyTooFastSeconds = 10;

    @Value("${worker.risk.close-after-account-copy-too-fast.window-minutes:30}")
    private int closeAfterAccountCopyTooFastWindowMinutes = 30;

    @Value("${worker.risk.close-after-account-copy-too-fast.limit:3}")
    private int closeAfterAccountCopyTooFastLimit = 3;

    @Value("${worker.risk.task-completion.10m-limit:10}")
    private int taskCompletionTenMinuteLimit = 10;

    @Value("${worker.risk.task-completion.hour-limit:30}")
    private int taskCompletionHourLimit = 30;

    @Value("${worker.risk.task-completion.day-limit:70}")
    private int taskCompletionDayLimit = 70;

    @Value("${worker.risk.publish.10m-limit:8}")
    private int publishTenMinuteLimit = 8;

    @Value("${worker.risk.publish.hour-limit:20}")
    private int publishHourLimit = 20;

    @Value("${worker.risk.publish.day-limit:50}")
    private int publishDayLimit = 50;

    @Value("${worker.risk.account-actions.without-use-window-minutes:15}")
    private int accountActionWithoutUseWindowMinutes = 15;

    @Value("${worker.risk.nagul.hour-limit:15}")
    private int nagulHourLimit = 15;

    @Value("${worker.risk.nagul.day-limit:50}")
    private int nagulDayLimit = 50;

    @Value("${worker.risk.text-actions.hour-limit:30}")
    private int textActionHourLimit = 30;

    @Value("${worker.risk.text-actions.day-limit:90}")
    private int textActionDayLimit = 90;

    @Value("${worker.risk.review-text.same-card-hour-limit:5}")
    private int reviewTextSameCardHourLimit = 5;

    @Value("${otziv.app-base-url:https://o-ogo.ru}")
    private String appBaseUrl = "https://o-ogo.ru";

    public void evaluateSafely(WorkerActivityEvent event, User workerUser) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> evaluate(event, workerUser));
        } catch (RuntimeException e) {
            log.warn("Risk-score специалиста не рассчитан eventId={}, workerUserId={}: {}",
                    event == null ? null : event.getId(),
                    workerUser == null ? null : workerUser.getId(),
                    e.getMessage());
            log.debug("Worker risk evaluation failed", e);
        }
    }

    private void evaluate(WorkerActivityEvent event, User workerUser) {
        if (event == null || workerUser == null || workerUser.getId() == null) {
            return;
        }

        List<RiskFinding> findings = findings(event);
        int maxScore = findings.stream().mapToInt(RiskFinding::score).max().orElse(0);
        if (maxScore > event.getRiskScore()) {
            event.setRiskScore(maxScore);
            eventRepository.save(event);
        }

        for (RiskFinding finding : findings) {
            createIncidentIfNeeded(event, workerUser, finding);
        }
    }

    private List<RiskFinding> findings(WorkerActivityEvent event) {
        List<RiskFinding> result = new ArrayList<>();
        LocalDateTime now = event.getCreatedAt() == null ? LocalDateTime.now() : event.getCreatedAt();

        if (TASK_COMPLETIONS.contains(event.getAction())) {
            addFrequencyFinding(result, event, "TASK_COMPLETION_10M", TASK_COMPLETIONS, now.minusMinutes(10), taskCompletionTenMinuteLimit, 30,
                    "Много закрытых задач за 10 минут");
            addFrequencyFinding(result, event, "TASK_COMPLETION_HOUR", TASK_COMPLETIONS, now.minusHours(1), taskCompletionHourLimit, 50,
                    "Много закрытых задач за час");
            addFrequencyFinding(result, event, "TASK_COMPLETION_DAY", TASK_COMPLETIONS, now.toLocalDate().atStartOfDay(), taskCompletionDayLimit, 80,
                    "Много закрытых задач за день");
        }

        if (event.getAction() == WorkerActivityAction.REVIEW_PUBLISH) {
            addFrequencyFinding(result, event, "PUBLISH_10M", REVIEW_PUBLISHES, now.minusMinutes(10), publishTenMinuteLimit, 30,
                    "Много публикаций за 10 минут");
            addFrequencyFinding(result, event, "PUBLISH_HOUR", REVIEW_PUBLISHES, now.minusHours(1), publishHourLimit, 50,
                    "Много публикаций за час");
            addFrequencyFinding(result, event, "PUBLISH_DAY", REVIEW_PUBLISHES, now.toLocalDate().atStartOfDay(), publishDayLimit, 80,
                    "Много публикаций за день");
            addPublishPreparationFindings(result, event, now);
        }

        if (ACCOUNT_ACTIONS.contains(event.getAction())) {
            if (ACCOUNT_DEACTIVATIONS.contains(event.getAction())) {
                addAccountDeactivationWithoutCredentialCopyFinding(result, event);
            }
        }

        if (event.getAction() == WorkerActivityAction.REVIEW_NAGUL) {
            addFrequencyFinding(result, event, "NAGUL_HOUR", NAGUL_ACTIONS, now.minusHours(1), nagulHourLimit, 30,
                    "Много действий выгула за час");
            addFrequencyFinding(result, event, "NAGUL_DAY", NAGUL_ACTIONS, now.toLocalDate().atStartOfDay(), nagulDayLimit, 50,
                    "Много действий выгула за день");
        }

        if (TEXT_FREQUENCY_ACTIONS.contains(event.getAction())) {
            addFrequencyFinding(result, event, "TEXT_ACTION_HOUR", TEXT_FREQUENCY_ACTIONS, now.minusHours(1), textActionHourLimit, 30,
                    "Много правок текстов и заметок за час");
            addFrequencyFinding(result, event, "TEXT_ACTION_DAY", TEXT_FREQUENCY_ACTIONS, now.toLocalDate().atStartOfDay(), textActionDayLimit, 50,
                    "Много правок текстов и заметок за день");
        }

        if (event.getAction() == WorkerActivityAction.REVIEW_TEXT_UPDATE) {
            addReviewTextSameCardFinding(result, event, now);
        }

        if (closesWorkAfterAccountSelection(event)) {
            addAccountChangedWithoutUseFinding(result, event, now);
            addFastCloseAfterAccountCopySeriesFinding(result, event, now);
        }

        return result;
    }

    private void addReviewTextSameCardFinding(List<RiskFinding> result, WorkerActivityEvent event, LocalDateTime now) {
        if (event.getEntityType() == null || event.getEntityId() == null) {
            return;
        }

        long count = eventRepository.countByWorkerUserIdAndActionInAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                event.getWorkerUserId(),
                List.of(WorkerActivityAction.REVIEW_TEXT_UPDATE),
                event.getEntityType(),
                event.getEntityId(),
                now.minusHours(1)
        );
        if (count >= reviewTextSameCardHourLimit) {
            result.add(new RiskFinding(
                    "REVIEW_TEXT_SAME_CARD_HOUR",
                    30,
                    "Много правок текста одного отзыва за час",
                    "Текст одного отзыва изменяли " + count + " раз за час, порог: " + reviewTextSameCardHourLimit + "."
            ));
        }
    }

    private void addAccountDeactivationWithoutCredentialCopyFinding(List<RiskFinding> result, WorkerActivityEvent event) {
        if (event.getReviewId() == null || event.getCreatedAt() == null) {
            return;
        }

        LocalDateTime since = event.getCreatedAt().minusMinutes(Math.max(1, accountActionWithoutUseWindowMinutes));
        boolean copiedLogin = copiedCredentialBetween(event, WorkerActivityAction.REVIEW_COPY_LOGIN, since, event.getCreatedAt());
        boolean copiedPassword = copiedCredentialBetween(event, WorkerActivityAction.REVIEW_COPY_PASSWORD, since, event.getCreatedAt());
        if (!copiedLogin || !copiedPassword) {
            result.add(new RiskFinding(
                    "ACCOUNT_DEACTIVATION_WITHOUT_CREDENTIAL_COPY",
                    35,
                    "Деактивация аккаунта без копирования данных",
                    "Перед деактивацией аккаунта по этой карточке не видно копирования логина и пароля"
                            + (botToken(event).isBlank() ? "." : " этого аккаунта.")
            ));
        }
    }

    private boolean copiedCredentialBetween(
            WorkerActivityEvent event,
            WorkerActivityAction action,
            LocalDateTime since,
            LocalDateTime until
    ) {
        String botToken = botToken(event);
        if (!botToken.isBlank()) {
            return eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                    event.getWorkerUserId(),
                    action,
                    event.getReviewId(),
                    since,
                    until,
                    botToken
            );
        }
        return eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                event.getWorkerUserId(),
                List.of(action),
                event.getReviewId(),
                since,
                until
        );
    }

    private void addAccountChangedWithoutUseFinding(List<RiskFinding> result, WorkerActivityEvent event, LocalDateTime now) {
        if (event.getReviewId() == null) {
            return;
        }

        findLastAccountSelectionBeforeClose(event)
                .ifPresent(accountEvent -> {
                    boolean copiedLogin = copiedCredentialSince(event, WorkerActivityAction.REVIEW_COPY_LOGIN, accountEvent.getCreatedAt());
                    boolean copiedPassword = copiedCredentialSince(event, WorkerActivityAction.REVIEW_COPY_PASSWORD, accountEvent.getCreatedAt());
                    if (!copiedLogin || !copiedPassword) {
                        result.add(new RiskFinding(
                                "ACCOUNT_CHANGED_WITHOUT_CREDENTIAL_COPY",
                                35,
                                "Закрытие после смены аккаунта без копирования данных",
                                "Перед закрытием карточки была смена/деактивация аккаунта, но после нее не видно копирования логина и пароля."
                        ));
                    }
                });
    }

    private void addFastCloseAfterAccountCopySeriesFinding(
            List<RiskFinding> result,
            WorkerActivityEvent event,
            LocalDateTime now
    ) {
        if (!isFastCloseAfterAccountCopy(event)) {
            return;
        }

        LocalDateTime since = now.minusMinutes(Math.max(1, closeAfterAccountCopyTooFastWindowMinutes));
        long count = eventRepository.findTop50ByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        event.getWorkerUserId(),
                        workClosingActions(),
                        since
                )
                .stream()
                .filter(this::isFastCloseAfterAccountCopy)
                .count();

        if (count >= Math.max(1, closeAfterAccountCopyTooFastLimit)) {
            result.add(new RiskFinding(
                    "ACCOUNT_CLOSE_TOO_FAST_AFTER_CREDENTIAL_COPY_SERIES",
                    35,
                    "Быстрые закрытия после копирования данных",
                    "За " + Math.max(1, closeAfterAccountCopyTooFastWindowMinutes)
                            + " мин. найдено " + count
                            + " закрытий после смены аккаунта, где после последнего копирования логина/пароля прошло не больше "
                            + Math.max(1, closeAfterAccountCopyTooFastSeconds)
                            + " сек. Порог: " + Math.max(1, closeAfterAccountCopyTooFastLimit) + "."
            ));
        }
    }

    private boolean isFastCloseAfterAccountCopy(WorkerActivityEvent closeEvent) {
        if (closeEvent == null || !closesWorkAfterAccountSelection(closeEvent)
                || closeEvent.getReviewId() == null || closeEvent.getCreatedAt() == null) {
            return false;
        }

        return findLastAccountSelectionBeforeClose(closeEvent)
                .flatMap(accountEvent -> {
                    LocalDateTime since = accountEvent.getCreatedAt();
                    LocalDateTime until = closeEvent.getCreatedAt();
                    String botToken = botToken(closeEvent);
                    boolean copiedLogin = copiedCredentialBetween(closeEvent, WorkerActivityAction.REVIEW_COPY_LOGIN, since, until, botToken);
                    boolean copiedPassword = copiedCredentialBetween(closeEvent, WorkerActivityAction.REVIEW_COPY_PASSWORD, since, until, botToken);
                    if (!copiedLogin || !copiedPassword) {
                        return Optional.<WorkerActivityEvent>empty();
                    }
                    return findLastCredentialCopyBetween(closeEvent, botToken, since, until);
                })
                .map(copyEvent -> secondsBetween(copyEvent, closeEvent) <= Math.max(1, closeAfterAccountCopyTooFastSeconds))
                .orElse(false);
    }

    private Optional<WorkerActivityEvent> findLastAccountSelectionBeforeClose(WorkerActivityEvent event) {
        if (event == null || event.getWorkerUserId() == null || event.getReviewId() == null || event.getCreatedAt() == null) {
            return Optional.empty();
        }

        LocalDateTime since = event.getCreatedAt().minusMinutes(Math.max(1, accountActionWithoutUseWindowMinutes));
        return eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                event.getWorkerUserId(),
                ACCOUNT_ACTIONS,
                event.getReviewId(),
                since,
                event.getCreatedAt()
        );
    }

    private boolean copiedCredentialSince(WorkerActivityEvent event, WorkerActivityAction action, LocalDateTime since) {
        if (event == null || event.getCreatedAt() == null) {
            return false;
        }
        String botToken = botToken(event);
        LocalDateTime from = since == null
                ? event.getCreatedAt().minusMinutes(Math.max(1, accountActionWithoutUseWindowMinutes))
                : since;
        return copiedCredentialBetween(event, action, from, event.getCreatedAt(), botToken);
    }

    private boolean closesWorkAfterAccountSelection(WorkerActivityEvent event) {
        return event.getAction() == WorkerActivityAction.REVIEW_NAGUL
                || event.getAction() == WorkerActivityAction.BAD_TASK_COMPLETE
                || event.getAction() == WorkerActivityAction.RECOVERY_TASK_COMPLETE;
    }

    private void addPublishPreparationFindings(List<RiskFinding> result, WorkerActivityEvent event, LocalDateTime now) {
        if (event.getReviewId() == null) {
            return;
        }

        LocalDateTime since = now.minusMinutes(Math.max(1, publishPrepWindowMinutes));
        String botToken = botToken(event);
        boolean copiedLogin = copiedCredentialForPublishedBot(event, WorkerActivityAction.REVIEW_COPY_LOGIN, since, botToken);
        boolean copiedPassword = copiedCredentialForPublishedBot(event, WorkerActivityAction.REVIEW_COPY_PASSWORD, since, botToken);

        if (!copiedLogin || !copiedPassword) {
            result.add(new RiskFinding(
                    "PUBLISH_WITHOUT_CREDENTIAL_COPY",
                    30,
                    "Публикация без копирования данных аккаунта",
                    botToken.isBlank()
                            ? "Перед публикацией по этому отзыву не видно копирования логина и пароля за последний час."
                            : "Перед публикацией по этому отзыву не видно копирования логина и пароля закрепленного аккаунта за последний час."
            ));
            return;
        }

        findLastCredentialCopyForPublishedBot(event, botToken)
                .filter(copyEvent -> copyEvent.getCreatedAt() != null && event.getCreatedAt() != null)
                .filter(copyEvent -> secondsBetween(copyEvent, event) <= Math.max(1, publishTooFastSeconds))
                .ifPresent(copyEvent -> result.add(new RiskFinding(
                        "PUBLISH_TOO_FAST_AFTER_CREDENTIAL_COPY",
                        30,
                        "Публикация слишком быстро после копирования данных",
                        "После последнего копирования логина/пароля до публикации прошло "
                                + secondsBetween(copyEvent, event)
                                + " сек. Минимум: " + Math.max(1, publishTooFastSeconds) + " сек."
                )));
    }

    private boolean copiedCredentialForPublishedBot(
            WorkerActivityEvent event,
            WorkerActivityAction action,
            LocalDateTime since,
            String botToken
    ) {
        if (!botToken.isBlank()) {
            return eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                    event.getWorkerUserId(),
                    action,
                    event.getReviewId(),
                    since,
                    event.getCreatedAt(),
                    botToken
            );
        }

        return eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                event.getWorkerUserId(),
                List.of(action),
                event.getReviewId(),
                since,
                event.getCreatedAt()
        );
    }

    private Optional<WorkerActivityEvent> findLastCredentialCopyForPublishedBot(WorkerActivityEvent event, String botToken) {
        if (event == null || event.getCreatedAt() == null) {
            return Optional.empty();
        }
        LocalDateTime since = event.getCreatedAt().minusMinutes(Math.max(1, publishPrepWindowMinutes));
        return findLastCredentialCopyBetween(event, botToken, since, event.getCreatedAt());
    }

    private Optional<WorkerActivityEvent> findLastCredentialCopyBetween(
            WorkerActivityEvent event,
            String botToken,
            LocalDateTime since,
            LocalDateTime until
    ) {
        if (event == null || event.getWorkerUserId() == null || event.getReviewId() == null || since == null || until == null) {
            return Optional.empty();
        }
        if (!botToken.isBlank()) {
            return eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenAndDetailsContainingOrderByCreatedAtDesc(
                    event.getWorkerUserId(),
                    REVIEW_CREDENTIAL_COPIES,
                    event.getReviewId(),
                    since,
                    until,
                    botToken
            );
        }

        return eventRepository.findTopByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                event.getWorkerUserId(),
                REVIEW_CREDENTIAL_COPIES,
                event.getReviewId(),
                since,
                until
        );
    }

    private boolean copiedCredentialBetween(
            WorkerActivityEvent event,
            WorkerActivityAction action,
            LocalDateTime since,
            LocalDateTime until,
            String botToken
    ) {
        if (event == null || event.getWorkerUserId() == null || event.getReviewId() == null || since == null || until == null) {
            return false;
        }
        if (!botToken.isBlank()) {
            return eventRepository.existsByWorkerUserIdAndActionAndReviewIdAndCreatedAtBetweenAndDetailsContaining(
                    event.getWorkerUserId(),
                    action,
                    event.getReviewId(),
                    since,
                    until,
                    botToken
            );
        }
        return eventRepository.existsByWorkerUserIdAndActionInAndReviewIdAndCreatedAtBetween(
                event.getWorkerUserId(),
                List.of(action),
                event.getReviewId(),
                since,
                until
        );
    }

    private long secondsBetween(WorkerActivityEvent first, WorkerActivityEvent second) {
        if (first == null || second == null || first.getCreatedAt() == null || second.getCreatedAt() == null) {
            return Long.MAX_VALUE;
        }
        long seconds = Duration.between(first.getCreatedAt(), second.getCreatedAt()).getSeconds();
        return seconds < 0 ? Long.MAX_VALUE : seconds;
    }

    private List<WorkerActivityAction> workClosingActions() {
        return List.of(
                WorkerActivityAction.REVIEW_NAGUL,
                WorkerActivityAction.BAD_TASK_COMPLETE,
                WorkerActivityAction.RECOVERY_TASK_COMPLETE
        );
    }

    private String botToken(WorkerActivityEvent event) {
        String botId = detailValue(event == null ? null : event.getDetails(), "botId");
        return botId.isBlank() || "-".equals(botId) ? "" : "botId=" + botId + ";";
    }

    private String detailValue(String details, String key) {
        if (details == null || details.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        String prefix = key + "=";
        for (String part : details.split(";")) {
            String value = part.trim();
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private void addFrequencyFinding(
            List<RiskFinding> result,
            WorkerActivityEvent event,
            String ruleCode,
            Collection<WorkerActivityAction> actions,
            LocalDateTime since,
            int limit,
            int score,
            String title
    ) {
        long count = eventRepository.countByWorkerUserIdAndActionInAndCreatedAtGreaterThanEqual(
                event.getWorkerUserId(),
                actions,
                since
        );
        if (count >= limit) {
            result.add(new RiskFinding(
                    ruleCode,
                    score,
                    title,
                    "Количество действий: " + count + ", порог: " + limit + "."
            ));
        }
    }

    private void createIncidentIfNeeded(WorkerActivityEvent event, User workerUser, RiskFinding finding) {
        if (duplicateOpenIncidentExists(event, finding)) {
            return;
        }

        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setLevel(level(finding.score()));
        incident.setRuleCode(finding.ruleCode());
        incident.setScore(finding.score());
        incident.setWorkerUserId(event.getWorkerUserId());
        incident.setWorkerUsername(event.getWorkerUsername());
        incident.setWorkerName(event.getWorkerName());
        incident.setActivityEventId(event.getId());
        incident.setAction(event.getAction().name());
        incident.setEntityType(event.getEntityType());
        incident.setEntityId(event.getEntityId());
        incident.setOrderId(event.getOrderId());
        incident.setReviewId(event.getReviewId());
        incident.setTitle(limit(finding.title(), 180));
        incident.setMessage(limit(message(event, finding), DETAILS_LIMIT));
        incident.setDetails(limit(finding.details() + sourceContextText(event), DETAILS_LIMIT));
        WorkerRiskIncident savedIncident = incidentRepository.save(incident);

        notifySafely(() -> notifyManagers(workerUser, savedIncident), workerUser.getId(), savedIncident.getId());
        log.warn(
                "Подозрительное действие специалиста: incidentId={}, workerUserId={}, username={}, rule={}, score={}, action={}, entityType={}, entityId={}, orderId={}, reviewId={}",
                savedIncident.getId(),
                event.getWorkerUserId(),
                event.getWorkerUsername(),
                finding.ruleCode(),
                finding.score(),
                event.getAction(),
                event.getEntityType(),
                event.getEntityId(),
                event.getOrderId(),
                event.getReviewId()
        );
    }

    private boolean duplicateOpenIncidentExists(WorkerActivityEvent event, RiskFinding finding) {
        LocalDateTime baseTime = event.getCreatedAt() == null ? LocalDateTime.now() : event.getCreatedAt();
        LocalDateTime since = baseTime.minusMinutes(Math.max(1, duplicateWindowMinutes));
        if (event.getReviewId() != null) {
            return incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndReviewIdAndCreatedAtGreaterThanEqual(
                    event.getWorkerUserId(),
                    finding.ruleCode(),
                    WorkerRiskIncidentStatus.OPEN,
                    event.getReviewId(),
                    since
            );
        }
        if (event.getOrderId() != null) {
            return incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndOrderIdAndCreatedAtGreaterThanEqual(
                    event.getWorkerUserId(),
                    finding.ruleCode(),
                    WorkerRiskIncidentStatus.OPEN,
                    event.getOrderId(),
                    since
            );
        }
        if (event.getEntityType() != null && event.getEntityId() != null) {
            return incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndEntityTypeAndEntityIdAndCreatedAtGreaterThanEqual(
                    event.getWorkerUserId(),
                    finding.ruleCode(),
                    WorkerRiskIncidentStatus.OPEN,
                    event.getEntityType(),
                    event.getEntityId(),
                    since
            );
        }
        return incidentRepository.existsByWorkerUserIdAndRuleCodeAndStatusAndCreatedAtGreaterThanEqual(
                event.getWorkerUserId(),
                finding.ruleCode(),
                WorkerRiskIncidentStatus.OPEN,
                since
        );
    }

    private void notifyManagers(User workerUser, WorkerRiskIncident incident) {
        recipients(workerUser).values().forEach(recipient -> {
            String text = managerNotificationText(workerUser, incident, recipient.includeLogin());
            String telegramText = managerNotificationTelegramText(workerUser, incident, recipient.includeLogin());
            notifyUser(recipient.user(), "Проверьте действия специалиста", text, telegramText, incident.getId());
        });
    }

    private String managerNotificationText(User workerUser, WorkerRiskIncident incident, boolean includeLogin) {
        return "Система заметила подозрительное действие специалиста."
                + "\nСпециалист: " + workerName(workerUser)
                + (includeLogin ? "\nЛогин: " + clean(workerUser.getUsername()) : "")
                + "\nПричина: " + incident.getTitle()
                + "\nРиск: " + incident.getScore()
                + incidentContext(incident)
                + "\n\nРекомендуется выборочно проверить фактическое выполнение.";
    }

    private String managerNotificationTelegramText(User workerUser, WorkerRiskIncident incident, boolean includeLogin) {
        return "Система заметила подозрительное действие специалиста."
                + "\nСпециалист: " + html(clean(workerName(workerUser)))
                + (includeLogin ? "\nЛогин: " + html(clean(workerUser.getUsername())) : "")
                + "\nПричина: " + html(clean(incident.getTitle()))
                + "\nРиск: " + incident.getScore()
                + incidentContextHtml(incident)
                + "\n\nРекомендуется выборочно проверить фактическое выполнение.";
    }

    private String incidentContext(WorkerRiskIncident incident) {
        return "\nДействие: " + clean(incident.getAction())
                + "\n" + entityLinksPlain(incident)
                + "\nОбъект: " + clean(incident.getEntityType()) + " #" + valueOrDash(incident.getEntityId())
                + "\nДетали: " + clean(incident.getDetails());
    }

    private String incidentContextHtml(WorkerRiskIncident incident) {
        return "\nДействие: " + html(clean(incident.getAction()))
                + "\n" + entityLinksHtml(incident)
                + "\nОбъект: " + html(clean(incident.getEntityType())) + " #" + valueOrDash(incident.getEntityId())
                + "\nДетали: " + html(clean(incident.getDetails()));
    }

    private String entityLinksPlain(WorkerRiskIncident incident) {
        StringBuilder result = new StringBuilder();
        companyId(incident).ifPresent(companyId -> result.append("Компания: №").append(companyId).append(" "));
        result.append("Заказ: #").append(valueOrDash(incident == null ? null : incident.getOrderId()));
        result.append("\nОтзыв: #").append(valueOrDash(incident == null ? null : incident.getReviewId()));
        return result.toString();
    }

    private String entityLinksHtml(WorkerRiskIncident incident) {
        StringBuilder result = new StringBuilder();
        companyId(incident).ifPresent(companyId -> result
                .append("Компания: ")
                .append(link("№" + companyId, managerBoardCompanyUrl(companyId)))
                .append(" "));
        result.append("Заказ: ")
                .append(incident != null && incident.getOrderId() != null
                        ? link("#" + incident.getOrderId(), orderUrl(incident.getOrderId(), null))
                        : "#-");
        result.append("\nОтзыв: ")
                .append(incident != null && incident.getOrderId() != null && incident.getReviewId() != null
                        ? link("#" + incident.getReviewId(), orderUrl(incident.getOrderId(), incident.getReviewId()))
                        : "#" + valueOrDash(incident == null ? null : incident.getReviewId()));
        return result.toString();
    }

    private Optional<Long> companyId(WorkerRiskIncident incident) {
        if (incident == null || incident.getOrderId() == null) {
            return Optional.empty();
        }
        try {
            return orderRepository.findCompanyIdByOrderId(incident.getOrderId());
        } catch (RuntimeException e) {
            log.warn("Не удалось получить companyId для risk incident orderId={}: {}", incident.getOrderId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String managerBoardCompanyUrl(Long companyId) {
        return baseUrl() + "/manager?section=orders&companyId=" + companyId;
    }

    private String orderUrl(Long orderId, Long reviewId) {
        String url = baseUrl() + "/manager/orders/0/" + orderId;
        return reviewId == null ? url : url + "?reviewId=" + reviewId;
    }

    private String link(String label, String url) {
        return "<a href=\"" + htmlAttribute(url) + "\">" + html(label) + "</a>";
    }

    private String baseUrl() {
        String value = appBaseUrl == null || appBaseUrl.isBlank() ? "https://o-ogo.ru" : appBaseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? "https://o-ogo.ru" : value;
    }

    private Map<Long, RiskNotificationRecipient> recipients(User workerUser) {
        Map<Long, RiskNotificationRecipient> result = new LinkedHashMap<>();

        if (workerUser.getManagers() != null) {
            workerUser.getManagers().stream()
                    .filter(Objects::nonNull)
                    .map(Manager::getUser)
                    .forEach(user -> addRecipient(result, user, false));
        }

        safeUsers(userService.getAllOwners("ROLE_OWNER")).forEach(user -> addRecipient(result, user, true));
        safeUsers(userService.getAllOwners("ROLE_ADMIN")).forEach(user -> addRecipient(result, user, true));
        result.remove(workerUser.getId());
        return result;
    }

    private List<User> safeUsers(List<User> users) {
        return users == null ? List.of() : users;
    }

    private void addRecipient(Map<Long, RiskNotificationRecipient> recipients, User user, boolean includeLogin) {
        if (user == null || user.getId() == null || !user.isActive()) {
            return;
        }
        recipients.merge(
                user.getId(),
                new RiskNotificationRecipient(user, includeLogin),
                (existing, incoming) -> existing.includeLogin() ? existing : incoming
        );
    }

    private void notifyUser(User user, String title, String text, String telegramText, Long sourceId) {
        boolean alreadyOpen = false;
        try {
            alreadyOpen = personalReminderService.hasOpenSystemReminder(user, SOURCE_WORKER_RISK_INCIDENT, sourceId);
            if (!alreadyOpen) {
                personalReminderService.createSystemReminderDueNow(
                        user,
                        limit(title, 120),
                        limit(text, 1000),
                        SOURCE_WORKER_RISK_INCIDENT,
                        sourceId,
                        null
                );
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось создать предупреждение о подозрительном действии userId={}", user.getId(), e);
            alreadyOpen = true;
        }

        if (!alreadyOpen && user.getTelegramChatId() != null) {
            try {
                telegramService.sendMessageWithInlineKeyboard(
                        user.getTelegramChatId(),
                        telegramText,
                        "HTML",
                        WorkerRiskTelegramCallbackService.keyboard(sourceId)
                );
            } catch (RuntimeException e) {
                log.warn("Не удалось отправить Telegram-предупреждение о подозрительном действии userId={}", user.getId(), e);
            }
        }
    }

    private WorkerRiskIncidentLevel level(int score) {
        if (score >= 80) {
            return WorkerRiskIncidentLevel.HIGH_RISK;
        }
        if (score >= 50) {
            return WorkerRiskIncidentLevel.MANAGER_REVIEW;
        }
        return WorkerRiskIncidentLevel.WARNING;
    }

    private String message(WorkerActivityEvent event, RiskFinding finding) {
        return finding.title()
                + "\nРиск: " + finding.score()
                + "\nДействие: " + event.getAction()
                + "\nЗаказ: #" + valueOrDash(event.getOrderId())
                + "\nОтзыв: #" + valueOrDash(event.getReviewId())
                + "\nОбъект: " + clean(event.getEntityType()) + " #" + valueOrDash(event.getEntityId())
                + "\n" + finding.details()
                + sourceContextText(event);
    }

    private String sourceContextText(WorkerActivityEvent event) {
        String page = detailValue(event == null ? null : event.getDetails(), "sourcePage");
        String entry = detailValue(event == null ? null : event.getDetails(), "sourceEntry");
        String section = firstNonBlank(
                detailValue(event == null ? null : event.getDetails(), "sourceSection"),
                event == null ? null : event.getSection()
        );

        String place = sourcePageLabel(page);
        String entryLabel = sourceEntryLabel(entry);
        String sectionLabel = sourceSectionLabel(section);

        if (place.isBlank() && entryLabel.isBlank() && sectionLabel.isBlank()) {
            return "";
        }

        StringBuilder result = new StringBuilder("\nМесто: ");
        result.append(place.isBlank() ? "Не указано" : place);
        if (!entryLabel.isBlank()) {
            result.append(", вход: ").append(entryLabel);
        }
        if (!sectionLabel.isBlank()) {
            result.append(", раздел: ").append(sectionLabel);
        }
        return result.toString();
    }

    private String detailValue(String details, String key) {
        if (details == null || key == null || key.isBlank()) {
            return "";
        }
        String prefix = key + "=";
        for (String part : details.split(";")) {
            String cleanPart = part.trim();
            if (cleanPart.startsWith(prefix)) {
                return clean(cleanPart.substring(prefix.length()));
            }
        }
        return "";
    }

    private String sourcePageLabel(String page) {
        return switch (clean(page)) {
            case "order-details" -> "Детали заказа";
            case "worker-board" -> "Специалист";
            default -> clean(page);
        };
    }

    private String sourceEntryLabel(String entry) {
        return switch (clean(entry)) {
            case "worker-all" -> "Специалист -> Все";
            default -> clean(entry);
        };
    }

    private String sourceSectionLabel(String section) {
        return switch (clean(section)) {
            case "all" -> "Все";
            case "publish" -> "Публикация";
            case "nagul" -> "Выгул";
            case "recovery" -> "Восстановление";
            case "bad" -> "Плохие";
            case "new" -> "Новые";
            case "correct" -> "Коррекция";
            default -> clean(section);
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!clean(value).isBlank()) {
                return clean(value);
            }
        }
        return "";
    }

    private String workerName(User user) {
        String fio = clean(user == null ? null : user.getFio());
        return fio.isBlank() ? clean(user == null ? null : user.getUsername()) : fio;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String html(String value) {
        return clean(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String htmlAttribute(String value) {
        return html(value).replace("\"", "&quot;");
    }

    private String valueOrDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private void notifySafely(Runnable action, Long workerUserId, Long incidentId) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Уведомление о подозрительном действии не отправлено workerUserId={}, incidentId={}: {}",
                    workerUserId, incidentId, e.getMessage());
            log.debug("Worker risk notification failed", e);
        }
    }

    private record RiskNotificationRecipient(User user, boolean includeLogin) {
    }

    private record RiskFinding(String ruleCode, int score, String title, String details) {
    }
}
