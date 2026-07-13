package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewBotAssignmentGuardServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private ReviewRecoveryTaskRepository recoveryTaskRepository;

    @Mock
    private BotsRepository botsRepository;

    private ReviewBotAssignmentGuardService service;

    @BeforeEach
    void setUp() {
        service = new ReviewBotAssignmentGuardService(
                reviewRepository,
                badReviewTaskRepository,
                recoveryTaskRepository,
                botsRepository
        );
    }

    @Test
    void blockedBotIdsCombinesCompanyHistoryAndAllActiveQueues() {
        ReviewBotAssignmentGuardService.AssignmentScope scope =
                service.scopeForRecoveryTask(10L, 40L, 100L);

        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of(11L, 1L));
        when(reviewRepository.findReservedBotIdsByUnpublishedReviews(100L)).thenReturn(Set.of(12L));
        when(badReviewTaskRepository.findBotIdsByStatus(BadReviewTaskStatus.NEW, null))
                .thenReturn(Set.of(13L));
        when(recoveryTaskRepository.findBotIdsByStatus(ReviewRecoveryTaskStatus.PLANNED, 40L))
                .thenReturn(Set.of(14L));
        when(badReviewTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                eq(10L), anyCollection(), isNull()))
                .thenReturn(Set.of(15L));
        when(recoveryTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                eq(10L), anyCollection(), eq(40L)))
                .thenReturn(Set.of(16L));

        Set<Long> blocked = service.blockedBotIds(scope);

        assertEquals(Set.of(11L, 12L, 13L, 14L, 15L, 16L), blocked);
    }

    @Test
    void blockedBotIdsFailsClosedWhenAnyHistoryCheckFails() {
        when(reviewRepository.findUsedBotIdsByCompanyId(10L))
                .thenThrow(new IllegalStateException("database unavailable"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.blockedBotIds(service.scope(10L, null))
        );

        assertTrue(exception.getMessage().contains("Назначение аккаунта остановлено"));
    }

    @Test
    void lockIfEligibleRejectsAccountAlreadyUsedByCompany() {
        Bot candidate = activeBot(21L);
        when(botsRepository.findByIdForAssignmentLock(21L)).thenReturn(Optional.of(candidate));
        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of(21L));
        allowEmptyQueues();

        Optional<Bot> selected = service.lockIfEligible(candidate, service.scope(10L, 100L));

        assertTrue(selected.isEmpty());
    }

    @Test
    void lockIfEligibleReturnsLockedActiveAccountWhenStillFree() {
        Bot candidate = activeBot(22L);
        when(botsRepository.findByIdForAssignmentLock(22L)).thenReturn(Optional.of(candidate));
        when(reviewRepository.findUsedBotIdsByCompanyId(10L)).thenReturn(Set.of());
        allowEmptyQueues();

        Optional<Bot> selected = service.lockIfEligible(candidate, service.scope(10L, 100L));

        assertEquals(Optional.of(candidate), selected);
    }

    private void allowEmptyQueues() {
        when(reviewRepository.findReservedBotIdsByUnpublishedReviews(100L)).thenReturn(Set.of());
        when(badReviewTaskRepository.findBotIdsByStatus(BadReviewTaskStatus.NEW, null)).thenReturn(Set.of());
        when(recoveryTaskRepository.findBotIdsByStatus(ReviewRecoveryTaskStatus.PLANNED, null))
                .thenReturn(Set.of());
        when(badReviewTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                eq(10L), anyCollection(), isNull()))
                .thenReturn(Set.of());
        when(recoveryTaskRepository.findBotIdsByCompanyIdAndStatusIn(
                eq(10L), anyCollection(), isNull()))
                .thenReturn(Set.of());
    }

    private Bot activeBot(Long id) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setActive(true);
        return bot;
    }
}
