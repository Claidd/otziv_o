package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.payments.service.BadReviewPaymentInstructionOrchestrator;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonalReminderServiceTest {

    @Mock
    private PersonalReminderRepository reminderRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ReviewRecoveryBatchRepository recoveryBatchRepository;

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private UserService userService;

    @Mock
    private BadReviewPaymentInstructionOrchestrator paymentInstructionOrchestrator;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private PersonalReminderService service;

    @Test
    void listHidesRecoveryCompletionReminderWhenBatchReopened() {
        User user = new User();
        user.setId(5L);
        user.setUsername("manager");

        PersonalReminder staleRecovery = reminder(
                11L,
                "Восстановление завершено: Компания",
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                30L
        );
        staleRecovery.setSourceOrderId(10L);
        PersonalReminder regular = reminder(12L, "Обычная заметка", null, null);

        ReviewRecoveryBatch reopenedBatch = new ReviewRecoveryBatch();
        reopenedBatch.setId(30L);
        reopenedBatch.setStatus(ReviewRecoveryBatchStatus.OPEN);

        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(reminderRepository.findByUserIdAndCompletedAtIsNullOrderByUpdatedAtDesc(5L))
                .thenReturn(List.of(staleRecovery, regular));
        when(recoveryBatchRepository.findById(30L)).thenReturn(Optional.of(reopenedBatch));

        List<PersonalReminderResponse> reminders = service.list(principal("manager"));

        assertEquals(1, reminders.size());
        assertEquals(12L, reminders.getFirst().id());
    }

    @Test
    void paymentCopyIsPreparedCanonicallyAtClickTime() {
        User user = new User();
        user.setId(5L);
        user.setUsername("manager");
        PersonalReminder reminder = reminder(
                13L,
                "Плохой отзыв выполнен: Компания",
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                99L
        );
        reminder.setSourceOrderId(10L);
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(reminderRepository.findByIdAndUserId(13L, 5L)).thenReturn(Optional.of(reminder));
        when(paymentInstructionOrchestrator.prepareCopyTextAuthorized(10L, authentication)).thenReturn("canonical");

        assertEquals("canonical", service.preparePaymentCopyText(principal("manager"), authentication, 13L));
    }

    @Test
    void userCreatedTitleAndTextCannotForgePaymentInstruction() {
        User user = new User();
        user.setId(5L);
        user.setUsername("manager");
        PersonalReminder forged = reminder(14L, "Плохой отзыв выполнен: Компания", null, null);
        forged.setText("Заказ #10");
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(reminderRepository.findByIdAndUserId(14L, 5L)).thenReturn(Optional.of(forged));

        assertThrows(
                ResponseStatusException.class,
                () -> service.preparePaymentCopyText(principal("manager"), authentication, 14L)
        );

        verifyNoInteractions(paymentInstructionOrchestrator);
    }

    @Test
    void trustedSourceWithoutCanonicalOrderIdCannotPreparePaymentInstruction() {
        User user = new User();
        user.setId(5L);
        user.setUsername("manager");
        PersonalReminder incomplete = reminder(
                15L,
                "Плохой отзыв выполнен: Компания",
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                99L
        );
        incomplete.setText("Заказ #10");
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(reminderRepository.findByIdAndUserId(15L, 5L)).thenReturn(Optional.of(incomplete));

        assertThrows(
                ResponseStatusException.class,
                () -> service.preparePaymentCopyText(principal("manager"), authentication, 15L)
        );

        verifyNoInteractions(paymentInstructionOrchestrator);
    }

    private PersonalReminder reminder(Long id, String title, String sourceType, Long sourceId) {
        PersonalReminder reminder = new PersonalReminder();
        reminder.setId(id);
        reminder.setTitle(title);
        reminder.setText("текст");
        reminder.setReminderMode("none");
        reminder.setSourceType(sourceType);
        reminder.setSourceId(sourceId);
        reminder.setCreatedAt(Instant.parse("2026-05-31T06:09:00Z"));
        reminder.setUpdatedAt(Instant.parse("2026-05-31T06:09:00Z"));
        return reminder;
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
