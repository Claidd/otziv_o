package com.hunt.otziv.p_products.application;

import com.hunt.otziv.b_bots.service.BotService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.p_products.worker_access.service.WorkerCellularAccessService;
import com.hunt.otziv.p_products.worker_flow.service.WorkerPublicationGateService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hunt.otziv.p_products.application.WorkerOrderCommandException.Kind.*;

/** Owns worker account mutations and their successful-command audit. */
@Service
@RequiredArgsConstructor
public class WorkerReviewAccountCommands {
    private final ReviewService reviews;
    private final BotService bots;
    private final WorkerAssignmentMutationGuardService assignment;
    private final WorkerCellularAccessService cellular;
    private final WorkerPublicationGateService publication;
    private final WorkerActivityService activity;

    @Transactional(rollbackFor = Exception.class)
    public Change change(long reviewId, Source source, WorkerOrderActor actor) {
        source = source == null ? new Source(null,null,null) : source;
        Review review = authorize(reviewId,source,actor);
        Long previous = botId(review);
        reviews.changeBot(reviewId);
        Long next = botId(reviews.getReviewById(reviewId));
        audit(actor,review,WorkerActivityAction.REVIEW_BOT_CHANGE,"review",
                source.append("oldBotId="+value(previous)+";newBotId="+value(next)+";botId="+value(next)+";"));
        publicationActivity(source,actor);
        return new Change(previous,next);
    }

    @Transactional(rollbackFor = Exception.class)
    public Deactivated deactivate(long reviewId, long botId, Source source, WorkerOrderActor actor) {
        source = source == null ? new Source(null,null,null) : source;
        Review review = authorize(reviewId,source,actor);
        reviews.deActivateAndChangeBot(reviewId,botId);
        audit(actor,review,WorkerActivityAction.REVIEW_BOT_DEACTIVATE,"review",source.append("botId="+botId+";"));
        publicationActivity(source,actor);
        Long next = botId(reviews.getReviewById(reviewId));
        return new Deactivated(botId,next,next != null && next > 1);
    }

    @Transactional
    public void rename(long reviewId,String requestedName,WorkerOrderActor actor) {
        actor.require("ADMIN","OWNER","MANAGER","WORKER");
        cellular.enforceProtectedAccess("nagul",actor.authentication());
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) throw new WorkerOrderCommandException(BAD_REQUEST,"Имя аккаунта не указано");
        if (name.length()>255) throw new WorkerOrderCommandException(BAD_REQUEST,"Имя аккаунта слишком длинное");
        assignment.assertReview(reviewId,actor.authentication());
        Review review = reviews.getReviewById(reviewId);
        var bot = review == null ? null : review.getBot();
        if (bot == null) throw new WorkerOrderCommandException(CONFLICT,"У отзыва нет назначенного аккаунта");
        if (Objects.equals(bot.getFio(),name)) return;
        bot.setFio(name); bots.save(bot);
        audit(actor,review,WorkerActivityAction.REVIEW_BOT_NAME_UPDATE,"nagul","botId="+value(bot.getId())+";");
    }

    @Transactional
    public void delete(long botId,WorkerOrderActor actor) {
        actor.require("ADMIN","OWNER");
        bots.deleteBot(botId,actor.authentication());
    }

    private Review authorize(long id,Source source,WorkerOrderActor actor) {
        actor.require("ADMIN","OWNER","MANAGER","WORKER");
        assignment.assertReview(id,actor.authentication());
        Review review = reviews.getReviewById(id);
        if (review == null) throw new WorkerOrderCommandException(NOT_FOUND,"Отзыв не найден");
        String status = review.getOrderDetails()==null || review.getOrderDetails().getOrder()==null
                || review.getOrderDetails().getOrder().getStatus()==null ? ""
                : review.getOrderDetails().getOrder().getStatus().getTitle();
        status = status == null ? "" : status.trim();
        String section = "Новый".equalsIgnoreCase(status) ? "new" : "Коррекция".equalsIgnoreCase(status) ? "correct"
                : WorkerCellularAccessService.PROTECTED_SECTIONS.contains(source.normalizedSection()) ? source.normalizedSection()
                : review.isVigul() ? "publish" : "nagul";
        cellular.enforceSection(section,actor.authentication());
        if ("publish".equals(source.normalizedSection())) {
            publication.blockForPublication(actor.authentication(),actor.authentication()).ifPresent(block -> {
                throw new WorkerOrderCommandException(CONFLICT,block.message());
            });
        }
        return review;
    }

    private void publicationActivity(Source source,WorkerOrderActor actor) {
        if ("publish".equals(source.normalizedSection())) publication.recordPublicationActivity(actor.authentication(),actor.authentication());
    }
    private void audit(WorkerOrderActor actor,Review review,WorkerActivityAction action,String section,String details) {
        Long orderId = review.getOrderDetails()==null || review.getOrderDetails().getOrder()==null
                ? null : review.getOrderDetails().getOrder().getId();
        activity.recordTransactional(actor.authentication(),action,"review",review.getId(),orderId,review.getId(),section,details);
    }
    private Long botId(Review review) { return review==null || review.getBot()==null ? null : review.getBot().getId(); }
    private static String value(Long id) { return id == null ? "-" : id.toString(); }

    public record Change(Long oldBotId,Long newBotId) {}
    public record Deactivated(Long blockedBotId,Long newBotId,boolean replacementFound) {}
    public record Source(String page,String entry,String section) {
        public String normalizedSection() { return section==null ? "" : section.trim().toLowerCase(Locale.ROOT); }
        String append(String detail) {
            StringBuilder result = new StringBuilder(detail);
            append(result,"sourcePage",page); append(result,"sourceEntry",entry); append(result,"sourceSection",section);
            return result.toString();
        }
        private void append(StringBuilder result,String key,String value) {
            if(value!=null && !value.isBlank()) result.append(key).append('=').append(value.trim()).append(';');
        }
    }
}
