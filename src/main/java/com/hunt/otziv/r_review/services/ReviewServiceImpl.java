package com.hunt.otziv.r_review.services;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.services.BotService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService{

    private final ReviewRepository reviewRepository;
    private final BotService botService;

    public Review save(Review review){
       return reviewRepository.save(review);
    }

    @Override
    public List<Review> getReviewsAllByOrderId(Long id) {
        return reviewRepository.findAllByOrderDetailsId(id);
    }

    @Override
    public void changeBot(Long id) {
        Review review = reviewRepository.findById(id).orElse(null);
        log.info("2. Достали отзыв по id" + id);
        if (review != null){
            List<Bot> bots = botService.getAllBotsByWorkerId(review.getOrderDetails().getOrder().getWorker().getId());
            var random = new SecureRandom();
            review.setBot(bots.get(random.nextInt(bots.size())));
            log.info("3. Установили нового рандомного бота");
            reviewRepository.save(review);
            log.info("4. Сохранили нового бота в отзыве в БД");
        }
        else {
            return;
        }
    }
}
