package com.hunt.otziv.p_products.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Orders owns request state, aggregation and locking; callers receive scalar values only. */
public interface NextOrderRequests {
    Map<Long, CompanySummary> companySummaries(Collection<Long> companyIds);
    List<CreatedOrder> createdOrdersForSources(Collection<Long> sourceOrderIds);
    boolean hasOpenRequest(Long sourceOrderId);

    /** Caller must already hold the canonical created Order lock in the same transaction. */
    boolean lockCreatedOrigin(Long createdOrderId);

    record CompanySummary(int openCount, int failedCount, String latestFilialTitle, String latestError) {}
    record CreatedOrder(Long sourceOrderId, Long orderId, String companyTitle, String filialTitle, String statusTitle) {}
}
