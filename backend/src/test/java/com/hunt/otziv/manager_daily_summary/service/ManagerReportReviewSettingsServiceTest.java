package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewEvidenceSettings;
import com.hunt.otziv.manager_daily_summary.dto.ManagerReportReviewSettingsRequest;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewStatus;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewIssueRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManagerReportReviewSettingsServiceTest {

    @Mock private AppSettingService settings;
    @Mock private ManagerRepository managerRepository;
    @Mock private ManagerReportReviewSessionRepository sessionRepository;
    @Mock private ManagerReportReviewIssueRepository issueRepository;
    @Mock private ManagerReportReviewEventRepository eventRepository;
    @Mock private ManagerReportReviewAccessPolicy accessPolicy;

    private ManagerReportReviewSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ManagerReportReviewSettingsService(
                settings,
                managerRepository,
                sessionRepository,
                issueRepository,
                eventRepository,
                accessPolicy
        );
    }

    @Test
    void disablingManagerStopsAndClosesCurrentAudit() {
        User user = User.builder()
                .id(17L)
                .fio("Вика Ц.")
                .active(true)
                .build();
        Manager manager = Manager.builder()
                .id(3L)
                .user(user)
                .reportReviewEnabled(true)
                .build();
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(6L);
        review.setManager(manager);
        review.setManagerUserId(17L);
        review.setStatus(ManagerReportReviewStatus.QUESTION_PENDING);
        review.setIssueCount(2);
        ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
        issue.setReview(review);
        issue.setQuestionIndex(0);
        issue.setStatus(ManagerReportReviewIssueStatus.PENDING);

        when(managerRepository.findByIdWithUser(3L)).thenReturn(Optional.of(manager));
        when(sessionRepository.findByManager_IdAndCompletedAtIsNull(3L)).thenReturn(List.of(review));
        when(issueRepository.findByReview_IdOrderByQuestionIndexAsc(6L)).thenReturn(List.of(issue));

        var response = service.updateManager(3L, false);

        assertThat(response.auditEnabled()).isFalse();
        assertThat(manager.isReportReviewEnabled()).isFalse();
        assertThat(review.getStatus()).isEqualTo(ManagerReportReviewStatus.COMPLETED);
        assertThat(review.getAnswerQuality()).isEqualTo("AUDIT_DISABLED");
        assertThat(review.getCompletedAt()).isNotNull();
        assertThat(issue.getStatus()).isEqualTo(ManagerReportReviewIssueStatus.WITHDRAWN);
        verify(managerRepository).save(manager);
        verify(sessionRepository).save(review);
        verify(accessPolicy).invalidate(17L);
        ArgumentCaptor<ManagerReportReviewEvent> event = ArgumentCaptor.forClass(ManagerReportReviewEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("AUDIT_DISABLED");
    }

    @Test
    void enablingManagerDoesNotCreateOrSendAuditImmediately() {
        User user = User.builder().id(17L).fio("Вика Ц.").active(true).build();
        Manager manager = Manager.builder()
                .id(3L)
                .user(user)
                .reportReviewEnabled(false)
                .build();
        when(managerRepository.findByIdWithUser(3L)).thenReturn(Optional.of(manager));

        var response = service.updateManager(3L, true);

        assertThat(response.auditEnabled()).isTrue();
        assertThat(manager.isReportReviewEnabled()).isTrue();
        verify(managerRepository).save(manager);
        verify(accessPolicy).invalidate(17L);
    }

    @Test
    void storesIndependentRolloutAndBudgetsAndInvalidatesWorkingAccessCache() {
        User user = User.builder().id(17L).build();
        when(managerRepository.findAllForReportReviewSettings()).thenReturn(List.of(
                Manager.builder().id(2L).user(user).build()));
        when(managerRepository.existsById(2L)).thenReturn(true);
        defaultReads();
        var evidence = evidence("PILOT", List.of(2L), "ENFORCE", List.of());

        service.update(request(evidence));

        verify(settings).setString("manager-control.diagnostics.mode", "PILOT");
        verify(settings).setString("manager-control.diagnostics.pilot-manager-ids", "2");
        verify(settings).setString("manager.report-review.evidence-mode", "ENFORCE");
        verify(settings).setInt("manager-control.diagnostics.max-requests-per-hour", 40);
        verify(settings).setInt("manager.report-review.answer-assessment-max-tokens", 7000);
        verify(settings).setInt("manager.report-review.answer-assessment-timeout-seconds", 100);
        verify(settings).setBoolean("manager.report-review.answer-assessment-thinking", true);
        verify(settings).setInt("manager.report-review.question-generation-timeout-seconds", 120);
        verify(settings).setBoolean("manager.report-review.question-generation-thinking", false);
        verify(settings).setInt("manager.report-review.evidence-max-age-minutes", 10);
        verify(accessPolicy).invalidate(17L);
    }

    @Test
    void legacyRequestKeepsRolloutSettingsAndIgnoresRetiredHeuristics() {
        defaultReads();
        service.update(request(null));
        verify(settings, never()).setString(eq("manager.report-review.evidence-mode"), anyString());
        verify(settings, never()).setInt(eq("manager.report-review.minimum-answer-score"), anyInt());
        verify(settings, never()).setInt(eq("manager.report-review.fast-paste-seconds"), anyInt());
        verify(settings, never()).setInt(eq("manager.report-review.max-answer-characters"), anyInt());
    }

    @Test
    void rejectsEmptyPilotBeforeWritingAnySettings() {
        assertThatThrownBy(() -> service.update(request(evidence("SHADOW", List.of(), "PILOT", List.of()))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Выберите менеджеров пилота");
        verifyNoInteractions(settings);
    }

    @Test
    void rejectsUnknownManagerBeforeWritingAnySettings() {
        when(managerRepository.existsById(999L)).thenReturn(false);
        assertThatThrownBy(() -> service.update(request(evidence("PILOT", List.of(999L), "SHADOW", List.of()))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("999");
        verifyNoInteractions(settings);
    }

    @Test
    void rejectsUnknownModeInsteadOfSilentlyEnforcing() {
        assertThatThrownBy(() -> service.update(request(evidence("PILTO", List.of(), "SHADOW", List.of()))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Неизвестный режим");
        verifyNoInteractions(settings);
    }

    @Test
    void reportsConfiguredRolloutAndResourceSettings() {
        defaultReads();
        when(settings.getString("manager.report-review.evidence-mode", "SHADOW")).thenReturn("PILOT");
        when(settings.getString("manager.report-review.evidence-pilot-manager-ids", "")).thenReturn("2, 2, 3");
        var response = service.settings();
        assertThat(response.evidence().answerMode()).isEqualTo("PILOT");
        assertThat(response.evidence().answerPilotManagerIds()).containsExactly(2L, 3L);
        assertThat(response.evidence().answerTimeoutSeconds()).isEqualTo(90);
        assertThat(response.evidence().diagnosisMaxOutputTokens()).isEqualTo(8000);
    }

    private void defaultReads() {
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(settings.getString(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
    }

    private ManagerReportReviewSettingsRequest request(ManagerReportReviewEvidenceSettings evidence) {
        // Retired JSON fields may be omitted (zero) by current clients.
        return new ManagerReportReviewSettingsRequest(true, true, true, 8, 60, 10, 60, 180,
                0, 0, 0, 0, 0, 0, 0, 0, 8000, 12000, evidence);
    }

    private ManagerReportReviewEvidenceSettings evidence(String diagnosis, List<Long> diagnosisIds,
                                                        String answer, List<Long> answerIds) {
        return new ManagerReportReviewEvidenceSettings(diagnosis, diagnosisIds, 40, 5000, 45, true,
                answer, answerIds, 7000, 100, true, 120, false, 10);
    }
}
