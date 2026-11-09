package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualPaymentTaskRoutingFilterTest {
    @Test void refusingTheBankLinkCanKeepAnOwnerTaskOnlyWhenRequestingNumbers() {
        var task = new ManualPaymentTask();
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.OWNER);
        task.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        assertTrue(ManualPaymentTaskRoutingFilter.NUMBER.allows(task));
        assertFalse(ManualPaymentTaskRoutingFilter.otherRecipient(null, null).allows(task));
        task.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        assertFalse(ManualPaymentTaskRoutingFilter.NUMBER.allows(task));
        assertTrue(ManualPaymentTaskRoutingFilter.ANY.allows(task));
    }

    @Test void changingRecipientExcludesEveryTaskOfThePreviousEmployee() {
        var user = new User();
        user.setId(7L);
        var profile = new ContractorPaymentProfile();
        profile.setUser(user);
        var task = new ManualPaymentTask();
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.SPECIALIST);
        task.setAccountingTargetProfile(profile);
        task.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        var other = ManualPaymentTaskRoutingFilter.otherRecipient(7L, null);
        assertFalse(other.allows(task));
        user.setId(8L);
        assertTrue(other.allows(task));
    }
}
