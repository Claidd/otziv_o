package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.*;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommonInvoiceFutureReminderRecovery {
    private final CommonInvoiceReminderRecoveryStore store;
    private final ClientMessageDelivery delivery;
    private final SchedulerLeaseService leases;
    private long afterInvoice;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<LocalDateTime> recover(long invoiceId) {
        var checked = store.inspect(invoiceId);
        if (checked.isEmpty()) return store.existing(invoiceId);
        if (!delivery.hasSettledUncertainOperation(checked.get().queue().operationId())) return Optional.empty();
        return store.restore(checked.get());
    }

    @Scheduled(fixedDelayString = "${common-billing.periodic-recovery.delay-ms:60000}", initialDelay = 45000,
            scheduler = "commonInvoiceReminderRecoveryScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recoverFutureReminders() {
        var acquired = leases.tryAcquire("common-invoice-future-reminder-recovery", Duration.ofMinutes(5));
        if (acquired.isEmpty()) return;
        var lease = acquired.get();
        try {
            var candidates = store.candidates(afterInvoice, 20);
            if (candidates.isEmpty()) { afterInvoice = 0; return; }
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            for (Long id : candidates) {
                if (System.nanoTime() >= deadline) break;
                afterInvoice = id;
                try {
                    var result = recover(id);
                    if (result.isPresent()) log.info("Common invoice future reminder restored: invoiceId={}, next={}", id, result.get());
                } catch (RuntimeException held) {
                    log.warn("Common invoice future reminder held: invoiceId={}, errorType={}", id, held.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Common invoice periodic recovery scan unavailable: errorType={}", unavailable.getClass().getSimpleName());
        } finally { leases.release(lease); }
    }
}
