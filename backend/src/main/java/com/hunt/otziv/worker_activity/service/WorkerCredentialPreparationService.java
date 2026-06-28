package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.dto.WorkerCredentialPreparationResponse;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparation;
import com.hunt.otziv.worker_activity.model.WorkerCredentialPreparationScope;
import com.hunt.otziv.worker_activity.repository.WorkerCredentialPreparationRepository;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkerCredentialPreparationService {

    private static final int VALUE_LIMIT = 80;

    private final WorkerCredentialPreparationRepository repository;
    private final UserService userService;
    private final WorkerActivityService workerActivityService;

    @Transactional
    public void recordCopy(
            Authentication authentication,
            Review review,
            String field,
            String sourcePage,
            String sourceEntry,
            String sourceSection
    ) {
        if (!workerActivityService.isPlainWorker(authentication) || review == null || review.getId() == null) {
            return;
        }

        WorkerCredentialPreparationScope scope = scope(sourcePage, sourceEntry, sourceSection);
        if (scope == null) {
            return;
        }

        User workerUser = currentUser(authentication);
        if (workerUser == null || workerUser.getId() == null) {
            return;
        }

        Long botId = botId(review.getBot());
        WorkerCredentialPreparation preparation = repository
                .findByWorkerUserIdAndScope(workerUser.getId(), scope)
                .orElseGet(WorkerCredentialPreparation::new);

        boolean sameReviewBot = Objects.equals(preparation.getReviewId(), review.getId())
                && Objects.equals(preparation.getBotId(), botId);
        if (!sameReviewBot) {
            preparation.setWorkerUserId(workerUser.getId());
            preparation.setScope(scope);
            preparation.setReviewId(review.getId());
            preparation.setBotId(botId);
            preparation.setLoginCopiedAt(null);
            preparation.setPasswordCopiedAt(null);
        }

        LocalDateTime now = LocalDateTime.now();
        if ("login".equals(field)) {
            preparation.setLoginCopiedAt(now);
        } else if ("password".equals(field)) {
            preparation.setPasswordCopiedAt(now);
        } else {
            return;
        }
        preparation.setSourcePage(limit(sourcePage));
        preparation.setSourceEntry(limit(sourceEntry));
        preparation.setSourceSection(limit(sourceSection));
        preparation.setUpdatedAt(now);
        repository.save(preparation);
    }

    @Transactional
    public void clear(Authentication authentication, WorkerCredentialPreparationScope scope) {
        if (!workerActivityService.isPlainWorker(authentication) || scope == null) {
            return;
        }

        User workerUser = currentUser(authentication);
        if (workerUser == null || workerUser.getId() == null) {
            return;
        }
        repository.deleteByWorkerUserIdAndScope(workerUser.getId(), scope);
    }

    @Transactional(readOnly = true)
    public WorkerCredentialPreparationResponse active(Authentication authentication, WorkerCredentialPreparationScope scope) {
        if (!workerActivityService.isPlainWorker(authentication) || scope == null) {
            return null;
        }

        User workerUser = currentUser(authentication);
        if (workerUser == null || workerUser.getId() == null) {
            return null;
        }
        return repository.findByWorkerUserIdAndScope(workerUser.getId(), scope)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<CredentialPreparationBlock> blockUntilReady(
            Authentication authentication,
            WorkerCredentialPreparationScope scope,
            Long reviewId,
            Long botId,
            int waitSeconds
    ) {
        if (!workerActivityService.isPlainWorker(authentication) || scope == null || reviewId == null) {
            return Optional.empty();
        }

        User workerUser = currentUser(authentication);
        if (workerUser == null || workerUser.getId() == null) {
            return Optional.empty();
        }

        int normalizedWaitSeconds = Math.max(1, waitSeconds);
        return repository.findByWorkerUserIdAndScope(workerUser.getId(), scope)
                .map(preparation -> Optional.ofNullable(blockReason(
                        preparation,
                        reviewId,
                        botId,
                        normalizedWaitSeconds
                )))
                .orElseGet(() -> Optional.of(missingPreparationBlock(normalizedWaitSeconds)));
    }

    private WorkerCredentialPreparationScope scope(String sourcePage, String sourceEntry, String sourceSection) {
        String page = normalize(sourcePage);
        String entry = normalize(sourceEntry);
        String section = normalize(sourceSection);

        if ("worker-board".equals(page) && "publish".equals(section)) {
            return WorkerCredentialPreparationScope.PUBLISH;
        }
        if ("worker-board".equals(page) && "nagul".equals(section)) {
            return WorkerCredentialPreparationScope.NAGUL;
        }
        if ("order-details".equals(page) && "worker-all".equals(entry)) {
            return WorkerCredentialPreparationScope.PUBLISH;
        }
        return null;
    }

    private WorkerCredentialPreparationResponse toResponse(WorkerCredentialPreparation preparation) {
        return new WorkerCredentialPreparationResponse(
                preparation.getScope().name(),
                preparation.getReviewId(),
                preparation.getBotId(),
                dateValue(preparation.getLoginCopiedAt()),
                dateValue(preparation.getPasswordCopiedAt()),
                dateValue(preparation.getUpdatedAt())
        );
    }

    private CredentialPreparationBlock blockReason(
            WorkerCredentialPreparation preparation,
            Long reviewId,
            Long botId,
            int waitSeconds
    ) {
        if (preparation == null
                || !Objects.equals(preparation.getReviewId(), reviewId)
                || !Objects.equals(preparation.getBotId(), botId)
                || preparation.getLoginCopiedAt() == null
                || preparation.getPasswordCopiedAt() == null) {
            return missingPreparationBlock(waitSeconds);
        }

        LocalDateTime lastCopyAt = preparation.getLoginCopiedAt().isAfter(preparation.getPasswordCopiedAt())
                ? preparation.getLoginCopiedAt()
                : preparation.getPasswordCopiedAt();
        LocalDateTime readyAt = lastCopyAt.plusSeconds(waitSeconds);
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(readyAt)) {
            return null;
        }

        long remainingSeconds = Math.max(1, Duration.between(now, readyAt).toSeconds() + 1);
        return new CredentialPreparationBlock(
                "После копирования логина и пароля подождите еще " + remainingSeconds + " сек.",
                remainingSeconds
        );
    }

    private CredentialPreparationBlock missingPreparationBlock(int waitSeconds) {
        return new CredentialPreparationBlock("Сначала скопируйте логин и пароль аккаунта.", waitSeconds);
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return null;
        }
        return userService.findByUserNameWithAssignments(authentication.getName()).orElse(null);
    }

    private Long botId(Bot bot) {
        return bot == null ? null : bot.getId();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String limit(String value) {
        if (value == null || value.length() <= VALUE_LIMIT) {
            return value;
        }
        return value.substring(0, VALUE_LIMIT);
    }

    private String dateValue(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    public record CredentialPreparationBlock(String message, long remainingSeconds) {
    }
}
