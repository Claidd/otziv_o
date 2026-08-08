package com.hunt.otziv.contractor_payments.dto;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import java.time.LocalDateTime;

public record ContractorPaymentAllocationEventResponse(
        Long id,
        ContractorAllocationEventType eventType,
        long amountKopecks,
        ContractorAllocationStatus statusBefore,
        ContractorAllocationStatus statusAfter,
        ContractorRoutingDecisionReason routingDecisionReason,
        ContractorRoutingDecisionReason specialistRejectionReason,
        ContractorRoutingDecisionReason managerRejectionReason,
        LocalDateTime effectiveAt,
        String reason,
        String actor,
        LocalDateTime createdAt
) {
}
