package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewEvent;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewEventRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewSessionRepository;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewCheckInService {

    private final ManagerReportReviewSessionRepository sessionRepository;
    private final ManagerReportReviewEventRepository eventRepository;
    private final ManagerReportReviewAccessPolicy accessPolicy;

    @Transactional
    public ManagerReportReviewAccessPolicy.AccessState checkIn(User user) {
        if (user == null || user.getId() == null) {
            return ManagerReportReviewAccessPolicy.AccessState.allowed();
        }
        Long reviewId = sessionRepository.findLatestIncompleteId(user.getId()).orElse(null);
        if (reviewId != null && sessionRepository.startDeadlineIfAbsent(reviewId, LocalDateTime.now()) == 1) {
            // A reference is sufficient for the event; no stale answer snapshot is loaded or saved.
            ManagerReportReviewSession review = sessionRepository.getReferenceById(reviewId);
            ManagerReportReviewEvent event = new ManagerReportReviewEvent();
            event.setReview(review);
            event.setEventType("DEADLINE_STARTED");
            event.setActorUserId(user.getId());
            event.setActorRole("MANAGER");
            event.setSource("personal-cabinet");
            event.setPayloadText("Трёхчасовой срок начат при входе в личный кабинет");
            eventRepository.save(event);
        }
        accessPolicy.invalidate(user.getId());
        return accessPolicy.state(user);
    }
}
