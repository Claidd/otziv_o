package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.r_review.bot.repository.ReviewBotAssignmentExclusionRepository;
import com.hunt.otziv.r_review.model.Review;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReviewBotAssignmentExclusionService {

    private static final long STUB_BOT_ID = 1L;

    private final ReviewBotAssignmentExclusionRepository repository;

    public Set<Long> excludedBotIds(Review review) {
        if (review == null || review.getId() == null) {
            return new HashSet<>();
        }
        Set<Long> result = repository.findBotIdsByReviewId(review.getId());
        return result == null ? new HashSet<>() : new HashSet<>(result);
    }

    public void rejectCurrentBot(Review review, String reason) {
        if (review == null) {
            return;
        }
        reject(review.getId(), review.getBot(), reason);
    }

    public void reject(Long reviewId, Bot bot, String reason) {
        if (reviewId == null || bot == null || bot.getId() == null || STUB_BOT_ID == bot.getId()) {
            return;
        }
        repository.insertIgnore(reviewId, bot.getId(), normalizeReason(reason));
    }

    public int clearForReview(Long reviewId) {
        return reviewId == null ? 0 : repository.deleteByReviewId(reviewId);
    }

    public int clearPublishedBefore(java.time.LocalDateTime cutoff) {
        return cutoff == null ? 0 : repository.deletePublishedBefore(cutoff);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "CHANGE";
        }
        String normalized = reason.trim().toUpperCase();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}
