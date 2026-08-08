package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import java.time.LocalDateTime;
import java.util.List;

public record ContractorPaymentAllocationJournalItemResponse(
        Long id,
        int attemptNo,
        ContractorAllocationMode mode,
        ContractorAllocationSourceType sourceType,
        Long sourceId,
        Long orderId,
        Long commonInvoiceId,
        ContractorRecipientType recipientType,
        Long recipientProfileId,
        Long recipientUserId,
        String recipientName,
        Long currentWorkerId,
        Long currentManagerId,
        long amountKopecks,
        long confirmedKopecks,
        long returnedKopecks,
        ContractorAllocationStatus status,
        ContractorRoutingDecisionReason routingDecisionReason,
        ContractorRoutingDecisionReason specialistRejectionReason,
        ContractorRoutingDecisionReason managerRejectionReason,
        Long availableBeforeKopecks,
        LocalDateTime reservedAt,
        LocalDateTime clientReportedAt,
        LocalDateTime confirmedAt,
        LocalDateTime releasedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String reason,
        int reconcileAttempts,
        LocalDateTime reconcileNextRetryAt,
        String reconcileLastErrorCode,
        List<ContractorPaymentAllocationEventResponse> events
) {
}
