package com.hunt.otziv.performers.service;

import com.hunt.otziv.performers.model.PerformerAssignmentStatus;
import com.hunt.otziv.performers.model.PerformerOfferStatus;
import com.hunt.otziv.performers.model.ReviewPerformerAssignment;
import com.hunt.otziv.performers.model.ReviewPerformerOffer;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository;
import com.hunt.otziv.performers.repository.PerformerNotificationRepository.Intent;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PerformerNotificationService {
    private final PerformerNotificationRepository repository;
    private final PerformerMutationLockService locks;
    private final PerformerTelegramNotificationService telegram;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void offer(ReviewPerformerOffer offer) {
        repository.enqueue(offer.getAssignment().getId(), offer.getId(), "OFFER", 0, repository.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accepted(ReviewPerformerAssignment assignment) {
        repository.enqueue(assignment.getId(), null, "ACCEPTED", 0, repository.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean ready(ReviewPerformerAssignment assignment) {
        // save() alone may leave the new generation only in the persistence context.
        // Commit intent + exact database-generation marker together, then allow later
        // assignment dirty checking without a writable JPA mapping for the marker.
        entityManager.flush();
        return repository.enqueue(assignment.getId(), null, "READY", assignment.getPublicationGeneration(),
                assignment.getPublishAvailableAt() == null ? repository.now() : assignment.getPublishAvailableAt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcileReady(Long assignmentId) {
        ReviewPerformerAssignment assignment = locks.assignment(assignmentId);
        return assignment.getStatus() == PerformerAssignmentStatus.WAITING_PUBLICATION
                && assignment.getPublicationGeneration() > 0 && ready(assignment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Intent> claim() { return repository.claim(120); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireClaims() { return repository.expireClaims(100); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PerformerTelegramNotificationService.Message> prepare(Intent intent) {
        ReviewPerformerAssignment assignment = locks.assignment(intent.assignmentId());
        if (!repository.owns(intent)) return Optional.empty();
        ReviewPerformerOffer offer = intent.offerId() == null ? null : locks.offer(intent.offerId());
        if (!eligible(intent, assignment, offer)) {
            repository.finish(intent, "CANCELLED", null, "business_state_changed");
            return Optional.empty();
        }
        PerformerTelegramNotificationService.Message message = telegram.prepare(intent.type(), assignment, offer);
        if (message.chatId() == null) {
            repository.finish(intent, "BLOCKED", null, "missing_chat");
            return Optional.empty();
        }
        if (offer != null) offer.setTelegramChatId(message.chatId());
        return Optional.of(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(Intent intent, Integer messageId) {
        // Same lock order as user mutations, then the fenced intent update.
        locks.assignment(intent.assignmentId());
        ReviewPerformerOffer offer = intent.offerId() == null ? null : locks.offer(intent.offerId());
        boolean committed = repository.finish(intent, messageId == null ? "UNKNOWN" : "SENT", messageId,
                messageId == null ? "transport_outcome_unknown" : "telegram_acknowledged");
        if (committed && offer != null) {
            if (messageId == null) offer.setDeliveryState("UNKNOWN");
            else confirmOffer(offer, messageId, repository.now());
        }
        return committed;
    }

    @Transactional(readOnly = true)
    public List<Intent> unresolved(int limit) { return repository.unresolved(Math.max(1, Math.min(limit, 100))); }

    @Transactional
    public void resolve(Long id, String action, Integer messageId, String reason, String actor) {
        if (reason == null || reason.isBlank() || reason.length() > 1000 || actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужны ответственный и причина решения");
        }
        Intent intent = repository.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ReviewPerformerAssignment assignment = locks.assignment(intent.assignmentId());
        ReviewPerformerOffer offer = intent.offerId() == null ? null : locks.offer(intent.offerId());
        if (!List.of("UNKNOWN", "BLOCKED", "LEGACY_UNKNOWN").contains(intent.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исход уже обработан или отправка ещё выполняется");
        }
        String target = switch (action == null ? "" : action) {
            case "CONFIRM_SENT" -> "SENT";
            // Operator explicitly checked that the previous attempt did NOT send.
            case "CONFIRM_NOT_SENT_REQUEUE" -> "PENDING";
            case "CANCEL" -> "CANCELLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестное решение");
        };
        if (target.equals("SENT") && (messageId == null || messageId <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужен подтверждённый Telegram messageId");
        }
        if (target.equals("PENDING") && !eligible(intent, assignment, offer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задание больше не требует это уведомление");
        }
        if (target.equals("PENDING") && "READY".equals(intent.type())
                && assignment.getPublishAvailableAt() != null
                && assignment.getPublishAvailableAt().isAfter(repository.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Время публикации ещё не наступило");
        }
        if (!repository.resolve(intent, target, messageId, actor, reason.trim(), action)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Исход изменился; обновите данные");
        }
        if (offer != null) {
            if (target.equals("SENT")) confirmOffer(offer, messageId, repository.now());
            if (target.equals("PENDING")) offer.setDeliveryState("PENDING");
            if (target.equals("CANCELLED") && offer.getStatus() == PerformerOfferStatus.OFFERED) {
                offer.setStatus(PerformerOfferStatus.SKIPPED);
                offer.setRespondedAt(repository.now());
                if (assignment.getStatus() == PerformerAssignmentStatus.OFFERING) assignment.setStatus(PerformerAssignmentStatus.CREATED);
            }
        }
    }

    static boolean eligible(Intent intent, ReviewPerformerAssignment assignment, ReviewPerformerOffer offer) {
        return switch (intent.type()) {
            case "OFFER" -> offer != null && offer.getStatus() == PerformerOfferStatus.OFFERED
                    && assignment.getStatus() == PerformerAssignmentStatus.OFFERING;
            case "READY" -> assignment.getStatus() == PerformerAssignmentStatus.WAITING_PUBLICATION
                    && assignment.getPublicationGeneration() == intent.generation();
            case "ACCEPTED" -> List.of(PerformerAssignmentStatus.ACCEPTED, PerformerAssignmentStatus.WAITING_PUBLICATION)
                    .contains(assignment.getStatus());
            default -> false;
        };
    }

    static void confirmOffer(ReviewPerformerOffer offer, Integer messageId, LocalDateTime now) {
        offer.setTelegramMessageId(messageId);
        offer.setDeliveryState("DELIVERED");
        if (offer.getDeliveredAt() == null) {
            offer.setDeliveredAt(now);
            offer.setExpiresAt(now.plusMinutes(Math.max(1, Math.min(offer.getResponseTtlMinutes(), 1440))));
        }
    }
}
