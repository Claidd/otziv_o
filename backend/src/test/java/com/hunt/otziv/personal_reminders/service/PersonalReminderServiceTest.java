package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.personal_reminders.dto.PersonalReminderResponse;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
