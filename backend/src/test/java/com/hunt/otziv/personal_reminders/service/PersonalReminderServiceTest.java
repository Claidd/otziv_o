package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void systemReminderUpsertFlushesCanonicalBeforeRemovingDuplicates() {
        User user = new User();
        user.setId(5L);
        PersonalReminder canonical = reminder(21L, "old", "PAYMENT_RETURN_RECONCILIATION", 99L);
        PersonalReminder duplicate = reminder(22L, "duplicate", "PAYMENT_RETURN_RECONCILIATION", 99L);
        when(reminderRepository
                .findByUserIdAndSourceTypeAndSourceIdAndCompletedAtIsNullOrderByIdAsc(
                        5L, "PAYMENT_RETURN_RECONCILIATION", 99L))
                .thenReturn(List.of(canonical, duplicate));

        service.upsertSystemReminderDueNow(
                user,
                "Нужна сверка",
                "Проверьте выписку",
                "PAYMENT_RETURN_RECONCILIATION",
                99L,
                42L
        );

        assertEquals("Нужна сверка", canonical.getTitle());
        assertEquals("Проверьте выписку", canonical.getText());
        assertEquals("datetime", canonical.getReminderMode());
        assertNotNull(canonical.getRemindAt());
        assertEquals(42L, canonical.getSourceOrderId());
        var ordered = inOrder(reminderRepository);
        ordered.verify(reminderRepository).saveAndFlush(canonical);
        ordered.verify(reminderRepository).deleteAll(List.of(duplicate));
        ordered.verify(reminderRepository).flush();
    }

    @Test
    void paymentReturnReconciliationReminderCannotBeChangedOrDismissedByUser() {
        User user = new User();
        user.setId(5L);
        user.setUsername("manager");
        PersonalReminder reminder = reminder(
                23L,
                "Нужна сверка возврата",
                "PAYMENT_RETURN_RECONCILIATION",
                99L
        );
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(reminderRepository.findByIdAndUserId(23L, 5L)).thenReturn(Optional.of(reminder));

        PersonalReminderRequest update = new PersonalReminderRequest(
                "скрыть", "скрыть", "none", null, null
        );
        assertThrows(ResponseStatusException.class, () -> service.update(principal("manager"), 23L, update));
        assertThrows(ResponseStatusException.class, () -> service.complete(principal("manager"), 23L));
        assertThrows(ResponseStatusException.class, () -> service.delete(principal("manager"), 23L));

        verify(reminderRepository, never()).save(reminder);
        verify(reminderRepository, never()).delete(reminder);
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
