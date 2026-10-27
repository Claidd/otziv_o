package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewAiAvailabilityService {

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewAccessPolicy accessPolicy;

    @Transactional
    public boolean pause(
            ManagerReportReviewSession review,
            LocalDateTime now,
            String source,
            String reason
    ) {
        if (review == null || review.getId() == null) {
            return false;
        }
        var current = sessionRepository.findTimingForUpdate(review.getId()).orElse(null);
        if (!active(current)) return false;
        synchronizeTiming(review, current);
        if (current.getAiUnavailableStartedAt() != null
                || sessionRepository.pauseIfActive(review.getId(), now) != 1) return false;
        review.setAiUnavailableStartedAt(now);
        event(review, "AI_VERIFICATION_PAUSED", source,
                "Срок проверки приостановлен: " + clean(reason));
        accessPolicy.invalidate(review.getManagerUserId());
        return true;
    }

    @Transactional
    public boolean resume(
            ManagerReportReviewSession review,
            LocalDateTime now,
            String source
    ) {
        if (review == null || review.getId() == null) {
            return false;
        }
        var current = sessionRepository.findTimingForUpdate(review.getId()).orElse(null);
        if (!active(current)) return false;
        synchronizeTiming(review, current);
        if (current.getAiUnavailableStartedAt() == null) return false;
        long seconds = Math.max(
                0,
                Duration.between(current.getAiUnavailableStartedAt(), now).toSeconds()
        );
        long totalSeconds = Math.max(0, current.getAiUnavailableSeconds()) + seconds;
        LocalDateTime deadline = current.getDeadlineStartedAt() == null ? null
                : current.getDeadlineStartedAt().plusSeconds(seconds);
        if (sessionRepository.resumeIfPaused(review.getId(), current.getAiUnavailableStartedAt(),
                totalSeconds, deadline, now) != 1) return false;
        review.setAiUnavailableSeconds(totalSeconds);
        review.setDeadlineStartedAt(deadline);
        review.setAiUnavailableStartedAt(null);
        review.setRecoveryAttempts(0);
        review.setRecoveryNextAttemptAt(null);
        event(review, "AI_VERIFICATION_RESUMED", source,
                "Автоматическая проверка восстановлена; срок продлён на " + seconds + " сек.");
        accessPolicy.invalidate(review.getManagerUserId());
        return true;
    }

    private boolean active(ManagerReportReviewSessionRepository.Timing current) {
        return current != null && current.getCompletedAt() == null && !"COMPLETED".equals(current.getStatus());
    }

    private void synchronizeTiming(ManagerReportReviewSession review, ManagerReportReviewSessionRepository.Timing current) {
        // Only timing is mirrored for the caller. Never merge answers/status from a stale snapshot.
        review.setDeadlineStartedAt(current.getDeadlineStartedAt());
        review.setAiUnavailableStartedAt(current.getAiUnavailableStartedAt());
        review.setAiUnavailableSeconds(current.getAiUnavailableSeconds());
    }

    private void event(
            ManagerReportReviewSession review,
            String type,
            String source,
            String payload
    ) {
        ManagerReportReviewEvent event = new ManagerReportReviewEvent();
        event.setReview(review);
        event.setEventType(type);
        event.setActorRole("SYSTEM");
        event.setSource(limit(source, 32));
        event.setPayloadText(limit(payload, 2000));
        eventRepository.save(event);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
