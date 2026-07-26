package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerReportReviewAccessPolicyTest {

    private ManagerReportReviewSessionRepository repository;
    private AppSettingService settings;
    private ManagerReportReviewQualityService qualityService;
    private ManagerReportReviewAccessPolicy policy;
    private User manager;

    @BeforeEach
    void setUp() {
        repository = mock(ManagerReportReviewSessionRepository.class);
        settings = mock(AppSettingService.class);
        qualityService = mock(ManagerReportReviewQualityService.class);
        when(settings.getBoolean("manager.report-review.enabled", true)).thenReturn(true);
        when(settings.getBoolean("manager.report-review.restriction-enabled", true)).thenReturn(true);
        when(settings.getInt("manager.report-review.reminder-three-minutes", 180)).thenReturn(180);
        when(qualityService.aiAvailable()).thenReturn(true);
        policy = new ManagerReportReviewAccessPolicy(repository, settings, qualityService);
        manager = User.builder().id(17L).username("manager").active(true).build();
    }

    @Test
    void restrictsManagerWhenUnfinishedReportIsOlderThanThreeHours() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        review.setSummaryDate(LocalDate.of(2026, 7, 25));
        review.setDeadlineStartedAt(LocalDateTime.now().minusHours(4));
        review.setStatus(ManagerReportReviewStatus.PLAN_PENDING);
        when(repository
                .findFirstByManagerUserIdAndTestModeFalseAndDeadlineStartedAtLessThanEqualAndAiUnavailableStartedAtIsNullAndStatusNotInAndCompletedAtIsNullOrderByDeadlineStartedAtAsc(
                        eq(17L),
                        any(LocalDateTime.class),
                        any()
                ))
                .thenReturn(Optional.of(review));

        ManagerReportReviewAccessPolicy.AccessState state = policy.state(manager);

        assertThat(state.restricted()).isTrue();
        assertThat(state.reviewId()).isEqualTo(41L);
        assertThat(state.message()).contains("Telegram");
    }

    @Test
    void keepsAccessWhenNoOverdueUnfinishedReportExists() {
        when(repository
                .findFirstByManagerUserIdAndTestModeFalseAndDeadlineStartedAtLessThanEqualAndAiUnavailableStartedAtIsNullAndStatusNotInAndCompletedAtIsNullOrderByDeadlineStartedAtAsc(
                        eq(17L),
                        any(LocalDateTime.class),
                        any()
                ))
                .thenReturn(Optional.empty());

        assertThat(policy.state(manager).restricted()).isFalse();
    }

    @Test
    void featureFlagCanDisableRestriction() {
        when(settings.getBoolean("manager.report-review.restriction-enabled", true)).thenReturn(false);

        assertThat(policy.state(manager).restricted()).isFalse();
    }

    @Test
    void neverRestrictsAccessWhileAutomaticVerificationIsUnavailable() {
        when(qualityService.aiAvailable()).thenReturn(false);

        assertThat(policy.state(manager).restricted()).isFalse();
    }
}
