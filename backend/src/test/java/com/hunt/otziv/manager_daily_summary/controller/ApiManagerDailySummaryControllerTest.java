package com.hunt.otziv.manager_daily_summary.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.hunt.otziv.manager_daily_summary.dto.ManagerDailySummaryResponse;
import com.hunt.otziv.manager_daily_summary.service.ManagerDailySummaryService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewQueryService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewTelegramService;
import com.hunt.otziv.manager_daily_summary.service.ManagerSummaryFormatter;
import com.hunt.otziv.manager_daily_summary.service.ManagerSummaryNotificationService;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ApiManagerDailySummaryControllerTest {

    @Mock private ManagerDailySummaryService summaryService;
    @Mock private ManagerSummaryFormatter formatter;
    @Mock private ManagerSummaryNotificationService notificationService;
    @Mock private ManagerReportReviewQueryService reportReviewQueryService;
    @Mock private ManagerReportReviewTelegramService reportReviewTelegramService;
    @Mock private ManagerRepository managerRepository;
    @Mock private UserRepository userRepository;

    @Test
    void sendsFreshAllManagerAuditOnlyToRequester() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        Principal principal = () -> "owner";
        User requester = User.builder()
                .id(11L)
                .username("owner")
                .fio("Владелец")
                .telegramChatId(9911L)
                .active(true)
                .build();
        List<ManagerDailySummaryResponse> rows = List.of();
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(requester));
        when(summaryService.calculate(date, false)).thenReturn(rows);
        when(notificationService.sendTest(requester, rows)).thenReturn(2);

        ApiManagerDailySummaryController controller = new ApiManagerDailySummaryController(
                summaryService,
                formatter,
                notificationService,
                userRepository
        );

        var response = controller.sendTest(date, principal);

        assertThat(response.date()).isEqualTo(date);
        assertThat(response.managerCount()).isZero();
        assertThat(response.messageCount()).isEqualTo(2);
        assertThat(response.recipient()).isEqualTo("Владелец");
        verify(summaryService).calculate(date, false);
        verify(notificationService).sendTest(requester, rows);
    }

    @Test
    void missingTelegramStopsBeforeExpensiveCalculation() {
        Principal principal = () -> "admin";
        User requester = User.builder().id(12L).username("admin").active(true).build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(requester));
        ApiManagerDailySummaryController controller = new ApiManagerDailySummaryController(
                summaryService,
                formatter,
                notificationService,
                userRepository
        );

        assertThatThrownBy(() -> controller.sendTest(LocalDate.of(2026, 7, 25), principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).contains("привяжите Telegram");
                });
        verifyNoInteractions(summaryService, notificationService);
    }

    @Test
    void startsIndependentInteractiveTestForRequestingAdministrator() {
        LocalDate date = LocalDate.of(2026, 7, 26);
        Principal principal = () -> "admin";
        User admin = User.builder()
                .id(12L)
                .username("admin")
                .fio("Администратор")
                .telegramChatId(9912L)
                .active(true)
                .build();
        ManagerDailySummaryResponse row = mock(ManagerDailySummaryResponse.class);
        when(row.managerId()).thenReturn(7L);
        when(row.managerName()).thenReturn("Анжелика Б.");
        Manager sourceManager = Manager.builder().id(7L).build();
        ManagerReportReviewSession review = new ManagerReportReviewSession();
        review.setId(501L);
        review.setIssueCount(3);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(summaryService.calculate(date, false)).thenReturn(List.of(row));
        when(managerRepository.findById(7L)).thenReturn(Optional.of(sourceManager));
        when(reportReviewTelegramService.deliverTest(admin, sourceManager, row))
                .thenReturn(Optional.of(review));

        ApiManagerDailySummaryController controller = new ApiManagerDailySummaryController(
                summaryService,
                formatter,
                notificationService,
                reportReviewQueryService,
                reportReviewTelegramService,
                managerRepository,
                userRepository
        );

        var response = controller.startReviewTest(date, null, principal);

        assertThat(response.reviewId()).isEqualTo(501L);
        assertThat(response.sourceManagerName()).isEqualTo("Анжелика Б.");
        assertThat(response.recipient()).isEqualTo("Администратор");
        assertThat(response.issueCount()).isEqualTo(3);
        verify(reportReviewTelegramService).deliverTest(admin, sourceManager, row);
    }
}
