package com.hunt.otziv.worker_activity.account_action;

import com.hunt.otziv.business_audit.api.CredentialRevealEvidence;
import com.hunt.otziv.worker_activity.api.WorkerAccountBlockGuard;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.model.WorkerActivityEvent;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** The same durable evidence is used before a block and when evaluating its risk. */
@Service
@RequiredArgsConstructor
public class WorkerAccountCredentialGuard implements WorkerAccountBlockGuard {
    private static final List<WorkerActivityAction> ACCOUNT_CHANGES = List.of(
            WorkerActivityAction.REVIEW_BOT_CHANGE, WorkerActivityAction.REVIEW_BOT_DEACTIVATE,
            WorkerActivityAction.BAD_TASK_BOT_CHANGE, WorkerActivityAction.BAD_TASK_BOT_DEACTIVATE,
            WorkerActivityAction.RECOVERY_TASK_BOT_CHANGE, WorkerActivityAction.RECOVERY_TASK_BOT_DEACTIVATE);
    private static final LocalDateTime HISTORY_START = LocalDateTime.of(2000, 1, 1, 0, 0);
    private final CredentialRevealEvidence credentialEvidence;
    private final WorkerActivityEventRepository activityRepository;

    @Override
    public void assertCurrentActorCanBlock(String entityType, Long entityId, Long botId) {
        assertCanBlock(SecurityContextHolder.getContext().getAuthentication(), entityType, entityId, botId);
    }

    @Override
    public void assertCanBlock(Authentication actor, String entityType, Long entityId, Long botId) {
        if (!WorkerAccountActionCooldownService.isPlainWorker(actor)) return;
        if (!hasBothCredentials(actor.getName(), entityType, entityId, botId, LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Перед нажатием «Блок» скопируйте логин и пароль текущего аккаунта в этой карточке.");
        }
    }

    public boolean hasBothCredentials(String actor, String entityType, Long entityId, Long botId, LocalDateTime at) {
        return hasBothCredentials(actor, entityType, entityId, botId, at, null);
    }

    public boolean hasBothCredentials(String actor, String entityType, Long entityId, Long botId,
            LocalDateTime at, Long evaluatingEventId) {
        if (actor == null || actor.isBlank() || entityType == null || entityId == null
                || botId == null || botId <= 0 || at == null) return false;

        // Copies do not expire while the account remains assigned to this card.
        // A previous assignment (even of the same bot) must not supply evidence.
        // Exclude the current event by ID: MySQL rounds timestamps to microseconds.
        LocalDateTime since = activityRepository
                .findTopByEntityTypeAndEntityIdAndActionInAndIdNotAndCreatedAtLessThanEqualOrderByCreatedAtDesc(
                        entityType, entityId, ACCOUNT_CHANGES, evaluatingEventId == null ? 0L : evaluatingEventId, at)
                .map(WorkerActivityEvent::getCreatedAt).orElse(HISTORY_START);
        return credentialEvidence.hasBothCredentials(actor, entityType, entityId, botId, since, at);
    }
}
