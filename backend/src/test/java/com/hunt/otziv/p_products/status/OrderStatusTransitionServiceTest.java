package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private TelegramService telegramService;

    @Mock
    private OrderCompanyStatusService orderCompanyStatusService;

    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;

    @Mock
    private OrderBotLifecycleService orderBotLifecycleService;

    @Mock
    private ReviewArchiveService reviewArchiveService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderPaymentMessageBuilder orderPaymentMessageBuilder;

    @Mock
    private OrderReviewCheckMessageBuilder orderReviewCheckMessageBuilder;

    @Mock
    private MobilePushBusinessNotificationService mobilePushBusinessNotificationService;

    @Mock
    private OrderCorrectionTelegramNotifier orderCorrectionTelegramNotifier;

    @Test
    void paymentStatusDelegatesToTransactionServiceFromBan() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(1L, "Бан");

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
    void banStatusRequiresNotPaidOrder() {
        OrderStatusTransitionService service = service();
        Order order = order(31L, "Напоминание");

        when(orderRepository.findById(31L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(31L, "Бан")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("Перевести заказ в Бан можно только из статуса \"Не оплачено\"", exception.getReason());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void banStatusRequiresAllBadTasksDone() {
        OrderStatusTransitionService service = service();
        Order order = order(32L, "Не оплачено");

        when(orderRepository.findById(32L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(32L))
                .thenReturn(new BadReviewTaskSummary(2, 1, 1, 0, BigDecimal.valueOf(300), BigDecimal.valueOf(300)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(32L, "Бан")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("Сначала выполните все плохие задачи заказа", exception.getReason());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void banStatusSavesOrderAndDeletesReadyReminderWhenBadTasksDone() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(33L, "Не оплачено");
        OrderStatus ban = status("Бан");

        when(orderRepository.findById(33L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(33L))
                .thenReturn(new BadReviewTaskSummary(2, 0, 2, 0, BigDecimal.valueOf(600), BigDecimal.ZERO));
        when(orderStatusService.getOrderStatusByTitle("Бан")).thenReturn(ban);

        assertTrue(service.changeStatusForOrder(33L, "Бан"));

        assertSame(ban, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Бан");
        verify(badReviewTaskService).deleteOrderReadyReminder(order);
        verify(orderRepository).save(order);
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
        verify(reviewArchiveService, never()).saveNewReviewArchive(org.mockito.ArgumentMatchers.anyLong());
        verify(orderBotLifecycleService).detachBots(order);
        verify(orderRepository).save(order);
    }

    @Test
    void archiveStatusSavesValidReviewsToArchiveBeforeDetachingBots() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(42L, "Публикация", 420L, "Готовый текст отзыва");
        OrderStatus archive = status("Архив");

        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Архив")).thenReturn(archive);

        assertTrue(service.changeStatusForOrder(42L, "Архив"));

        InOrder inOrder = inOrder(reviewArchiveService, orderBotLifecycleService, orderRepository);
        inOrder.verify(reviewArchiveService).saveNewReviewArchive(420L);
        inOrder.verify(orderBotLifecycleService).detachBots(order);
        inOrder.verify(orderRepository).save(order);
    }

    @Test
    void archiveStatusClearsPublicationDatesOnlyForUnpublishedReviews() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithPublicationReviews(43L, "Публикация");
        List<Review> reviews = order.getDetails().getFirst().getReviews();
        OrderStatus archive = status("Архив");

        when(orderRepository.findById(43L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Архив")).thenReturn(archive);

        assertTrue(service.changeStatusForOrder(43L, "Архив"));

        assertNull(reviews.get(0).getPublishedDate());
        assertEquals(LocalDate.of(2026, 1, 11), reviews.get(1).getPublishedDate());
        assertSame(archive, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void archiveStatusRejectsBlankReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(40L, "Публикация", 400L, " ");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findById(40L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(40L, "Архив")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Нельзя отправить заказ в архив: заполните текст всех отзывов", exception.getReason());
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("Архив");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, reviewArchiveService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void archiveStatusRejectsPlaceholderReviewTextIgnoringCase() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(41L, "Публикация", 410L, "  текст отзыва  ");

        when(orderRepository.findById(41L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(41L, "Архив")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Нельзя отправить заказ в архив: заполните текст всех отзывов", exception.getReason());
        verify(orderStatusService, never()).getOrderStatusByTitle("Архив");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, reviewArchiveService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void toCheckWithoutClientDeliveryKeepsToCheckStatusAndReturnsTrue() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(5L, "Новый", "Компания", " ");
        OrderStatus toCheck = status("В проверку");

        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderReviewCheckMessageBuilder.reviewCheckMessage(order)).thenReturn("Проверьте отзывы");
        when(orderStatusNotificationService.sendMessageToClientChat(
                eq("В проверку"),
                same(order),
                eq("client"),
                eq(" "),
                contains("Проверьте отзывы"),
                eq("На проверке")
        )).thenAnswer(invocation -> {
            order.setStatus(toCheck);
            return "В проверку";
        });

        assertTrue(service.changeStatusForOrder(5L, "В проверку"));

        assertSame(toCheck, order.getStatus());
        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "В проверку");
        verify(orderStatusNotificationService).sendMessageToClientChat(
                eq("В проверку"),
                same(order),
                eq("client"),
                eq(" "),
                contains("Проверьте отзывы"),
                eq("На проверке")
        );
        verify(orderRepository, never()).save(order);
    }

    @Test
    void manualInCheckStatusDoesNotSendClientMessage() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(52L, "В проверку", "Компания", " ");
        OrderStatus inCheck = status("На проверке");

        when(orderRepository.findById(52L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("На проверке")).thenReturn(inCheck);

        assertTrue(service.changeStatusForOrder(52L, "На проверке"));

        assertSame(inCheck, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "На проверке");
        verify(orderRepository).save(order);
        verifyNoInteractions(orderStatusNotificationService, orderBotLifecycleService, orderReviewCheckMessageBuilder);
    }

    @Test
    void toCheckRejectsBlankReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(50L, "Новый", 500L, "");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findById(50L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(50L, "В проверку")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Нельзя отправить заказ на проверку: заполните текст всех отзывов", exception.getReason());
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("В проверку");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void toCheckRejectsPreviouslyPublishedReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(51L, "Новый", 510L, "Уже опубликованный текст");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findById(51L)).thenReturn(Optional.of(order));
        when(reviewRepository.existsPublishedByTextExcludingReviewId("Уже опубликованный текст", 510L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(51L, "В проверку")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ на проверку: текст отзыва уже публиковался ранее. Измените текст отзыва и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #510).",
                exception.getReason()
        );
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("В проверку");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void publicStatusWithGroupSendsPaymentMessageToGroup() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(6L, "Публикация", "Компания", "group");
        order.setSum(BigDecimal.valueOf(1500));

        when(orderRepository.findById(6L)).thenReturn(Optional.of(order));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessage(order))
                .thenReturn("Компания. Филиал\n\nОплата К оплате: 1500 руб.");
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
    void publicStatusKeepsManualTransitionSuccessfulWhenClientMessageFails() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(61L, "Публикация", "Компания", "group");

        when(orderRepository.findById(61L)).thenReturn(Optional.of(order));
        when(orderPaymentMessageBuilder.publishedOrderPaymentMessage(order))
                .thenReturn("Компания. Филиал\n\nОплата К оплате: 1500 руб.");
        when(orderStatusNotificationService.sendMessageToGroup(
                eq("Опубликовано"),
                same(order),
                eq("client"),
                eq("group"),
                contains("К оплате: 1500"),
                eq("Выставлен счет")
        )).thenReturn(false);

        assertTrue(service.changeStatusForOrder(61L, "Опубликовано"));

        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Опубликовано");
        verify(mobilePushBusinessNotificationService).notifyManagerOrderPublished(order);
    }

    @Test
    void manualToPayStatusDoesNotSendClientMessage() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(62L, "Опубликовано", "Компания", "group");
        OrderStatus toPay = status("Выставлен счет");

        when(orderRepository.findById(62L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(toPay);

        assertTrue(service.changeStatusForOrder(62L, "Выставлен счет"));

        assertSame(toPay, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Выставлен счет");
        verify(orderRepository).save(order);
        verifyNoInteractions(orderStatusNotificationService, orderBotLifecycleService, orderReviewCheckMessageBuilder);
    }

    @Test
    void correctionStatusQueuesTelegramAfterSavingOrderWhenWorkerChatIsAvailable() throws Exception {
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
        InOrder inOrder = inOrder(orderRepository, orderCorrectionTelegramNotifier);
        inOrder.verify(orderRepository).save(order);
        inOrder.verify(orderCorrectionTelegramNotifier).notifyWorkerCorrection(
                7L,
                700L,
                "Компания",
                "заметка",
                "комментарий"
        );
        verify(telegramService, never()).sendMessage(
                eq(700L),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void correctionStatusIsIdempotentWhenOrderAlreadyInCorrection() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithWorker(72L, "Коррекция", 720L);

        when(orderRepository.findById(72L)).thenReturn(Optional.of(order));

        assertTrue(service.changeStatusForOrder(72L, "Коррекция"));

        verify(orderBotLifecycleService, never()).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService, never()).autoManageCompanyStatus(order, "Коррекция");
        verify(orderRepository, never()).save(order);
        verify(orderCorrectionTelegramNotifier, never()).notifyWorkerCorrection(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void correctionStatusClearsPublicationDatesOnlyForUnpublishedReviews() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithPublicationReviews(71L, "Публикация");
        List<Review> reviews = order.getDetails().getFirst().getReviews();
        OrderStatus correction = status("Коррекция");

        when(orderRepository.findById(71L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Коррекция")).thenReturn(correction);

        assertTrue(service.changeStatusForOrder(71L, "Коррекция"));

        assertNull(reviews.get(0).getPublishedDate());
        assertEquals(LocalDate.of(2026, 1, 11), reviews.get(1).getPublishedDate());
        assertSame(correction, order.getStatus());
        verify(orderRepository).save(order);
    }

    @Test
    void toPublishFromArchiveNotifiesWorkerAndChecksStubBots() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithWorker(8L, "Архив", 800L);
        Review review = new Review();
        review.setId(80L);
        review.setText("Готовый текст отзыва");
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

    @Test
    void toPublishRejectsPlaceholderReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(81L, "На проверке", 810L, " Текст отзыва ");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findById(81L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(81L, "Публикация")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("Нельзя отправить заказ в публикацию: заполните текст всех отзывов", exception.getReason());
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("Публикация");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void toPublishRejectsDuplicatedReviewTextWithinOrderWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithTwoReviews(82L, "На проверке", "Повторяющийся текст", " повторяющийся текст ");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findById(82L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(82L, "Публикация")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ в публикацию: в заказе есть одинаковые тексты отзывов. Измените повторяющийся текст и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #821), №2 (отзыв #822).",
                exception.getReason()
        );
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("Публикация");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    private OrderStatusTransitionService service() {
        return new OrderStatusTransitionService(
                orderRepository,
                orderStatusService,
                orderTransactionService,
                badReviewTaskService,
                telegramService,
                orderCompanyStatusService,
                orderStatusNotificationService,
                orderBotLifecycleService,
                reviewArchiveService,
                reviewRepository,
                orderPaymentMessageBuilder,
                orderReviewCheckMessageBuilder,
                mobilePushBusinessNotificationService,
                orderCorrectionTelegramNotifier
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

    private Order orderWithReview(Long id, String statusTitle, Long reviewId, String reviewText) {
        Order order = orderWithCompanyManagerAndDetail(id, statusTitle, "Компания", "group");
        Review review = new Review();
        review.setId(reviewId);
        review.setText(reviewText);
        order.getDetails().getFirst().setReviews(List.of(review));
        return order;
    }

    private Order orderWithTwoReviews(Long id, String statusTitle, String firstText, String secondText) {
        Order order = orderWithCompanyManagerAndDetail(id, statusTitle, "Компания", "group");
        order.getDetails().getFirst().setReviews(List.of(
                review(821L, firstText, false, null),
                review(822L, secondText, false, null)
        ));
        return order;
    }

    private Order orderWithPublicationReviews(Long id, String statusTitle) {
        Order order = orderWithCompanyManagerAndDetail(id, statusTitle, "Компания", "group");
        Review unpublished = review(431L, "Готовый текст отзыва 1", false, LocalDate.of(2026, 1, 10));
        Review published = review(432L, "Готовый текст отзыва 2", true, LocalDate.of(2026, 1, 11));
        order.getDetails().getFirst().setReviews(List.of(unpublished, published));
        return order;
    }

    private Review review(Long id, String text, boolean publish, LocalDate publishedDate) {
        Review review = new Review();
        review.setId(id);
        review.setText(text);
        review.setPublish(publish);
        review.setPublishedDate(publishedDate);
        return review;
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
