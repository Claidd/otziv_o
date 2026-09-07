package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hunt.otziv.p_products.application.WorkerOrderCommandException.Kind.*;
import static com.hunt.otziv.p_products.application.WorkerMutationDetails.*;

/** Locked publication, counter/date changes and their audit share one transaction for every human entry. */
@Service
@RequiredArgsConstructor
public class ReviewPublicationMutationService {
    private final OrderService orders;
    private final ReviewService reviews;
    private final WorkerAssignmentMutationGuardService assignment;
    private final WorkerActivityService activity;

    @Transactional(rollbackFor=Exception.class)
    public void publish(Long reviewId,Long expectedOrderId,Long expectedCompanyId,Authentication actor,String sourceDetails,boolean recordActivity) throws Exception {
        WorkerOrderActor.from(actor).require("ADMIN","OWNER","MANAGER","WORKER");
        long orderId=assignment.requireReviewOrder(reviewId,actor);
        if(expectedOrderId!=null&&!Objects.equals(expectedOrderId,orderId))throw new WorkerOrderCommandException(NOT_FOUND,"Отзыв не найден");
        if(expectedCompanyId!=null) {
            var order=orders.getOrder(orderId);
            if(order.getCompany()==null||!Objects.equals(expectedCompanyId,order.getCompany().getId()))
                throw new WorkerOrderCommandException(NOT_FOUND,"Заказ не найден");
        }
        var review=reviews.getReviewById(reviewId);
        if(!orders.changeStatusAndOrderCounter(reviewId,actor))throw new WorkerOrderCommandException(BAD_REQUEST,"Отзыв не отмечен опубликованным");
        if(recordActivity)activity.recordSafely(actor,WorkerActivityAction.REVIEW_PUBLISH,"review",reviewId,orderId,reviewId,
                "publish",botDetails(review==null?null:review.getBot())+(sourceDetails==null?"":sourceDetails));
    }
}
