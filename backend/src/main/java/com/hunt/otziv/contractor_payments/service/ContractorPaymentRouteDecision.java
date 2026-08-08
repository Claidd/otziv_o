package com.hunt.otziv.contractor_payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import java.util.Objects;

record ContractorPaymentRouteDecision(
        ContractorPaymentProfile recipient,
        ContractorRoutingDecisionReason decisionReason,
        ContractorRoutingDecisionReason specialistRejectionReason,
        ContractorRoutingDecisionReason managerRejectionReason
) {
    ContractorPaymentRouteDecision {
        Objects.requireNonNull(decisionReason, "decisionReason");
    }

    static ContractorPaymentRouteDecision selected(
            ContractorPaymentProfile recipient,
            ContractorRoutingDecisionReason decisionReason,
            ContractorRoutingDecisionReason specialistRejectionReason
    ) {
        return new ContractorPaymentRouteDecision(
                Objects.requireNonNull(recipient, "recipient"),
                decisionReason,
                specialistRejectionReason,
                null
        );
    }

    static ContractorPaymentRouteDecision owner(
            ContractorRoutingDecisionReason decisionReason,
            ContractorRoutingDecisionReason specialistRejectionReason,
            ContractorRoutingDecisionReason managerRejectionReason
    ) {
        return new ContractorPaymentRouteDecision(
                null,
                decisionReason,
                specialistRejectionReason,
                managerRejectionReason
        );
    }
}
