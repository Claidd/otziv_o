package com.hunt.otziv.p_products.application;

import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.service.OrderService;
import com.hunt.otziv.p_products.service.OrderDetailsService;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.service.ReviewService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_activity.service.WorkerActivityService;
import com.hunt.otziv.worker_activity.model.WorkerActivityAction;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hunt.otziv.p_products.application.WorkerOrderCommandException.Kind.*;

/** Owns the entire worker order mutation, including waiting/publication state. */
@Service
@RequiredArgsConstructor
public class WorkerOrderCommands {
    private static final Set<String> WAITING_STATUSES = Set.of("Новый", "Коррекция");
    private final OrderService orders;
    private final WorkerAssignmentMutationGuardService assignment;
    private final ScheduledClientMessageService reminders;
    private final CompanyService companies;
    private final WorkerActivityService activity;
    private final com.hunt.otziv.p_products.api.OrderStatusCommands statusCommands;

    public void changeStatus(Long id,String status,WorkerOrderActor actor) throws Exception {
        statusCommands.changeStatus(id,null,status,actor.authentication(),com.hunt.otziv.p_products.api.OrderStatusCommands.EntryPoint.WORKER_BOARD);
    }

    @Transactional
    public void changeClientWaiting(Long id, boolean waiting, WorkerOrderActor actor) {
        actor.require("ADMIN", "OWNER", "MANAGER");
        assignment.assertOrder(id, actor.authentication());
        Order order = orders.getOrder(id);
        String status = order.getStatus() == null ? "" : order.getStatus().getTitle();
        if (waiting && !WAITING_STATUSES.contains(status)) throw new WorkerOrderCommandException(CONFLICT,
                "Ожидание клиента доступно только для статусов \"Новый\" и \"Коррекция\"");
        if (order.isWaitingForClient() != waiting) {
            LocalDateTime now = LocalDateTime.now();
            order.setWaitingForClientChangedAt(waiting ? now : null);
            if (!waiting) order.setStatusChangedAt(now);
        }
        order.setWaitingForClient(waiting);
        if (waiting) order.setClientTextExpected(true);
        orders.save(order);
        reminders.synchronizeClientTextReminderForOrder(order);
    }

    @Transactional
    public void changeOrderNote(Long id, String text, WorkerOrderActor actor) {
        actor.require("ADMIN", "OWNER", "MANAGER", "WORKER");
        assignment.assertOrder(id, actor.authentication());
        Order order = orders.getOrder(id);
        if (java.util.Objects.equals(order.getZametka(),text)) return;
        order.setZametka(text);
        orders.save(order);
        activity.recordTransactional(actor.authentication(),WorkerActivityAction.ORDER_NOTE_UPDATE,"order",id,id,null,"order_note",null);
    }

    @Transactional
    public void changeCompanyNote(Long id, String text, WorkerOrderActor actor) {
        actor.require("ADMIN", "OWNER", "MANAGER", "WORKER");
        assignment.assertOrder(id, actor.authentication());
        var company = orders.getOrder(id).getCompany();
        if (company == null) throw new WorkerOrderCommandException(NOT_FOUND, "Компания заказа не найдена");
        if (java.util.Objects.equals(company.getCommentsCompany(),text)) return;
        company.setCommentsCompany(text);
        companies.save(company);
        activity.recordTransactional(actor.authentication(),WorkerActivityAction.COMPANY_NOTE_UPDATE,"company",company.getId(),id,null,"company_note",null);
    }
}
