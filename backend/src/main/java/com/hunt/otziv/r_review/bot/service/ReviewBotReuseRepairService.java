package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.repository.CompanyOrganizationIdentityRepository;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ReviewBotReuseRepairService {
    private final ReviewRepository reviews;
    private final CompanyOrganizationIdentityRepository identities;
    private final ReviewBotChangeService changes;
    private final WorkerAssignmentMutationGuardService mutationGuard;
    private final BusinessAuditService audit;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional(readOnly = true)
    public Preview preview(Long reviewId) {
        return preview(reviews.findById(reviewId).orElseThrow(() -> conflict("Отзыв не найден")));
    }

    @Transactional
    public Preview repair(Long reviewId, Long expectedBotId, long expectedVersion) {
        mutationGuard.assertReview(reviewId);
        Review review = reviews.findByIdForBotChange(reviewId).orElseThrow(() -> conflict("Отзыв не найден"));
        Preview before = preview(review);
        if (review.isPublish() || !Objects.equals(before.botId(), expectedBotId)
                || review.getRowVersion() != expectedVersion || !before.reusedInRelatedCompany()) {
            throw conflict("Карточка изменилась или повторное использование не подтверждено. Обновите проверку");
        }
        changes.changeBot(reviewId);
        entityManager.flush();
        Preview after = preview(review);
        if (after.botId() == null || after.botId() == 1L || Objects.equals(before.botId(), after.botId())
                || after.reusedInRelatedCompany()) {
            throw conflict("Не удалось подобрать свободный аккаунт");
        }
        Long orderId = review.getOrderDetails() == null || review.getOrderDetails().getOrder() == null
                ? null : review.getOrderDetails().getOrder().getId();
        audit.recordRequiredInCurrentTransaction("REVIEW_BOT_REUSE_REPAIRED", "REVIEW", reviewId, orderId, reviewId,
                before.botId(), after.botId(), "Shared 2GIS history; standard account change; previousDate=" + before.date());
        return after;
    }

    private Preview preview(Review review) {
        if (review.getFilial() == null || review.getFilial().getCompany() == null) {
            throw conflict("У карточки не определена компания");
        }
        Long companyId = review.getFilial().getCompany().getId();
        Set<Long> related = identities.relatedCompanyIds(companyId);
        Long botId = review.getBot() == null ? null : review.getBot().getId();
        boolean reused = botId != null && botId != 1L && related.stream().filter(id -> !id.equals(companyId))
                .anyMatch(id -> reviews.findUsedBotIdsByCompanyId(id).contains(botId));
        return new Preview(review.getId(), review.getRowVersion(), botId, review.isPublish(), review.getPublishedDate(),
                related, reused);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Preview(Long reviewId, long version, Long botId, boolean published, LocalDate date,
                          Set<Long> relatedCompanyIds, boolean reusedInRelatedCompany) { }
}
