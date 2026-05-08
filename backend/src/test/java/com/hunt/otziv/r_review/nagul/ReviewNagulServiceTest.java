package com.hunt.otziv.r_review.nagul;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewNagulServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserService userService;

    @Mock
    private WorkerService workerService;

    @Mock
    private AppSettingService appSettingService;

    private ReviewNagulService service;

    @BeforeEach
    void setUp() {
        service = new ReviewNagulService(
                reviewRepository,
                userService,
                workerService,
                new ReviewNagulPolicy(),
                appSettingService
        );
        ReflectionTestUtils.setField(service, "defaultNagulCooldownMinutes", 10);
        ReflectionTestUtils.setField(service, "defaultNagulLookaheadDays", 60);
    }

    @Test
    void performNagulWithExceptionsMarksReviewAndUpdatesWorkerTime() {
        User user = workerUser();
        Worker worker = new Worker();
        Review review = reviewWithBotName("Иван Петров");

        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        when(reviewRepository.findById(15L)).thenReturn(Optional.of(review));
        when(appSettingService.getInt(AppSettingService.NAGUL_COOLDOWN_MINUTES, 10)).thenReturn(10);

        service.performNagulWithExceptions(15L, "worker");

        assertTrue(review.isVigul());
        assertTrue(worker.getLastNagulTime() != null);
        verify(workerService).save(worker);
        verify(reviewRepository).save(review);
    }

    @Test
    void changeNagulReviewIgnoresMissingReview() {
        when(reviewRepository.findById(404L)).thenReturn(Optional.empty());

        service.changeNagulReview(404L);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void hasActiveNagulReviewsChecksWorkerReviewsUntilConfiguredDaysAhead() {
        Principal principal = () -> "worker";
        User user = workerUser();
        Worker worker = new Worker();
        LocalDate expectedDate = LocalDate.now().plusDays(21);

        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        when(appSettingService.getInt(AppSettingService.NAGUL_LOOKAHEAD_DAYS, 60)).thenReturn(21);
        when(reviewRepository.existsActiveNagulReviews(any(Worker.class), any())).thenReturn(true);

        assertTrue(service.hasActiveNagulReviews(principal));

        verify(reviewRepository).existsActiveNagulReviews(eq(worker), eq(expectedDate));
    }

    private User workerUser() {
        Role role = new Role();
        role.setName("ROLE_WORKER");

        User user = new User();
        user.setId(77L);
        user.setRoles(List.of(role));
        return user;
    }

    private Review reviewWithBotName(String botName) {
        Bot bot = new Bot();
        bot.setFio(botName);

        Review review = new Review();
        review.setBot(bot);
        return review;
    }
}
