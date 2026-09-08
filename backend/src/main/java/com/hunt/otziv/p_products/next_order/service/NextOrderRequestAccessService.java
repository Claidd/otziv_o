package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.api.NextOrderRequests;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NextOrderRequestAccessService implements NextOrderRequests {
    private static final Set<NextOrderRequestStatus> OPEN = Set.of(NextOrderRequestStatus.PENDING, NextOrderRequestStatus.FAILED);
    private final NextOrderRequestRepository requests;

    @Override
    public Map<Long, CompanySummary> companySummaries(Collection<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) return Map.of();
        Map<Long, MutableSummary> summaries = new HashMap<>();
        for (var request : requests.findCompanyActivity(companyIds, OPEN)) {
            if (request.getCompanyId() == null) continue;
            var summary = summaries.computeIfAbsent(request.getCompanyId(), ignored -> new MutableSummary());
            summary.openCount++;
            if (isAfter(request.getUpdatedAt(), summary.latestRequestAt)) {
                summary.latestRequestAt = request.getUpdatedAt(); summary.latestFilialTitle = request.getFilialTitle();
            }
            if (request.getStatus() == NextOrderRequestStatus.FAILED) {
                summary.failedCount++;
                if (request.getErrorMessage() != null && !request.getErrorMessage().isBlank()
                        && isAfter(request.getUpdatedAt(), summary.latestErrorAt)) {
                    summary.latestErrorAt = request.getUpdatedAt(); summary.latestError = request.getErrorMessage();
                }
            }
        }
        Map<Long, CompanySummary> result = new HashMap<>();
        summaries.forEach((id, value) -> result.put(id,
                new CompanySummary(value.openCount, value.failedCount, value.latestFilialTitle, value.latestError)));
        return Map.copyOf(result);
    }

    @Override
    public List<CreatedOrder> createdOrdersForSources(Collection<Long> sourceOrderIds) {
        if (sourceOrderIds == null || sourceOrderIds.isEmpty()) return List.of();
        return requests.findCreatedOrderViews(sourceOrderIds).stream().map(row -> new CreatedOrder(
                row.getSourceOrderId(), row.getOrderId(), row.getCompanyTitle(), row.getFilialTitle(), row.getStatusTitle())).toList();
    }

    @Override
    public boolean hasOpenRequest(Long sourceOrderId) {
        return sourceOrderId != null && requests.existsBySourceOrderIdAndStatusIn(sourceOrderId, OPEN);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockCreatedOrigin(Long createdOrderId) {
        if (createdOrderId == null) return false;
        // Preserve Order -> requests(id ASC) -> billing locks. No new transaction
        // and no stateful entity crosses the module boundary.
        return requests.findByCreatedOrderIdForUpdate(createdOrderId).stream().anyMatch(request ->
                request.getStatus() == NextOrderRequestStatus.CREATED && request.getCreatedOrder() != null
                        && Objects.equals(createdOrderId, request.getCreatedOrder().getId()));
    }

    private static boolean isAfter(LocalDateTime candidate, LocalDateTime current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }

    private static class MutableSummary {
        private int openCount;
        private int failedCount;
        private LocalDateTime latestRequestAt;
        private String latestFilialTitle;
        private LocalDateTime latestErrorAt;
        private String latestError;
    }
}
