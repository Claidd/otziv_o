package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItem;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** «Заказы у отключённых специалистов»: a work order nobody would do becomes a card pointing at the order editor. */
class ManagerControlInactiveWorkerOrdersTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private ManagerControlProblemExamples examples;
    private Manager manager;

    @BeforeEach
    void setUp() {
        examples = new ManagerControlProblemExamples(mock(ManagerControlClientMessageText.class),
                mock(ManagerControlConcretePresenter.class), mock(ManagerControlOrderAutomationDiagnostics.class),
                mock(com.hunt.otziv.p_products.service.OrderService.class),
                mock(com.hunt.otziv.client_messages.service.ClientMessageOrderStatusService.class),
                mock(com.hunt.otziv.config.settings.service.AppSettingService.class),
                mock(com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService.class),
                mock(com.hunt.otziv.bad_reviews.service.BadReviewTaskService.class),
                mock(com.hunt.otziv.review_recovery.service.ReviewRecoveryTaskService.class),
                mock(com.hunt.otziv.r_review.repository.ReviewRepository.class),
                orderRepository, mock(com.hunt.otziv.c_companies.repository.CompanyRepository.class),
                mock(com.hunt.otziv.payments.repository.PaymentLinkRepository.class), mock(ManagerControlInvoiceDiagnostics.class),
                mock(ManagerAutomationFailureService.class),
                mock(com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository.class),
                mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlRepository.class),
                mock(com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository.class),
                mock(com.hunt.otziv.whatsapp.api.WhatsAppInviteLinkHealth.class),
                scenario -> null);
        manager = new Manager();
        manager.setId(3L);
    }

    @Test
    void everyWorkOrderOfADeactivatedOrMissingSpecialistBecomesACardWithTheFix() {
        Order deactivated = order(19358L, "Публикация", worker("Уволенный специалист"));
        Order withoutWorker = order(19400L, "Новый", null);
        when(orderRepository.findWorkOrdersOnInactiveWorkerByManager(eq(manager),
                eq(ManagerControlProblemExamples.INACTIVE_WORKER_ORDER_STATUSES), any(Pageable.class)))
                .thenReturn(List.of(deactivated, withoutWorker));
        ManagerDailyControlItem item = new ManagerDailyControlItem();
        item.setReasonCode("INACTIVE_WORKER_ORDERS");

        var cards = examples.inactiveWorkerOrderExamples(manager, LocalDate.of(2026, 11, 22), 10);

        assertThat(cards).hasSize(2);
        assertThat(cards.getFirst().type()).isEqualTo(ManagerControlProblemExamples.ENTITY_INACTIVE_WORKER_ORDER);
        assertThat(cards.getFirst().entityId()).isEqualTo(19358L);
        assertThat(cards.getFirst().reason())
                .contains("закреплён за отключённым специалистом (Уволенный специалист)")
                .contains("смените специалиста в карточке");
        assertThat(cards.get(1).reason()).contains("без специалиста");
    }

    @Test
    void theWorkStatusesAreThoseWhereTheOrderWaitsForItsSpecialist() {
        assertThat(ManagerControlProblemExamples.INACTIVE_WORKER_ORDER_STATUSES)
                .containsExactlyInAnyOrder("Новый", "В проверку", "На проверке", "Коррекция", "Публикация");
    }

    private Order order(Long id, String statusTitle, Worker worker) {
        Order order = new Order();
        order.setId(id);
        OrderStatus status = new OrderStatus();
        status.setTitle(statusTitle);
        order.setStatus(status);
        Company company = new Company();
        company.setId(77L);
        company.setTitle("Компания");
        order.setCompany(company);
        order.setWorker(worker);
        order.setStatusChangedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        return order;
    }

    private Worker worker(String fio) {
        User user = new User();
        user.setFio(fio);
        user.setActive(false);
        Worker worker = new Worker();
        worker.setId(2L);
        worker.setUser(user);
        return worker;
    }
}
