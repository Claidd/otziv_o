package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import java.util.Objects;

/** Constraints requested by the customer, applied before taking any reservation. */
public record ManualPaymentTaskRoutingFilter(boolean numberOnly, boolean excludeOwner,
        Long excludedUserId, Long excludedTaskId, String excludedTransferNumber) {
    public static final ManualPaymentTaskRoutingFilter ANY = new ManualPaymentTaskRoutingFilter(false, false, null, null, "");
    public static final ManualPaymentTaskRoutingFilter NUMBER = new ManualPaymentTaskRoutingFilter(true, false, null, null, "");

    public static ManualPaymentTaskRoutingFilter otherRecipient(Long userId, ManualPaymentTaskRouteSnapshot previousTask) {
        boolean owner = previousTask == null ? userId == null
                : previousTask.accountingTargetKind() == ManualPaymentTaskAccountingTargetKind.OWNER;
        return new ManualPaymentTaskRoutingFilter(true, owner, userId, previousTask == null ? null : previousTask.taskId(),
                previousTask == null ? "" : digits(previousTask.manualPhone()));
    }

    private static String digits(String number) {
        return number == null ? "" : number.replaceAll("[^0-9]", "");
    }

    boolean allows(ManualPaymentTask task) {
        if (numberOnly && task.getManualPaymentType() == ManualPaymentType.EXTERNAL_LINK) return false;
        if (excludeOwner && task.getAccountingTargetKind() == ManualPaymentTaskAccountingTargetKind.OWNER) return false;
        if (excludedTaskId != null && excludedTaskId.equals(task.getId())) return false;
        if (!excludedTransferNumber.isEmpty() && excludedTransferNumber.equals(digits(task.getManualPhone()))) return false;
        var profile = task.getAccountingTargetProfile();
        return excludedUserId == null || profile == null || profile.getUser() == null
                || !Objects.equals(excludedUserId, profile.getUser().getId());
    }
}
