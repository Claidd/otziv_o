package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Fail-closed classification of payment-dependent rows created before cutover. */
@Service
@RequiredArgsConstructor
public class ContractorLegacyRewardGuard {

    private final ZpRepository zpRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireNoActiveLegacyAggregate(Long orderId) {
        List<Zp> legacy = activePaymentDependentRows(orderId);
        if (!legacy.isEmpty()) {
            throw reconciliationRequired();
        }
    }

    /**
     * A pre-cutover order may legitimately retain payment-dependent rows while
     * a separately dated task is completed after cutover. Only explicitly
     * classified rows whose own occurrence date is strictly before the
     * boundary can coexist with that new task.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireOnlyDatedPreCutoffLegacyAggregate(Long orderId, LocalDate attributionStart) {
        if (attributionStart == null) {
            throw reconciliationRequired();
        }
        boolean ambiguous = activePaymentDependentRows(orderId).stream()
                .anyMatch(reward -> !ContractorRewardSourceCodes.isLegacyEarnedReward(reward.getSource())
                        || reward.getCreated() == null
                        || !reward.getCreated().isBefore(attributionStart));
        if (ambiguous) {
            throw reconciliationRequired();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireCancellationClassifiable(Long orderId) {
        boolean ambiguous = activePaymentDependentRows(orderId).stream()
                .anyMatch(reward -> !ContractorRewardSourceCodes.isLegacyEarnedReward(reward.getSource()));
        if (ambiguous) {
            throw reconciliationRequired();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireNoUnclassifiedActiveRows(Long orderId) {
        boolean ambiguous = activePaymentDependentRows(orderId).stream()
                .anyMatch(reward -> !ContractorRewardSourceCodes.isLegacyEarnedReward(reward.getSource()));
        if (ambiguous) {
            throw reconciliationRequired();
        }
    }

    private List<Zp> activePaymentDependentRows(Long orderId) {
        if (orderId == null || orderId <= 0) {
            return List.of();
        }
        return zpRepository.findByOrderIdAndActiveTrue(orderId).stream()
                .filter(reward -> !isRecognizedCompletionReward(orderId, reward.getSource()))
                .toList();
    }

    private boolean isRecognizedCompletionReward(Long orderId, String source) {
        if (!ContractorCompletionRewardService.isCompletionBasedSource(source)) {
            return false;
        }
        var taskId = ContractorRewardSourceCodes.completionTaskId(source);
        return taskId.isEmpty()
                || badReviewTaskRepository.findOrderIdById(taskId.getAsLong())
                        .filter(taskOrderId -> Objects.equals(taskOrderId, orderId))
                        .isPresent();
    }

    private ResponseStatusException reconciliationRequired() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Найдено ранее созданное начисление без безопасного разделения источников; "
                        + "нужна датированная ручная сверка"
        );
    }
}
