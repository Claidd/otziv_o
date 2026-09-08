package com.hunt.otziv.p_products.application;

import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Worker application commands for ReviewContent. Existing domain transactions and best-effort audit ordering are preserved. */
@Service
@RequiredArgsConstructor
public class WorkerReviewContentCommands {

    private final ReviewService reviewService;
    private final WorkerActivityService workerActivityService;
    private final WorkerReviewAccessPolicy reviewAccess;

    public void updateReviewText(Long reviewId, ReviewTextUpdateRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        reviewAccess.enforceReviewSourceAccess(reviewId, request == null ? null : request.sourceSection(), authentication);
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Текст отзыва не указан");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewText(orderId, reviewId, request.text(), authentication)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
        workerActivityService.recordSafely(authentication,
                WorkerActivityAction.REVIEW_TEXT_UPDATE,
                "review",
                reviewId,
                orderId,
                reviewId,
                "review_text",
                null
        );
    }

    public void updateReviewAnswer(Long reviewId, ReviewAnswerUpdateRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        reviewAccess.enforceReviewSourceAccess(reviewId, request == null ? null : request.sourceSection(), authentication);
        if (request == null || request.answer() == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Ответ на отзыв не указан");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewAnswer(orderId, reviewId, request.answer(), authentication)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
        workerActivityService.recordSafely(authentication,
                WorkerActivityAction.REVIEW_ANSWER_UPDATE,
                "review",
                reviewId,
                orderId,
                reviewId,
                "review_answer",
                null
        );
    }

    public void updateReviewNote(Long reviewId, ReviewNoteUpdateRequest request, WorkerOrderActor actor) {
        Authentication authentication = requireActor(actor, "ADMIN", "OWNER", "MANAGER", "WORKER");
        reviewAccess.enforceReviewSourceAccess(reviewId, request == null ? null : request.sourceSection(), authentication);
        if (request == null || request.comment() == null) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Заметка отзыва не указана");
        }

        Long orderId = requireReviewOrderId(request.orderId());
        if (!reviewService.updateReviewNote(orderId, reviewId, request.comment(), authentication)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.NOT_FOUND, "Отзыв не найден в этом заказе");
        }
        workerActivityService.recordSafely(authentication,
                WorkerActivityAction.REVIEW_NOTE_UPDATE,
                "review",
                reviewId,
                orderId,
                reviewId,
                "review_note",
                null
        );
    }

    private Long requireReviewOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.BAD_REQUEST, "Заказ отзыва не указан");
        }

        return orderId;
    }

    public record ReviewTextUpdateRequest(Long orderId, String text, String sourcePage, String sourceEntry, String sourceSection) {}

    public record ReviewAnswerUpdateRequest(Long orderId, String answer, String sourcePage, String sourceEntry, String sourceSection) {}

    public record ReviewNoteUpdateRequest(Long orderId, String comment, String sourcePage, String sourceEntry, String sourceSection) {}
}
