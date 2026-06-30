package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewRecoveryGateService {

    public static final int RECOVERY_SCHEDULE_STEP_DAYS = 3;

    private final ReviewRepository reviewRepository;
    private final ReviewRecoveryTaskRepository taskRepository;

    @Transactional(readOnly = true)
    public boolean hasActiveRecoveryTasks(Long orderId) {
        if (orderId == null) {
            return false;
        }
        return taskRepository.countByOrderIdAndStatusAndBatchStatus(
                orderId,
                ReviewRecoveryTaskStatus.PLANNED,
                ReviewRecoveryBatchStatus.OPEN
        ) > 0;
    }

    @Transactional(readOnly = true)
    public LocalDate nextScheduledDate(Long orderId) {
        LocalDate today = LocalDate.now();
        if (orderId == null) {
            return today.plusDays(RECOVERY_SCHEDULE_STEP_DAYS);
        }
        LocalDate baseDate = maxDate(
                reviewRepository.maxPublishedDateByOrderId(orderId),
                taskRepository.maxScheduledDateByOrderId(orderId, ReviewRecoveryTaskStatus.CANCELLED)
        );
        return maxDate(baseDate, today).plusDays(RECOVERY_SCHEDULE_STEP_DAYS);
    }

    private LocalDate maxDate(LocalDate first, LocalDate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }
}
