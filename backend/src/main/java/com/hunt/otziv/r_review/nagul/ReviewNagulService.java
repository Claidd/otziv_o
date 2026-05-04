package com.hunt.otziv.r_review.nagul;

import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewNagulService {

    private final ReviewRepository reviewRepository;
    private final UserService userService;
    private final WorkerService workerService;
    private final ReviewNagulPolicy reviewNagulPolicy;

    @Value("${app.nagul.cooldown}")
    private int nagulCooldownMinutes;

    public boolean hasActiveNagulReviews(Principal principal) {
        if (principal == null) {
            return false;
        }

        User user = userService.findByUserName(principal.getName()).orElse(null);
        if (user == null) {
            return false;
        }

        Worker worker = workerService.getWorkerByUserId(user.getId());
        if (worker == null) {
            return false;
        }

        LocalDate today = LocalDate.now();
        return reviewRepository.existsActiveNagulReviews(worker, today.plusDays(60));
    }

    @Transactional
    public void changeNagulReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            return;
        }
        review.setVigul(true);
        reviewRepository.save(review);
    }

    @Transactional
    public void performNagulWithExceptions(Long reviewId, String username) {
        User currentUser = userService.findByUserName(username)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        List<String> roles = currentUser.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toList());

        boolean isWorker = roles.contains("ROLE_WORKER");
        Worker worker = null;

        if (isWorker) {
            worker = workerService.getWorkerByUserId(currentUser.getId());

            if (worker == null) {
                throw new RuntimeException("Ошибка: не найдена информация о работнике");
            }

            reviewNagulPolicy.validateWorkerCooldown(worker, nagulCooldownMinutes);
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new RuntimeException("Отзыв не найден"));

        reviewNagulPolicy.validateBotName(review);

        review.setVigul(true);

        if (isWorker && worker != null) {
            worker.setLastNagulTime(LocalDateTime.now());
            workerService.save(worker);
        }

        reviewRepository.save(review);
    }
}
