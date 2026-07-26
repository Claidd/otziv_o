package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewAiAvailabilityService {

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewAccessPolicy accessPolicy;

    public boolean pause(
            ManagerReportReviewSession review,
            LocalDateTime now,
            String source,
            String reason
    ) {
        if (review == null || review.getId() == null || review.getAiUnavailableStartedAt() != null) {
            return false;
        }
        review.setAiUnavailableStartedAt(now);
        sessionRepository.save(review);
        event(review, "AI_VERIFICATION_PAUSED", source,
                "Срок проверки приостановлен: " + clean(reason));
        accessPolicy.invalidate(review.getManagerUserId());
        return true;
    }

    public boolean resume(
            ManagerReportReviewSession review,
            LocalDateTime now,
            String source
    ) {
        if (review == null || review.getId() == null || review.getAiUnavailableStartedAt() == null) {
            return false;
        }
        long seconds = Math.max(
                0,
                Duration.between(review.getAiUnavailableStartedAt(), now).toSeconds()
        );
        review.setAiUnavailableSeconds(Math.max(0, review.getAiUnavailableSeconds()) + seconds);
        if (review.getDeadlineStartedAt() != null) {
            review.setDeadlineStartedAt(review.getDeadlineStartedAt().plusSeconds(seconds));
        }
        review.setAiUnavailableStartedAt(null);
        sessionRepository.save(review);
        event(review, "AI_VERIFICATION_RESUMED", source,
                "Автоматическая проверка восстановлена; срок продлён на " + seconds + " сек.");
        accessPolicy.invalidate(review.getManagerUserId());
        return true;
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
