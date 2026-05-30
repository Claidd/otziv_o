package com.hunt.otziv.p_products.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.p_products.board.OrderBoardQueryService;
import com.hunt.otziv.p_products.deletion.OrderDeletionService;
import com.hunt.otziv.p_products.editing.OrderEditService;
import com.hunt.otziv.p_products.mapper.OrderDtoMapper;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderDetailsRepository;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.OrderReviewMutationService;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.statistics.OrderStatisticsService;
import com.hunt.otziv.p_products.status.OrderBotLifecycleService;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import com.hunt.otziv.p_products.status.OrderStatusTransitionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.r_review.services.ReviewService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderDetailsRepository orderDetailsRepository;

    @Mock
    private CompanyService companyService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private OrderStatusService orderStatusService;

    @Mock
    private ReviewArchiveService reviewArchiveService;

    @Mock
    private CompanyStatusService companyStatusService;

    @Mock
    private OrderStatusCheckerService orderStatusCheckerService;

    @Mock
    private OrderDtoMapper orderDtoMapper;

    @Mock
    private OrderBoardQueryService orderBoardQueryService;

    @Mock
    private OrderStatisticsService orderStatisticsService;

    @Mock
    private OrderDeletionService orderDeletionService;

    @Mock
    private OrderEditService orderEditService;

    @Mock
    private OrderBotLifecycleService orderBotLifecycleService;

    @Mock
    private OrderStatusTransitionService orderStatusTransitionService;

    @Mock
    private OrderReviewMutationService orderReviewMutationService;

    @Mock
    private OrderStatusNotificationService orderStatusNotificationService;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private GamificationEventService gamificationEventService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    void changeStatusAndOrderCounterSynchronizesCounterToActualPublishedReviews() throws Exception {
        Order order = order(10L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review alreadyPublished = review(1L, true, "Уже опубликован", details);
        Review reviewToPublish = review(2L, false, "Новый отзыв", details);
        details.setReviews(List.of(alreadyPublished, reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(10L));
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));
        when(reviewRepository.countPublishedByOrderId(10L)).thenReturn(2);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, true))
                .thenReturn(true);
        doAnswer(invocation -> {
            Order synchronizedOrder = invocation.getArgument(0);
            Integer actualPublished = invocation.getArgument(1);
            synchronizedOrder.setCounter(actualPublished);
            return null;
        }).when(orderStatusCheckerService).validateCounterConsistency(same(order), eq(2));

        assertTrue(orderService.changeStatusAndOrderCounter(2L));

        assertTrue(reviewToPublish.isPublish());
        verify(reviewRepository).save(reviewToPublish);
        verify(reviewArchiveService).saveNewReviewArchive(2L, ReviewArchiveSourceReason.PUBLISHED);
        verify(reviewRepository).countPublishedByOrderId(10L);
        verify(orderStatusCheckerService).validateCounterConsistency(order, 2);
        verify(orderStatusNotificationService).sendProgressMessageToClientChat(
                order,
                null,
                null,
                "Company - Main filial. Опубликован новый отзыв 2 / 5.",
                false
        );
        verify(orderStatusCheckerService).checkAndMarkOrderCompleted(order);
        verify(reviewService, never()).save(reviewToPublish);
    }

    @Test
    void changeStatusAndOrderCounterAddsProgressControlsOnlyForFirstPublishedReview() throws Exception {
        Order order = order(10L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review reviewToPublish = review(2L, false, "Первый отзыв", details);
        details.setReviews(List.of(reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(10L));
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));
        when(reviewRepository.countPublishedByOrderId(10L)).thenReturn(1);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true))
                .thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.CLIENT_PUBLICATION_PROGRESS_REPORTS_ENABLED, true))
                .thenReturn(true);
        doAnswer(invocation -> {
            Order synchronizedOrder = invocation.getArgument(0);
            Integer actualPublished = invocation.getArgument(1);
            synchronizedOrder.setCounter(actualPublished);
            return null;
        }).when(orderStatusCheckerService).validateCounterConsistency(same(order), eq(1));

        assertTrue(orderService.changeStatusAndOrderCounter(2L));

        verify(orderStatusNotificationService).sendProgressMessageToClientChat(
                order,
                null,
                null,
                "Company - Main filial. Опубликован новый отзыв 1 / 5.",
                true
        );
    }

    @Test
    void changeStatusAndOrderCounterSkipsProgressReportForFinalReview() throws Exception {
        Order order = order(10L, 4);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review reviewToPublish = review(2L, false, "Финальный отзыв", details);
        details.setReviews(List.of(reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(10L));
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));
        when(reviewRepository.countPublishedByOrderId(10L)).thenReturn(5);

        assertTrue(orderService.changeStatusAndOrderCounter(2L));

        verify(orderStatusNotificationService, never()).sendProgressMessageToClientChat(
                same(order),
                eq(null),
                eq(null),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
        verify(orderStatusCheckerService).checkAndMarkOrderCompleted(order);
    }

    @Test
    void changeStatusAndOrderCounterReportsDuplicateCardNumber() {
        Order order = order(10L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Review firstReview = review(1L, false, "Первый отзыв", details);
        Review duplicateReview = review(2L, false, duplicateText, details);
        details.setReviews(List.of(firstReview, duplicateReview));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(10L));
        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(duplicateReview));
        when(reviewRepository.existsPublishedByTextExcludingReviewId(duplicateText, 2L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> orderService.changeStatusAndOrderCounter(2L)
        );

        assertEquals(
                "Такой текст уже опубликован ранее. Измените текст отзыва перед публикацией. Проблемная карточка: №2 (отзыв #2).",
                exception.getReason()
        );
        verifyNoInteractions(orderBotLifecycleService, reviewArchiveService, orderStatusCheckerService);
    }

    @Test
    void changeStatusAndOrderCounterReportsArchivedDuplicateCardNumber() {
        Order order = order(11L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        String duplicateText = "Клиент подробно описал услугу, отметил качество работы специалиста, скорость выполнения и удобство общения с компанией";
        Review firstReview = review(1L, false, "Первый отзыв", details);
        Review duplicateReview = review(2L, false, duplicateText, details);
        details.setReviews(List.of(firstReview, duplicateReview));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(11L));
        when(orderRepository.findByIdForCounterUpdate(11L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(duplicateReview));
        when(reviewArchiveService.existsByTextExcludingOwnSource(duplicateText, 2L, 11L)).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> orderService.changeStatusAndOrderCounter(2L)
        );

        assertEquals(
                "Такой текст уже есть в архиве текстов. Возможно, он был зарезервирован или использован ранее. Измените текст отзыва перед публикацией. Проблемная карточка: №2 (отзыв #2).",
                exception.getReason()
        );
        verify(reviewRepository, never()).save(duplicateReview);
        verify(reviewArchiveService, never()).saveNewReviewArchive(eq(2L), org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(orderBotLifecycleService, orderStatusCheckerService);
    }

    @Test
    void changeStatusAndOrderCounterRejectsPlaceholderWithOperatorNote() {
        Order order = order(13L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review placeholderReview = review(2L, false, "текст отзыва Нужно подсавить текст", details);
        details.setReviews(List.of(placeholderReview));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(13L));
        when(orderRepository.findByIdForCounterUpdate(13L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(placeholderReview));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> orderService.changeStatusAndOrderCounter(2L)
        );

        assertEquals(
                "Нельзя опубликовать отзыв: заполните настоящий текст. Проблемная карточка: №1 (отзыв #2).",
                exception.getReason()
        );
        verify(reviewRepository, never()).save(placeholderReview);
        verifyNoInteractions(orderBotLifecycleService, reviewArchiveService, orderStatusCheckerService);
    }

    @Test
    void changeStatusAndOrderCounterRejectsTemplateBotAccount() {
        Order order = order(14L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        Review reviewToPublish = review(2L, false, "Готовый текст отзыва", details);
        reviewToPublish.setBot(bot(99L, "Впиши Имя Фамилию", "79000000000", true));
        details.setReviews(List.of(reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(14L));
        when(orderRepository.findByIdForCounterUpdate(14L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> orderService.changeStatusAndOrderCounter(2L)
        );

        assertEquals(
                "Нельзя опубликовать отзыв: назначьте реальный аккаунт. Проблемная карточка: №1 (отзыв #2).",
                exception.getReason()
        );
        verify(reviewRepository, never()).save(reviewToPublish);
        verifyNoInteractions(orderBotLifecycleService, reviewArchiveService, orderStatusCheckerService);
    }

    @Test
    void changeStatusAndOrderCounterAllowsShortCommonReviewTextWithoutHistoryLookup() throws Exception {
        Order order = order(12L, 0);
        OrderDetails details = new OrderDetails();
        details.setOrder(order);

        String shortText = "Спасибо, все было отлично";
        Review reviewToPublish = review(2L, false, shortText, details);
        details.setReviews(List.of(reviewToPublish));
        order.setDetails(List.of(details));

        when(reviewRepository.findOrderIdByReviewId(2L)).thenReturn(Optional.of(12L));
        when(orderRepository.findByIdForCounterUpdate(12L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByIdForPublication(2L)).thenReturn(Optional.of(reviewToPublish));
        when(reviewRepository.countPublishedByOrderId(12L)).thenReturn(5);

        assertTrue(orderService.changeStatusAndOrderCounter(2L));

        assertTrue(reviewToPublish.isPublish());
        verify(reviewRepository, never()).existsPublishedByTextExcludingReviewId(shortText, 2L);
        verify(reviewArchiveService, never()).existsByTextExcludingOwnSource(shortText, 2L, 12L);
        verify(reviewRepository).save(reviewToPublish);
        verify(reviewArchiveService).saveNewReviewArchive(2L, ReviewArchiveSourceReason.PUBLISHED);
        verify(orderStatusCheckerService).checkAndMarkOrderCompleted(order);
    }

    private Order order(Long id, int counter) {
        Order order = new Order();
        order.setId(id);
        order.setCounter(counter);
        order.setAmount(5);
        Company company = new Company();
        company.setTitle("Company");
        company.setPublicationProgressReportsEnabled(true);
        order.setCompany(company);
        Filial filial = new Filial();
        filial.setTitle("Main filial");
        order.setFilial(filial);
        return order;
    }

    private Review review(Long id, boolean publish, String text, OrderDetails details) {
        Review review = new Review();
        review.setId(id);
        review.setPublish(publish);
        review.setText(text);
        review.setOrderDetails(details);
        review.setBot(bot(10L + id, "Анна Иванова", "79000000000" + id, true));
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
}
