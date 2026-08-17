package com.hunt.otziv.mobile_push.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobilePushBusinessNotificationServiceTest {

    private MobilePushSenderService sender;
    private MobilePushBusinessNotificationService service;

    @BeforeEach
    void setUp() {
        sender = mock(MobilePushSenderService.class);
        service = new MobilePushBusinessNotificationService(sender, mock(UserRepository.class));
    }

    @Test
    void correctionPushUsesOnlyProvidedClientNoteAndOpensExactOrder() {
        User user = User.builder().id(41L).build();
        Order order = workerOrder(7L, 17L, user, "Компания", "Филиал");

        service.notifyWorkerCorrection(
                order,
                "  Общее замечание клиента\nОтзыв #701: исправьте название  "
        );

        verify(sender).sendToUser(
                user,
                "Заказ на коррекции",
                "Компания - Филиал. Замечания клиента: Общее замечание клиента Отзыв #701: исправьте название",
                "/tabs/orders/17/7"
        );
    }

    @Test
    void archiveApprovalPushOpensWorkerPublicationSection() {
        User user = User.builder().id(42L).build();
        Order order = workerOrder(8L, 18L, user, "Компания", "Филиал");

        service.notifyWorkerArchiveReadyForPublication(order);

        verify(sender).sendToUser(
                user,
                "Заказ из архива готов к публикации",
                "Компания - Филиал: клиент одобрил отзывы, можно публиковать.",
                "/tabs/worker?section=publish"
        );
    }

    private Order workerOrder(
            Long orderId,
            Long companyId,
            User user,
            String companyTitle,
            String filialTitle
    ) {
        Worker worker = mock(Worker.class);
        Company company = mock(Company.class);
        Filial filial = mock(Filial.class);
        Order order = mock(Order.class);

        when(worker.getUser()).thenReturn(user);
        when(company.getId()).thenReturn(companyId);
        when(company.getTitle()).thenReturn(companyTitle);
        when(filial.getTitle()).thenReturn(filialTitle);
        when(order.getId()).thenReturn(orderId);
        when(order.getWorker()).thenReturn(worker);
        when(order.getCompany()).thenReturn(company);
        when(order.getFilial()).thenReturn(filial);
        return order;
    }
}
