package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusTransitionServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private OrderTransactionService orderTransactionService;

    @Mock
    private BadReviewTaskService badReviewTaskService;

    @Mock
    private PromoTextService textService;

    @Mock
    private TelegramService telegramService;

    @Mock
    private OrderCompanyStatusService orderCompanyStatusService;

    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;

    @Mock
    private OrderBotLifecycleService orderBotLifecycleService;

    @Test
    void paymentStatusDelegatesToTransactionService() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(1L, "Новый");

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        assertTrue(service.changeStatusForOrder(1L, "Оплачено"));

        verify(orderTransactionService).handlePaymentStatus(order);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void defaultStatusSetsStatusAndSavesOrder() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(2L, "Новый");
        OrderStatus reminder = status("Напоминание");

        when(orderRepository.findById(2L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Напоминание")).thenReturn(reminder);

        assertTrue(service.changeStatusForOrder(2L, "Напоминание"));

        assertSame(reminder, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void notPaidStatusSavesOrderBeforeCreatingBadReviewTasks() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(3L, "Публикация");
        OrderStatus notPaid = status("Не оплачено");

        when(orderRepository.findById(3L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Не оплачено")).thenReturn(notPaid);

        assertTrue(service.changeStatusForOrder(3L, "Не оплачено"));

        assertSame(notPaid, order.getStatus());
        InOrder inOrder = inOrder(orderRepository, badReviewTaskService);
        inOrder.verify(orderRepository).save(order);
        inOrder.verify(badReviewTaskService).createTasksForUnpaidOrder(order);
    }

    @Test
    void archiveStatusUpdatesCompanyDetachesBotsAndSaves() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(4L, "Публикация");
        OrderStatus archive = status("Архив");

        when(orderRepository.findById(4L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Архив")).thenReturn(archive);

        assertTrue(service.changeStatusForOrder(4L, "Архив"));

        assertSame(archive, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Архив");
        verify(orderBotLifecycleService).detachBots(order);
        verify(orderRepository).save(order);
    }

    @Test
    void toCheckWithoutGroupSetsStatusWithoutSendingWhatsApp() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(5L, "Новый", "Компания", " ");
        OrderStatus toCheck = status("В проверку");

        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(textService.findById(5)).thenReturn("Проверьте отзывы");
        when(orderStatusService.getOrderStatusByTitle("В проверку")).thenReturn(toCheck);

        assertTrue(service.changeStatusForOrder(5L, "В проверку"));

        assertSame(toCheck, order.getStatus());
        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "В проверку");
        verify(orderStatusNotificationService, never()).sendMessageToGroup(
                eq("В проверку"),
                same(order),
                eq("client"),
                eq(" "),
                contains("Проверьте отзывы"),
                eq("На проверке")
        );
        verify(orderRepository).save(order);
    }

    @Test
    void publicStatusWithGroupSendsPaymentMessageToGroup() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(6L, "Публикация", "Компания", "group");
        order.setSum(BigDecimal.valueOf(1500));

        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(orderStatusNotificationService.sendMessageToGroup(
                eq("Опубликовано"),
                same(order),
                eq("client"),
                eq("group"),
                contains("К оплате: 1500"),
                eq("Выставлен счет")
        )).thenReturn(true);

        assertTrue(service.changeStatusForOrder(6L, "Опубликовано"));

        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Опубликовано");
        verify(orderRepository, never()).save(order);
    }

    @Test
    void correctionStatusNotifiesWorkerWhenTelegramIsAvailable() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithWorker(7L, "Публикация", 700L);
        order.setZametka("заметка");
        order.getCompany().setCommentsCompany("комментарий");
        OrderStatus correction = status("Коррекция");

        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderStatusNotificationService.hasWorkerWithTelegram(order)).thenReturn(true);
        when(orderStatusService.getOrderStatusByTitle("Коррекция")).thenReturn(correction);

        assertTrue(service.changeStatusForOrder(7L, "Коррекция"));

        assertSame(correction, order.getStatus());
        verify(telegramService).sendMessage(
                700L,
                "Компания отправлен в Коррекцию - заметка комментарий\n https://o-ogo.ru/worker/correct"
        );
        verify(orderRepository).save(order);
    }

    @Test
    void toPublishFromArchiveNotifiesWorkerAndChecksStubBots() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithWorker(8L, "Архив", 800L);
        Review review = new Review();
        review.setId(80L);
        order.getDetails().getFirst().setReviews(List.of(review));
        OrderStatus toPublish = status("Публикация");

        when(orderRepository.findById(8L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Публикация")).thenReturn(toPublish);
        when(orderStatusNotificationService.hasWorkerWithTelegram(order)).thenReturn(true);

        assertTrue(service.changeStatusForOrder(8L, "Публикация"));

        assertSame(toPublish, order.getStatus());
        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderBotLifecycleService).checkAndNotifyAboutStubBots(
                argThat(reviews -> reviews.size() == 1 && reviews.contains(review))
        );
        verify(telegramService).sendMessage(
                800L,
                "Компания. Новый заказ из Архива. \n https://o-ogo.ru/worker/new_orders"
        );
        verify(orderRepository).save(order);
    }

    private OrderStatusTransitionService service() {
        return new OrderStatusTransitionService(
                orderRepository,
                orderStatusService,
                orderTransactionService,
                badReviewTaskService,
                textService,
                telegramService,
                orderCompanyStatusService,
                orderStatusNotificationService,
                orderBotLifecycleService
        );
    }

    private Order order(Long id, String statusTitle) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status(statusTitle));
        order.setCompany(company("Компания", null));
        return order;
    }

    private Order orderWithCompanyManagerAndDetail(Long id, String statusTitle, String companyTitle, String groupId) {
        Order order = order(id, statusTitle);
        order.setCompany(company(companyTitle, groupId));
        order.setManager(manager("client"));
        order.setFilial(filial("Филиал"));

        OrderDetails detail = new OrderDetails();
        detail.setId(UUID.randomUUID());
        detail.setOrder(order);
        detail.setReviews(List.of());
        order.setDetails(List.of(detail));
        return order;
    }

    private Order orderWithWorker(Long id, String statusTitle, Long chatId) {
        Order order = orderWithCompanyManagerAndDetail(id, statusTitle, "Компания", "group");
        Worker worker = new Worker();
        worker.setUser(user(chatId));
        order.setWorker(worker);
        return order;
    }

    private Company company(String title, String groupId) {
        Company company = new Company();
        company.setTitle(title);
        company.setGroupId(groupId);
        return company;
    }

    private Manager manager(String clientId) {
        Manager manager = new Manager();
        manager.setClientId(clientId);
        manager.setUser(user(100L));
        return manager;
    }

    private User user(Long chatId) {
        User user = new User();
        user.setTelegramChatId(chatId);
        return user;
    }

    private Filial filial(String title) {
        Filial filial = new Filial();
        filial.setTitle(title);
        return filial;
    }

    private OrderStatus status(String title) {
        OrderStatus status = new OrderStatus();
        status.setTitle(title);
        return status;
    }
}
