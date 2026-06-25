package com.hunt.otziv.common_billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class CommonBillingReminderScheduler {

    private final CommonBillingService commonBillingService;

    @Scheduled(fixedDelayString = "${common-billing.reminders.fixed-delay:PT5M}")
    public void sendDueReminders() {
        int sent = commonBillingService.sendDueReminders(20);
        if (sent > 0) {
            log.info("Common billing reminders sent: {}", sent);
        }
    }

    @Scheduled(fixedDelayString = "${common-billing.payment-cancel.fixed-delay:PT1M}")
    public void cancelArchivedPaymentRefs() {
        int canceled = commonBillingService.cancelPendingArchivedPayments(20);
        if (canceled > 0) {
            log.info("Common billing archived payment cancel attempts: {}", canceled);
        }
    }
}
