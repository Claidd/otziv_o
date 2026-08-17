package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Slf4j
public class PaymentLinkReturnOutboxWorker {

    private static final int BATCH_SIZE = 25;

    private final PaymentLinkReturnOutboxTransactionService transactions;
    private final ContractorPaymentShadowService contractorPaymentShadowService;
    private final PaymentReturnOrderRecoveryService orderRecoveryService;
    private final com.hunt.otziv.client_messages.service.ScheduledClientMessageService clientMessageService;

    public PaymentLinkReturnOutboxWorker(
            PaymentLinkReturnOutboxTransactionService transactions,
            ContractorPaymentShadowService contractorPaymentShadowService
    ) {
        this(transactions, contractorPaymentShadowService, null, null);
    }

    @Autowired
    public PaymentLinkReturnOutboxWorker(
            PaymentLinkReturnOutboxTransactionService transactions,
            ContractorPaymentShadowService contractorPaymentShadowService,
            PaymentReturnOrderRecoveryService orderRecoveryService,
            com.hunt.otziv.client_messages.service.ScheduledClientMessageService clientMessageService
    ) {
        this.transactions = transactions;
        this.contractorPaymentShadowService = contractorPaymentShadowService;
        this.orderRecoveryService = orderRecoveryService;
        this.clientMessageService = clientMessageService;
    }

    @Scheduled(
            fixedDelayString = "${otziv.payments.return-outbox.fixed-delay-ms:5000}",
            initialDelayString = "${otziv.payments.return-outbox.initial-delay-ms:30000}"
    )
    public void processDue() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            var claim = transactions.claimNext();
            if (claim.isEmpty()) {
                return;
            }
            deliver(claim.get());
        }
    }

    private void deliver(PaymentLinkReturnOutboxRepository.Claim claim) {
        try {
            contractorPaymentShadowService.reconcilePaymentLinkId(claim.paymentLinkId());
            if (orderRecoveryService != null && clientMessageService != null) {
                PaymentLinkStatus observedStatus = null;
                try {
                    observedStatus = PaymentLinkStatus.valueOf(claim.observedStatus());
                } catch (RuntimeException ignored) {
                    // Unknown historical status is accounting-only and must
                    // not prevent the outbox row from being acknowledged.
                }
                orderRecoveryService.reopenAfterFullReturn(
                        new PaymentReturnOrderRecoveryService.PaymentLinkReturnOutboxClaim(
                                claim.paymentLinkId(),
                                observedStatus
                        )
                ).ifPresent(clientMessageService::enqueuePaymentReminderAfterFullReturn);
            }
            transactions.succeeded(claim);
        } catch (RuntimeException failure) {
            log.warn("Return reconciliation will retry: linkId={}, attempt={}, code={}",
                    claim.paymentLinkId(), claim.attemptCount(),
                    failure.getClass().getSimpleName());
            transactions.retry(claim, failure);
        }
    }
}
