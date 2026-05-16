package com.hunt.otziv.bad_reviews.services;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BadReviewTaskServiceImplTest {

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private BotService botService;

    @Mock
    private PersonalReminderService personalReminderService;

    @InjectMocks
    private BadReviewTaskServiceImpl service;

    @Test
    void createTasksForUnpaidOrderCopiesReviewBotSnapshot() {
        Order order = order(12L);
        Bot bot = Bot.builder()
                .id(7L)
                .fio("Аккаунт П.")
                .login("real-login")
                .password("real-password")
                .build();
        Review review = Review.builder()
                .id(88L)
                .publish(true)
                .text("хороший опубликованный текст")
                .bot(bot)
                .price(BigDecimal.valueOf(250))
                .build();

        when(reviewRepository.getAllByOrderId(12L)).thenReturn(List.of(review));
        when(badReviewTaskRepository.existsByOrderIdAndSourceReviewIdAndStatusIn(eq(12L), eq(88L), any()))
                .thenReturn(false);

        assertEquals(1, service.createTasksForUnpaidOrder(order));
        verify(badReviewTaskRepository).save(argThat(task ->
                task.getBot() == bot
                        && "real-login".equals(task.getBotLoginSnapshot())
                        && "real-password".equals(task.getBotPasswordSnapshot())
                        && "Аккаунт П.".equals(task.getBotFioSnapshot())
                        && "хороший опубликованный текст".equals(task.getTaskText())
                        && LocalDate.now().equals(task.getScheduledDate())
        ));
    }

    @Test
    void updateTaskChangesOnlyActiveTaskTextAndDate() {
        BadReviewTask task = BadReviewTask.builder()
                .id(42L)
                .status(BadReviewTaskStatus.NEW)
                .taskText("старый текст")
                .scheduledDate(LocalDate.of(2026, 5, 20))
                .build();

        when(badReviewTaskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(badReviewTaskRepository.save(task)).thenReturn(task);

        BadReviewTask updated = service.updateTask(42L, "новый плохой текст", LocalDate.of(2026, 5, 27));

        assertEquals("новый плохой текст", updated.getTaskText());
        assertEquals(LocalDate.of(2026, 5, 27), updated.getScheduledDate());
        verify(badReviewTaskRepository).save(task);
    }

    @Test
    void changeTaskBotSyncsSourceReviewBot() {
        City city = City.builder().id(3L).title("Иркутск").build();
        Filial filial = Filial.builder().id(4L).city(city).build();
        Bot oldBot = Bot.builder()
                .id(7L)
                .fio("Старый П.")
                .login("old-login")
                .password("old-password")
                .active(true)
                .build();
        Bot nextBot = Bot.builder()
                .id(8L)
                .fio("Новый П.")
                .login("next-login")
                .password("next-password")
                .active(true)
                .build();
        Review review = Review.builder()
                .id(88L)
                .bot(oldBot)
                .filial(filial)
                .build();
        BadReviewTask task = BadReviewTask.builder()
                .id(42L)
                .sourceReview(review)
                .bot(oldBot)
                .status(BadReviewTaskStatus.NEW)
                .build();

        when(badReviewTaskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(botService.getFindAllByFilialCityId(3L)).thenReturn(List.of(oldBot, nextBot));
        when(badReviewTaskRepository.save(task)).thenReturn(task);

        BadReviewTask updated = service.changeTaskBot(42L);

        assertEquals(nextBot, updated.getBot());
        assertEquals(nextBot, review.getBot());
        assertEquals("next-login", updated.getBotLoginSnapshot());
        assertEquals("next-password", updated.getBotPasswordSnapshot());
        assertEquals("Новый П.", updated.getBotFioSnapshot());
        verify(reviewRepository).save(review);
        verify(badReviewTaskRepository).save(task);
    }

    @Test
    void completeTaskCreatesPaymentReminderAndFinalBanReminderWhenAllBadTasksDone() {
        Order order = order(10L);
        BadReviewTask task = BadReviewTask.builder()
                .id(40L)
                .order(order)
                .status(BadReviewTaskStatus.NEW)
                .price(BigDecimal.valueOf(300))
                .build();

        when(badReviewTaskRepository.findById(40L)).thenReturn(Optional.of(task));
        when(badReviewTaskRepository.save(task)).thenReturn(task);
        when(badReviewTaskRepository.summarizeByOrderId(10L)).thenReturn(List.<Object[]>of(
                new Object[]{BadReviewTaskStatus.DONE, 2L, BigDecimal.valueOf(600)}
        ));

        BadReviewTask completed = service.completeTask(40L);

        assertEquals(BadReviewTaskStatus.DONE, completed.getStatus());
        verify(personalReminderService).deleteSystemReminderBySource(
                order.getManager().getUser(),
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                40L
        );
        verify(personalReminderService).createSystemReminderDueNow(
                order.getManager().getUser(),
                "Плохой отзыв выполнен: Компания 10",
                "Компания: Компания 10\nЗаказ #10\nЧат: https://chat.example/10\nПлохой отзыв #40 выполнен, можно отправить клиенту счет.\nК оплате: 1600 руб.",
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                40L,
                10L
        );
        verify(personalReminderService).createSystemReminderDueNow(
                order.getManager().getUser(),
                "Плохие отзывы завершены: Компания 10",
                "Компания: Компания 10\nЗаказ #10\nЧат: https://chat.example/10\nВсе плохие отзывы выполнены. Если клиент не оплатит, можно перевести заказ в Бан.\nК оплате: 1600 руб.",
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                10L,
                10L
        );
    }

    @Test
    void completeTaskDoesNotCreateFinalBanReminderWhilePendingBadTasksRemain() {
        Order order = order(11L);
        BadReviewTask task = BadReviewTask.builder()
                .id(41L)
                .order(order)
                .status(BadReviewTaskStatus.NEW)
                .price(BigDecimal.valueOf(300))
                .build();

        when(badReviewTaskRepository.findById(41L)).thenReturn(Optional.of(task));
        when(badReviewTaskRepository.save(task)).thenReturn(task);
        when(badReviewTaskRepository.summarizeByOrderId(11L)).thenReturn(List.<Object[]>of(
                new Object[]{BadReviewTaskStatus.NEW, 1L, BigDecimal.valueOf(300)},
                new Object[]{BadReviewTaskStatus.DONE, 1L, BigDecimal.valueOf(300)}
        ));

        service.completeTask(41L);

        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(order.getManager().getUser()),
                startsWith("Плохие отзывы завершены"),
                any(),
                eq(PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY),
                any(),
                any()
        );
    }

    @Test
    void cancelDoneTaskRemovesTaskReminderAndOrderReadyReminderWhenNoDoneTasksRemain() {
        Order order = order(12L);
        BadReviewTask task = BadReviewTask.builder()
                .id(42L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(BigDecimal.valueOf(300))
                .build();

        when(badReviewTaskRepository.findById(42L)).thenReturn(Optional.of(task));
        when(badReviewTaskRepository.save(task)).thenReturn(task);
        when(badReviewTaskRepository.summarizeByOrderId(12L)).thenReturn(List.<Object[]>of(
                new Object[]{BadReviewTaskStatus.CANCELED, 1L, BigDecimal.ZERO}
        ));

        BadReviewTask canceled = service.cancelTask(42L);

        assertEquals(BadReviewTaskStatus.CANCELED, canceled.getStatus());
        verify(personalReminderService).deleteSystemReminderBySource(
                order.getManager().getUser(),
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                42L
        );
        verify(personalReminderService).deleteSystemReminderBySource(
                order.getManager().getUser(),
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                12L
        );
        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(order.getManager().getUser()),
                startsWith("Плохие отзывы завершены"),
                any(),
                eq(PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY),
                any(),
                any()
        );
    }

    @Test
    void cancelDoneTaskRefreshesOrderReadyReminderWhenOtherDoneTasksRemain() {
        Order order = order(13L);
        BadReviewTask task = BadReviewTask.builder()
                .id(43L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(BigDecimal.valueOf(300))
                .build();

        when(badReviewTaskRepository.findById(43L)).thenReturn(Optional.of(task));
        when(badReviewTaskRepository.save(task)).thenReturn(task);
        when(badReviewTaskRepository.summarizeByOrderId(13L)).thenReturn(List.<Object[]>of(
                new Object[]{BadReviewTaskStatus.DONE, 1L, BigDecimal.valueOf(300)},
                new Object[]{BadReviewTaskStatus.CANCELED, 1L, BigDecimal.ZERO}
        ));

        BadReviewTask canceled = service.cancelTask(43L);

        assertEquals(BadReviewTaskStatus.CANCELED, canceled.getStatus());
        verify(personalReminderService).deleteSystemReminderBySource(
                order.getManager().getUser(),
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                43L
        );
        verify(personalReminderService).createSystemReminderDueNow(
                order.getManager().getUser(),
                "Плохие отзывы завершены: Компания 13",
                "Компания: Компания 13\nЗаказ #13\nЧат: https://chat.example/13\nВсе плохие отзывы выполнены. Если клиент не оплатит, можно перевести заказ в Бан.\nК оплате: 1300 руб.",
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                13L,
                13L
        );
    }

    private Order order(Long id) {
        User user = new User();
        user.setId(5L);
        Manager manager = new Manager();
        manager.setId(50L);
        manager.setUser(user);
        Company company = new Company();
        company.setId(70L);
        company.setTitle("Компания " + id);
        company.setUrlChat("https://chat.example/" + id);

        Order order = new Order();
        order.setId(id);
        order.setManager(manager);
        order.setCompany(company);
        order.setSum(BigDecimal.valueOf(1000));
        return order;
    }
}
