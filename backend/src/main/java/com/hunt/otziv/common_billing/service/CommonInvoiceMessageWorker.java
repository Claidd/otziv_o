package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommonInvoiceMessageWorker {
    private final CommonInvoiceMessageQueue queue;
    private final CommonInvoiceDeliveryService invoices;
    private final ClientMessageDelivery delivery;
    /** Autoresponder admission for automatic reminders (field-injected; absent in narrow tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.client_messages.api.BackgroundDispatchBudget dispatchBudget;

    @Scheduled(fixedDelayString = "${common-billing.message-queue-delay-ms:5000}", scheduler = "commonInvoiceMessageScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tick() {
        for (int i = 0; i < 5; i++) {
            var next = queue.claim();
            if (next.isEmpty()) return;
            var claim = next.get();
            try {
                var prepared = queue.snapshot(claim.operationId());
                ClientMessageSendResult result;
                boolean paused = false;
                if (!claim.mayDispatch()) {
                    // A restarted or timed-out claim can only consult an existing receipt.
                    result = delivery.recordedOutcome(claim.operationId());
                    if (result == null || (!result.sent() && !ClientMessageDelivery.isKnownUnsent(result))) {
                        result = delivery.recoverRecordedOutcomeFromChat(claim.operationId(), queue.preparedAt(claim.operationId()));
                    }
                } else if (!invoices.messageDeliveryEnabled()) {
                    paused = true;
                    result = ClientMessageSendResult.failed("live_disabled", "Delivery paused");
                } else if (!invoices.queuedMessageMayDispatch(prepared)) {
                    // Business state changed. Hold for review; never send obsolete billing text.
                    result = ClientMessageSendResult.failed("invoice_changed", "Delivery requires review");
                } else {
                    var admission = automaticReminderAdmission(prepared);
                    if (admission != null && !admission.allowed()) {
                        // Pause, master switch, business window, shared daily limit or interval:
                        // keep the frozen message queued and retry later; nothing was sent.
                        paused = true;
                        result = ClientMessageSendResult.failed(admission.code(), admission.reason());
                    } else {
                        result = invoices.sendPreparedPaymentMessage(prepared);
                    }
                }
                invoices.finishQueuedMessage(claim, prepared, result, paused);
            } catch (RuntimeException failure) {
                // Leave the lease/barrier committed. Recovery is receipt-only after expiry.
                log.warn("Common invoice delivery held: invoiceId={}, errorType={}", claim.invoiceId(), failure.getClass().getSimpleName());
            }
        }
    }

    private com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Admission automaticReminderAdmission(
            PreparedCommonInvoiceMessage prepared) {
        if (dispatchBudget == null || prepared == null || !prepared.reminder() || prepared.manual()
                || prepared.alreadyConfirmed() || prepared.invoiceId() == null) {
            return null;
        }
        return dispatchBudget.reserve(
                com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Purpose.PAYMENT_REMINDER,
                "COMMON_INVOICE",
                prepared.invoiceId(),
                prepared.chatCompany()
        );
    }
}
