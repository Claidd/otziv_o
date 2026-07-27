package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
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
}
