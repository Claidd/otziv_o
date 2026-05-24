package com.hunt.otziv.payments.dto;

import java.util.List;

public record UpdateManagerPaymentProfilesRequest(
        List<ManagerPaymentProfileAssignmentRequest> assignments
) {
}
