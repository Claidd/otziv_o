package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewBotAssignmentGuardService {

    private static final Long STUB_BOT_ID = 1L;
    private static final EnumSet<BadReviewTaskStatus> COMPANY_BAD_TASK_STATUSES =
            EnumSet.of(BadReviewTaskStatus.NEW, BadReviewTaskStatus.DONE);
    private static final EnumSet<ReviewRecoveryTaskStatus> COMPANY_RECOVERY_TASK_STATUSES =
            EnumSet.of(ReviewRecoveryTaskStatus.PLANNED, ReviewRecoveryTaskStatus.DONE);

    private final ReviewRepository reviewRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRecoveryTaskRepository recoveryTaskRepository;
    private final BotsRepository botsRepository;

    public AssignmentScope scope(Long companyId, Long excludedReviewId) {
        return new AssignmentScope(companyId, excludedReviewId, null, null);
    }

    public AssignmentScope scopeForBadTask(Long companyId, Long excludedTaskId) {
        return new AssignmentScope(companyId, null, excludedTaskId, null);
    }

    public AssignmentScope scopeForRecoveryTask(Long companyId, Long excludedTaskId, Long excludedReviewId) {
        return new AssignmentScope(companyId, excludedReviewId, null, excludedTaskId);
    }

    @Transactional(readOnly = true)
    public Set<Long> blockedBotIds(AssignmentScope scope) {
        requireCompany(scope);
        try {
            Set<Long> blocked = new HashSet<>();
            addAll(blocked, reviewRepository.findUsedBotIdsByCompanyId(scope.companyId()));
            addAll(blocked, reviewRepository.findReservedBotIdsByUnpublishedReviews(scope.excludedReviewId()));
            addAll(blocked, badReviewTaskRepository.findBotIdsByStatus(
                    BadReviewTaskStatus.NEW,
                    scope.excludedBadTaskId()
            ));
            addAll(blocked, recoveryTaskRepository.findBotIdsByStatus(
                    ReviewRecoveryTaskStatus.PLANNED,
                    scope.excludedRecoveryTaskId()
            ));
            addAll(blocked, badReviewTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                    scope.companyId(),
                    COMPANY_BAD_TASK_STATUSES,
                    scope.excludedBadTaskId()
            ));
            addAll(blocked, recoveryTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                    scope.companyId(),
                    COMPANY_RECOVERY_TASK_STATUSES,
                    scope.excludedRecoveryTaskId()
            ));
            blocked.remove(null);
            blocked.remove(STUB_BOT_ID);
            return blocked;
        } catch (RuntimeException e) {
            log.error("Не удалось проверить занятость аккаунтов для компании {}", scope.companyId(), e);
            throw new IllegalStateException(
                    "Назначение аккаунта остановлено: не удалось проверить историю компании и активные очереди",
                    e
            );
        }
    }

    @Transactional
    public Optional<Bot> lockIfEligible(Bot candidate, AssignmentScope scope) {
        if (candidate == null || candidate.getId() == null || STUB_BOT_ID.equals(candidate.getId())) {
            return Optional.empty();
        }

        Bot locked = botsRepository.findByIdForAssignmentLock(candidate.getId())
                .orElseThrow(() -> new IllegalStateException("Аккаунт для назначения не найден: " + candidate.getId()));
        if (!locked.isActive()) {
            return Optional.empty();
        }

        Set<Long> blocked = blockedBotIds(scope);
        if (blocked.contains(locked.getId())) {
            log.info("Аккаунт {} отклонен общей проверкой назначения для компании {}",
                    locked.getId(), scope.companyId());
            return Optional.empty();
        }
        return Optional.of(locked);
    }

    public boolean isEligible(Bot bot, Collection<Long> blockedBotIds) {
        return bot != null
                && bot.getId() != null
                && !STUB_BOT_ID.equals(bot.getId())
                && bot.isActive()
                && (blockedBotIds == null || !blockedBotIds.contains(bot.getId()));
    }

    private void requireCompany(AssignmentScope scope) {
        if (scope == null || scope.companyId() == null) {
            throw new IllegalArgumentException("Невозможно назначить аккаунт: компания не определена");
        }
    }

    private void addAll(Set<Long> target, Collection<Long> values) {
        if (values != null) {
            values.stream().filter(Objects::nonNull).forEach(target::add);
        }
    }

    public record AssignmentScope(
            Long companyId,
            Long excludedReviewId,
            Long excludedBadTaskId,
            Long excludedRecoveryTaskId
    ) {
    }
}
