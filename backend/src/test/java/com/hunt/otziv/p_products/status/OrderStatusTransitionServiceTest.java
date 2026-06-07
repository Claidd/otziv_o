package com.hunt.otziv.p_products.status;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
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

    @Mock
    private ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;

    @Mock
    private PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private ObjectProvider<CommonBillingService> commonBillingServiceProvider;

    @Mock
    private CommonBillingService commonBillingService;

    @Test
    void paymentStatusDelegatesToTransactionServiceFromBan() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(1L, "Бан");

        when(orderRepository.findByIdForMutation(1L)).thenReturn(Optional.of(order));
        when(orderTransactionService.handlePaymentStatus(order)).thenReturn(true);

        assertTrue(service.changeStatusForOrder(1L, "Оплачено"));

        verify(manualPaymentAutoConfirmationService).ensureCanCloseOrderManually(order);
        verify(orderTransactionService).handlePaymentStatus(order);
        verify(manualPaymentAutoConfirmationService).confirmForPaidOrder(order);
        verify(manualPaymentAutoConfirmationService).retireOpenLinksForPaidOrder(order);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void paymentStatusStopsWhenBankPaymentIsInProgress() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(90L, "Выставлен счет");
        ResponseStatusException conflict = new ResponseStatusException(
                HttpStatus.CONFLICT,
                "У заказа есть T-Bank/СБП платеж в процессе. Проверьте его в журнале перед ручным закрытием."
        );

        when(orderRepository.findByIdForMutation(90L)).thenReturn(Optional.of(order));
        doThrow(conflict).when(manualPaymentAutoConfirmationService).ensureCanCloseOrderManually(order);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(90L, "Оплачено")
        );

        assertSame(conflict, exception);
        verify(orderTransactionService, never()).handlePaymentStatus(order);
        verify(manualPaymentAutoConfirmationService, never()).confirmForPaidOrder(order);
        verify(manualPaymentAutoConfirmationService, never()).retireOpenLinksForPaidOrder(order);
    }

    @Test
    void financialStatusRejectsOrderInsideActiveCommonInvoice() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(91L, "Опубликовано");

        when(orderRepository.findByIdForMutation(91L)).thenReturn(Optional.of(order));
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(91L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(91L, "Выставлен счет")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Финансовый статус заказа внутри общего счета меняется только через общий счет",
                exception.getReason()
        );
        verify(orderStatusService, never()).getOrderStatusByTitle("Выставлен счет");
        verify(orderRepository, never()).save(order);
    }

    @Test
    void privilegedBanRejectsOrderInsideActiveCommonInvoice() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(93L, "Не оплачено");

        when(orderRepository.findByIdForMutation(93L)).thenReturn(Optional.of(order));
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.isOrderInActiveCommonInvoice(93L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForPrivilegedOrder(93L, "Бан")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Финансовый статус заказа внутри общего счета меняется только через общий счет",
                exception.getReason()
        );
        verify(orderStatusService, never()).getOrderStatusByTitle("Бан");
        verify(orderRepository, never()).save(order);
    }

    @Test
    void commonBillingInternalStatusChangeBypassesFinancialGuard() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(92L, "Выставлен счет");
        OrderStatus notPaid = status("Не оплачено");

        when(orderRepository.findByIdForMutation(92L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Не оплачено")).thenReturn(notPaid);

        assertTrue(service.changeStatusForCommonBillingOrder(92L, "Не оплачено"));

        assertSame(notPaid, order.getStatus());
        verify(badReviewTaskService).createTasksForUnpaidOrder(order);
    }

    @Test
    void defaultStatusSetsStatusAndSavesOrder() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(2L, "Новый");
        OrderStatus reminder = status("Напоминание");

        when(orderRepository.findByIdForMutation(2L)).thenReturn(Optional.of(order));
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

        when(orderRepository.findByIdForMutation(3L)).thenReturn(Optional.of(order));
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

        when(orderRepository.findByIdForMutation(31L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(31L, "Бан")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("Перевести заказ в Бан можно из статуса \"Не оплачено\" или после финального счета за плохие отзывы", exception.getReason());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void banStatusRequiresAllBadTasksDone() {
        OrderStatusTransitionService service = service();
        Order order = order(32L, "Не оплачено");

        when(orderRepository.findByIdForMutation(32L)).thenReturn(Optional.of(order));
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

        when(orderRepository.findByIdForMutation(33L)).thenReturn(Optional.of(order));
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
    void banStatusAllowsOrderWithoutBadTasks() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(34L, "Не оплачено");
        OrderStatus ban = status("Бан");

        when(orderRepository.findByIdForMutation(34L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(34L))
                .thenReturn(BadReviewTaskSummary.empty());
        when(orderStatusService.getOrderStatusByTitle("Бан")).thenReturn(ban);

        assertTrue(service.changeStatusForOrder(34L, "Бан"));

        assertSame(ban, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Бан");
        verify(orderRepository).save(order);
    }

    @Test
    void banStatusAllowsFinalBadReviewInvoiceOrder() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = order(35L, "Выставлен счет");
        OrderStatus ban = status("Бан");

        when(orderRepository.findByIdForMutation(35L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(35L))
                .thenReturn(new BadReviewTaskSummary(2, 0, 2, 0, BigDecimal.valueOf(600), BigDecimal.ZERO));
        when(orderStatusService.getOrderStatusByTitle("Бан")).thenReturn(ban);

        assertTrue(service.changeStatusForOrder(35L, "Бан"));

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

        when(orderRepository.findByIdForMutation(4L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Архив")).thenReturn(archive);

        assertTrue(service.changeStatusForOrder(4L, "Архив"));

        assertSame(archive, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Архив");
        verify(reviewArchiveService, never()).saveNewReviewArchive(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()
        );
        verify(orderBotLifecycleService).detachBots(order);
        verify(orderRepository).save(order);
    }

    @Test
    void archiveStatusSavesValidReviewsToArchiveBeforeDetachingBots() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(42L, "Публикация", 420L, "Готовый текст отзыва");
        OrderStatus archive = status("Архив");

        when(orderRepository.findByIdForMutation(42L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Архив")).thenReturn(archive);

        assertTrue(service.changeStatusForOrder(42L, "Архив"));

        InOrder inOrder = inOrder(reviewArchiveService, orderBotLifecycleService, orderRepository);
        inOrder.verify(reviewArchiveService).saveNewReviewArchive(420L, ReviewArchiveSourceReason.ORDER_ARCHIVED);
        inOrder.verify(orderBotLifecycleService).detachBots(order);
        inOrder.verify(orderRepository).save(order);
    }

    @Test
    void archiveStatusClearsPublicationDatesOnlyForUnpublishedReviews() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithPublicationReviews(43L, "Публикация");
        List<Review> reviews = order.getDetails().getFirst().getReviews();
        OrderStatus archive = status("Архив");

        when(orderRepository.findByIdForMutation(43L)).thenReturn(Optional.of(order));
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

        when(orderRepository.findByIdForMutation(40L)).thenReturn(Optional.of(order));

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

        when(orderRepository.findByIdForMutation(41L)).thenReturn(Optional.of(order));

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

        enableImmediateMessages();
        when(orderRepository.findByIdForMutation(5L)).thenReturn(Optional.of(order));
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
        verify(paymentInvoiceRetryScheduler).scheduleReviewCheckRetry(order);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void manualInCheckStatusDoesNotSendClientMessage() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(52L, "В проверку", "Компания", " ");
        OrderStatus inCheck = status("На проверке");

        when(orderRepository.findByIdForMutation(52L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("На проверке")).thenReturn(inCheck);

        assertTrue(service.changeStatusForOrder(52L, "На проверке"));

        assertSame(inCheck, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "На проверке");
        verify(orderRepository).save(order);
        verifyNoInteractions(orderStatusNotificationService, orderBotLifecycleService, orderReviewCheckMessageBuilder);
    }

    @Test
    void manualInCheckAllowsShortCommonReviewTextWithoutHistoryLookup() throws Exception {
        OrderStatusTransitionService service = service();
        String shortText = "Спасибо, все было отлично";
        Order order = orderWithReview(55L, "В проверку", 550L, shortText);
        OrderStatus inCheck = status("На проверке");

        when(orderRepository.findByIdForMutation(55L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("На проверке")).thenReturn(inCheck);

        assertTrue(service.changeStatusForOrder(55L, "На проверке"));

        assertSame(inCheck, order.getStatus());
        verify(reviewRepository, never()).existsPublishedByTextExcludingReviewId(shortText, 550L);
        verify(reviewArchiveService, never()).existsByTextExcludingOwnSource(shortText, 550L, 55L);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "На проверке");
        verify(orderRepository).save(order);
        verifyNoInteractions(orderStatusNotificationService, orderBotLifecycleService, orderReviewCheckMessageBuilder);
    }

    @Test
    void manualInCheckRejectsPreviouslyPublishedReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Order order = orderWithReview(53L, "В проверку", 530L, duplicateText);
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(53L)).thenReturn(Optional.of(order));
        when(reviewRepository.existsPublishedByTextExcludingReviewId(duplicateText, 530L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(53L, "На проверке")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ на проверку: текст отзыва уже опубликован ранее. Измените текст отзыва и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #530).",
                exception.getReason()
        );
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("На проверке");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void toCheckRejectsBlankReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(50L, "Новый", 500L, "");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(50L)).thenReturn(Optional.of(order));

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
    void toCheckRejectsPlaceholderReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(56L, "Новый", 560L, " Нужно подсавить текст ");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(56L)).thenReturn(Optional.of(order));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(56L, "В проверку")
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
        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Order order = orderWithReview(51L, "Новый", 510L, duplicateText);
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(51L)).thenReturn(Optional.of(order));
        when(reviewRepository.existsPublishedByTextExcludingReviewId(duplicateText, 510L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(51L, "В проверку")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ на проверку: текст отзыва уже опубликован ранее. Измените текст отзыва и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #510).",
                exception.getReason()
        );
        assertSame(originalStatus, order.getStatus());
        verify(orderStatusService, never()).getOrderStatusByTitle("В проверку");
        verifyNoInteractions(orderCompanyStatusService, orderBotLifecycleService, orderStatusNotificationService);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void toCheckRejectsArchivedReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Order order = orderWithReview(54L, "Новый", 540L, duplicateText);
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(54L)).thenReturn(Optional.of(order));
        when(reviewArchiveService.existsByTextExcludingOwnSource(duplicateText, 540L, 54L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(54L, "В проверку")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ на проверку: текст отзыва уже есть в архиве текстов. Он может быть зарезервирован или использован ранее. Измените текст отзыва и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #540).",
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

        enableImmediateMessages();
        when(orderRepository.findByIdForMutation(6L)).thenReturn(Optional.of(order));
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
        verify(paymentInvoiceRetryScheduler, never()).scheduleRetry(order);
    }

    @Test
    void publicStatusForCommonInvoiceDoesNotSendSinglePaymentMessage() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(63L, "Публикация", "Компания", "group");

        when(orderRepository.findByIdForMutation(63L)).thenReturn(Optional.of(order));
        when(commonBillingServiceProvider.getIfAvailable()).thenReturn(commonBillingService);
        when(commonBillingService.completePublishedOrderIntoCommonInvoice(order)).thenReturn(true);

        assertTrue(service.changeStatusForOrder(63L, "Опубликовано"));

        verify(orderBotLifecycleService).assignBotsIfNeeded(order);
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Опубликовано");
        verify(commonBillingService).completePublishedOrderIntoCommonInvoice(order);
        verify(orderPaymentMessageBuilder, never()).publishedOrderPaymentMessage(order);
        verifyNoInteractions(orderStatusNotificationService);
        verify(paymentInvoiceRetryScheduler, never()).scheduleRetry(order);
        verify(mobilePushBusinessNotificationService).notifyManagerOrderPublished(order);
    }

    @Test
    void publicStatusKeepsManualTransitionSuccessfulWhenClientMessageFails() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(61L, "Публикация", "Компания", "group");

        enableImmediateMessages();
        when(orderRepository.findByIdForMutation(61L)).thenReturn(Optional.of(order));
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
        verify(paymentInvoiceRetryScheduler).scheduleRetry(order);
        verify(mobilePushBusinessNotificationService).notifyManagerOrderPublished(order);
    }

    @Test
    void manualToPayStatusDoesNotSendClientMessage() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(62L, "Опубликовано", "Компания", "group");
        OrderStatus toPay = status("Выставлен счет");

        when(orderRepository.findByIdForMutation(62L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Выставлен счет")).thenReturn(toPay);

        assertTrue(service.changeStatusForOrder(62L, "Выставлен счет"));

        assertSame(toPay, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Выставлен счет");
        verify(orderRepository).save(order);
        verifyNoInteractions(orderStatusNotificationService, orderBotLifecycleService, orderReviewCheckMessageBuilder);
    }

    @Test
    void manualToPayStatusRejectsBadReviewOrdersSoTheyStayNotPaid() {
        OrderStatusTransitionService service = service();
        Order order = orderWithCompanyManagerAndDetail(63L, "Не оплачено", "Компания", "group");

        when(orderRepository.findByIdForMutation(63L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(63L))
                .thenReturn(new BadReviewTaskSummary(2, 0, 2, 0, BigDecimal.valueOf(600), BigDecimal.ZERO));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(63L, "Выставлен счет")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("После плохих отзывов заказ остается в статусе \"Не оплачено\" до оплаты или автобана", exception.getReason());
        verify(paymentInvoiceRetryScheduler, never()).scheduleBadReviewAutoBan(order);
    }

    @Test
    void correctionStatusQueuesTelegramAfterSavingOrderWhenWorkerChatIsAvailable() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithWorker(7L, "Публикация", 700L);
        order.setZametka("заметка");
        order.getCompany().setCommentsCompany("комментарий");
        OrderStatus correction = status("Коррекция");

        when(orderRepository.findByIdForMutation(7L)).thenReturn(Optional.of(order));
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

        when(orderRepository.findByIdForMutation(72L)).thenReturn(Optional.of(order));

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

        when(orderRepository.findByIdForMutation(71L)).thenReturn(Optional.of(order));
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
        review.setBot(bot(80L, "Анна Иванова", "79000000080", true));
        order.getDetails().getFirst().setReviews(List.of(review));
        OrderStatus toPublish = status("Публикация");

        when(orderRepository.findByIdForMutation(8L)).thenReturn(Optional.of(order));
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
    void toPublishFromInCheckSendsPublicationStartedMessageToClientChat() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(84L, "На проверке", 840L, "Готовый текст отзыва");
        OrderStatus toPublish = status("Публикация");

        enableImmediateMessages();
        when(orderRepository.findByIdForMutation(84L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Публикация")).thenReturn(toPublish);
        when(orderReviewCheckMessageBuilder.publicationStartedMessage(order))
                .thenReturn("Компания. Филиал\n\nОтзывы переданы в публикацию");
        when(orderStatusNotificationService.sendInformationalMessageToClientChat(
                same(order),
                eq("client"),
                eq("group"),
                contains("Отзывы переданы в публикацию"),
                eq("заказ передан в публикацию")
        )).thenReturn(true);

        assertTrue(service.changeStatusForOrder(84L, "Публикация"));

        assertSame(toPublish, order.getStatus());
        verify(orderCompanyStatusService).autoManageCompanyStatus(order, "Публикация");
        verify(orderRepository).save(order);
        verify(orderStatusNotificationService).sendInformationalMessageToClientChat(
                same(order),
                eq("client"),
                eq("group"),
                contains("Отзывы переданы в публикацию"),
                eq("заказ передан в публикацию")
        );
    }

    @Test
    void toPublishFromInCheckKeepsStatusWhenPublicationStartedMessageFails() throws Exception {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(85L, "На проверке", 850L, "Готовый текст отзыва");
        OrderStatus toPublish = status("Публикация");

        enableImmediateMessages();
        when(orderRepository.findByIdForMutation(85L)).thenReturn(Optional.of(order));
        when(orderStatusService.getOrderStatusByTitle("Публикация")).thenReturn(toPublish);
        when(orderReviewCheckMessageBuilder.publicationStartedMessage(order))
                .thenReturn("Компания. Филиал\n\nОтзывы переданы в публикацию");
        when(orderStatusNotificationService.sendInformationalMessageToClientChat(
                same(order),
                eq("client"),
                eq("group"),
                contains("Отзывы переданы в публикацию"),
                eq("заказ передан в публикацию")
        )).thenReturn(false);

        assertTrue(service.changeStatusForOrder(85L, "Публикация"));

        assertSame(toPublish, order.getStatus());
        verify(orderRepository).save(order);
        verify(orderStatusNotificationService).sendInformationalMessageToClientChat(
                same(order),
                eq("client"),
                eq("group"),
                contains("Отзывы переданы в публикацию"),
                eq("заказ передан в публикацию")
        );
    }

    @Test
    void toPublishRejectsPlaceholderReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        Order order = orderWithReview(81L, "На проверке", 810L, " Текст отзыва ");
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(81L)).thenReturn(Optional.of(order));

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

        when(orderRepository.findByIdForMutation(82L)).thenReturn(Optional.of(order));

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

    @Test
    void toPublishRejectsArchivedReviewTextWithoutSideEffects() {
        OrderStatusTransitionService service = service();
        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Order order = orderWithReview(83L, "На проверке", 830L, duplicateText);
        OrderStatus originalStatus = order.getStatus();

        when(orderRepository.findByIdForMutation(83L)).thenReturn(Optional.of(order));
        when(reviewArchiveService.existsByTextExcludingOwnSource(duplicateText, 830L, 83L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.changeStatusForOrder(83L, "Публикация")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "Нельзя отправить заказ в публикацию: текст отзыва уже есть в архиве текстов. Он может быть зарезервирован или использован ранее. Измените текст отзыва и сохраните его дискеткой. Проблемные карточки: №1 (отзыв #830).",
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
                orderCorrectionTelegramNotifier,
                manualPaymentAutoConfirmationService,
                paymentInvoiceRetryScheduler,
                appSettingService,
                businessAuditService,
                commonBillingServiceProvider
        );
    }

    private void enableImmediateMessages() {
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true)).thenReturn(true);
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
        review.setBot(bot(reviewId + 1000, "Анна Иванова", "7900000" + reviewId, true));
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
        review.setBot(bot(id + 1000, "Анна Иванова", "7900000" + id, true));
        return review;
    }

    private Bot bot(Long id, String fio, String login, boolean active) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setLogin(login);
        bot.setActive(active);
        return bot;
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
