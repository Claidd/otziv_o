package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagerReportReviewCheckInServiceTest {

    private ManagerReportReviewSessionRepository sessionRepository;
    private ManagerReportReviewEventRepository eventRepository;
    private ManagerReportReviewAccessPolicy accessPolicy;
    private ManagerReportReviewCheckInService service;
    private User manager;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ManagerReportReviewSessionRepository.class);
        eventRepository = mock(ManagerReportReviewEventRepository.class);
        accessPolicy = mock(ManagerReportReviewAccessPolicy.class);
        service = new ManagerReportReviewCheckInService(sessionRepository, eventRepository, accessPolicy);
        manager = User.builder().id(17L).username("manager").active(true).build();
        when(accessPolicy.state(manager)).thenReturn(ManagerReportReviewAccessPolicy.AccessState.allowed());
    }

    @Test
    void startsDeadlineOnlyOnFirstCabinetCheckIn() {
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(41L);
        when(sessionRepository.findFirstByManagerUserIdAndTestModeFalseAndCompletedAtIsNullOrderByCreatedAtDesc(17L))
                .thenReturn(Optional.of(review));

        service.checkIn(manager);

        assertThat(review.getDeadlineStartedAt()).isNotNull();
        verify(sessionRepository).save(review);
        verify(eventRepository).save(any(ManagerReportReviewEvent.class));

        service.checkIn(manager);

        verify(sessionRepository).save(review);
        verify(eventRepository).save(any(ManagerReportReviewEvent.class));
        verify(accessPolicy, times(2)).invalidate(17L);
    }

    @Test
    void doesNotCreateDeadlineWhenThereIsNoPendingReport() {
        when(sessionRepository.findFirstByManagerUserIdAndTestModeFalseAndCompletedAtIsNullOrderByCreatedAtDesc(17L))
                .thenReturn(Optional.empty());

        service.checkIn(manager);

        verify(sessionRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
        verify(accessPolicy).invalidate(17L);
    }
}
