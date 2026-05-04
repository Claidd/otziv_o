package com.hunt.otziv.r_review.nagul;

import com.hunt.otziv.b_bots.model.Bot;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

    private ReviewNagulService service;

    @BeforeEach
    void setUp() {
        service = new ReviewNagulService(
                reviewRepository,
                userService,
                workerService,
                new ReviewNagulPolicy()
        );
        ReflectionTestUtils.setField(service, "nagulCooldownMinutes", 10);
    }

    @Test
    void performNagulWithExceptionsMarksReviewAndUpdatesWorkerTime() {
        User user = workerUser();
        Worker worker = new Worker();
        Review review = reviewWithBotName("Иван Петров");

        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        when(reviewRepository.findById(15L)).thenReturn(Optional.of(review));

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
    void hasActiveNagulReviewsChecksWorkerReviewsUntilSixtyDaysAhead() {
        Principal principal = () -> "worker";
        User user = workerUser();
        Worker worker = new Worker();

        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(77L)).thenReturn(worker);
        when(reviewRepository.existsActiveNagulReviews(any(Worker.class), any())).thenReturn(true);

        assertTrue(service.hasActiveNagulReviews(principal));

        verify(reviewRepository).existsActiveNagulReviews(any(Worker.class), any());
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
