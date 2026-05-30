package com.hunt.otziv.payments.dto;

import com.hunt.otziv.payments.model.ManualPaymentType;
import java.time.LocalDateTime;

public record ManualPaymentTaskResponse(
        Long id,
        Long managerId,
        String managerTitle,
        String username,
        Long paymentProfileId,
        String paymentProfileName,
        String status,
        String manualPaymentType,
        String manualPhone,
        String manualRecipientName,
        String manualPaymentUrl,
        String manualPaymentButtonLabel,
        long targetAmountKopecks,
        long reservedAmountKopecks,
        long confirmedAmountKopecks,
        long pendingAmountKopecks,
        long remainingAmountKopecks,
        long pendingCount,
        String comment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        boolean routable
) {
}
