package com.hunt.otziv.p_products.application;

import com.hunt.otziv.p_products.api.OrderStatusCommands;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hunt.otziv.p_products.application.WorkerOrderCommandException.Kind.*;

@Service
@RequiredArgsConstructor
public class OrderStatusCommandService implements OrderStatusCommands {
    private final OrderService orders;
    private final OrderDetailsService details;
    private final ReviewService reviews;
    private final WorkerAssignmentMutationGuardService assignment;
    private final ScheduledClientMessageService reminders;
    private final UserService users;
    private final WorkerService workers;

    @Override @Transactional(rollbackFor=Exception.class)
    public boolean changeStatus(Long id,Long companyId,String requested,Authentication authentication,EntryPoint entry) throws Exception {
        if(entry==null)throw new WorkerOrderCommandException(BAD_REQUEST,"Источник операции не указан");
        var actor=WorkerOrderActor.from(authentication);
        switch(entry) {
            case WORKER_BOARD -> actor.require("ADMIN","OWNER","WORKER");
            case LEGACY_STAFF -> actor.require("ADMIN","OWNER","MANAGER");
            default -> actor.require("ADMIN","OWNER","MANAGER","WORKER");
        }
        String status=requested==null?"":requested.trim();
        if(status.isBlank())throw new WorkerOrderCommandException(BAD_REQUEST,"Статус не указан");
        assignment.assertOrder(id,authentication); // current ownership after the canonical Order lock
        Order order=orders.getOrder(id);
        if(companyId!=null&&(order==null||order.getCompany()==null||!Objects.equals(order.getCompany().getId(),companyId)))
            throw new WorkerOrderCommandException(NOT_FOUND,"Заказ не найден");
        boolean workerOnly=actor.has("WORKER")&&!actor.has("ADMIN")&&!actor.has("OWNER")&&!actor.has("MANAGER");
        if(entry==EntryPoint.WORKER_BOARD&&!actor.has("ADMIN")&&!actor.has("OWNER")) {
            var user=users.findByUserName(actor.username()).orElseThrow(()->new WorkerOrderCommandException(NOT_FOUND,"Пользователь не найден"));
            var worker=workers.getWorkerByUserId(user.getId());
            if(worker==null)throw new WorkerOrderCommandException(NOT_FOUND,"Специалист не найден");
            boolean owned=order!=null&&order.getWorker()!=null&&worker.getId()!=null&&worker.getId().equals(order.getWorker().getId());
            if(!owned||!order.isWaitingForClient()||!"В проверку".equals(status))
                throw new WorkerOrderCommandException(FORBIDDEN,"Специалист может отправить на проверку только свой заказ, ожидающий клиента");
        } else if(workerOnly&&!"В проверку".equals(status)) {
            throw new WorkerOrderCommandException(FORBIDDEN,"Специалист может отправить заказ только на проверку");
        }
        if(entry!=EntryPoint.MANAGER_BOARD&&Set.of("Опубликовано","Оплачено").contains(status)&&order.getAmount()>order.getCounter()) {
            if(entry==EntryPoint.LEGACY_STAFF)return false; // legacy forms keep their redirect on an incomplete counter
            throw new WorkerOrderCommandException(CONFLICT,"Нельзя перевести заказ в статус \""+status+"\": опубликовано "+order.getCounter()+" из "+order.getAmount()+" отзывов");
        }
        boolean privilegedBan=entry==EntryPoint.MANAGER_BOARD&&"Бан".equals(status)&&(actor.has("ADMIN")||actor.has("OWNER"));
        boolean updated=privilegedBan?orders.changeStatusForPrivilegedOrder(id,status,authentication):orders.changeStatusForOrder(id,status,authentication);
        if(!updated) {
            if(entry==EntryPoint.LEGACY_STAFF||entry==EntryPoint.LEGACY_WORKER_SUBMISSION)return false;
            throw new WorkerOrderCommandException(BAD_REQUEST,"Статус заказа не изменен");
        }
        if(entry==EntryPoint.WORKER_BOARD&&!Set.of("Новый","Коррекция").contains(status)) {
            Order changed=orders.getOrder(id);
            if(changed.isWaitingForClient()) {
                changed.setWaitingForClient(false);changed.setWaitingForClientChangedAt(null);orders.save(changed);
                reminders.synchronizeClientTextReminderForOrder(changed);
            }
        }
        if("Публикация".equals(status)&&order.getDetails()!=null&&!order.getDetails().isEmpty()) {
            boolean datesUpdated=reviews.updateOrderDetailAndReviewAndPublishDate(details.getOrderDetailDTOById(order.getDetails().getFirst().getId()));
            if(!datesUpdated&&entry==EntryPoint.WORKER_BOARD)throw new WorkerOrderCommandException(CONFLICT,"Не удалось обновить даты публикации заказа");
        }
        return true;
    }
}
