package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.api.PeriodicReminderRestart;
import com.hunt.otziv.common_billing.model.*;
import com.hunt.otziv.common_billing.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retires only an ordinary reminder command and preserves its unknown outcome before planning a new cycle. */
@Service
@RequiredArgsConstructor
public class CommonInvoiceReminderRecoveryStore {
    static final String REASON = "periodic_invoice_next_cycle_scheduled_unknown_preserved";
    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceInitializationService initialization;
    private final CommonInvoiceSettlementService settlement;
    private final CommonInvoiceMessageQueue queue;
    private final JdbcTemplate jdbc;
    private final PeriodicReminderRestart planner;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher reminderEvents;

    record QueueSource(String operationId, String hash, LocalDateTime createdAt) { }
    record InvoiceSource(CommonInvoiceStatus status, long amount, long paid, LocalDateTime sent,
            LocalDateTime lastReminder, LocalDateTime nextReminder, LocalDateTime updated, Long accountId,
            CommonInvoiceInitializationService.ReminderRecoveryIdentity identity) { }
    record Check(Long invoiceId, QueueSource queue, PreparedCommonInvoiceMessage prepared,
            InvoiceSource invoice, LocalDateTime checkedAt) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public List<Long> candidates(long afterInvoice, int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT invoice_id FROM common_invoice_message_queue
                WHERE invoice_id>? AND state='UNKNOWN' AND created_at<=?
                  AND (lease_until IS NULL OR lease_until<?) ORDER BY invoice_id LIMIT ?
                """, Long.class, afterInvoice, LocalDateTime.now().minusMinutes(5), LocalDateTime.now(), Math.max(1, Math.min(20, limit)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public Optional<Check> inspect(long invoiceId) {
        return check(invoices.findByIdWithAccount(invoiceId).orElse(null), false);
    }

    private Optional<Check> check(CommonInvoice invoice, boolean lock) {
        LocalDateTime now = LocalDateTime.now();
        if (invoice == null || !settlement.automaticPaymentRemindersEnabled()
                || !CommonInvoiceDeliveryService.REMINDER_STATUSES.contains(invoice.getStatus())
                || invoice.getClosedAt() != null || invoice.getSentAt() == null
                || invoice.getAmountKopecks() <= 0 || invoice.getPaidKopecks() < 0 || invoice.getPaidKopecks() >= invoice.getAmountKopecks()
                || invoice.isPaymentMessageConfirmed() || !"REMINDER".equals(invoice.getPaymentMessageOperationKind())
                || !Set.of("message_send_in_progress", "message_send_stale", "message_send_unknown")
                    .contains(invoice.getLastError() == null ? "" : invoice.getLastError().trim())
                || invoice.getAccount() == null || !invoice.getAccount().isEnabled()) return Optional.empty();
        var pending = jdbc.query("""
                SELECT operation_id,state,created_at,lease_until,SHA2(envelope_ciphertext,256) envelope_hash
                FROM common_invoice_message_queue WHERE invoice_id=? AND state NOT IN ('SENT','SUPERSEDED')
                ORDER BY created_at LIMIT 2
                """ + (lock ? " FOR UPDATE" : ""), (r,n) -> {
            var lease = r.getTimestamp("lease_until");
            if (!"UNKNOWN".equals(r.getString("state")) || lease != null && lease.toLocalDateTime().isAfter(now)) return null;
            return new QueueSource(r.getString("operation_id"), r.getString("envelope_hash"), r.getTimestamp("created_at").toLocalDateTime());
        }, invoice.getId());
        if (pending.size() != 1 || pending.getFirst() == null) return Optional.empty();
        var source = pending.getFirst();
        if (source.createdAt().isAfter(now.minusMinutes(5)) || !Objects.equals(source.operationId(), invoice.getPaymentMessageOperationId()))
            return Optional.empty();
        var prepared = queue.snapshot(source.operationId());
        if (!prepared.reminder() || prepared.manual() || prepared.paymentRouteChanged() || prepared.alreadyConfirmed()
                || prepared.remainingKopecks() <= 0 || prepared.remainingKopecks() != invoice.getAmountKopecks() - invoice.getPaidKopecks())
            return Optional.empty();
        var identity = initialization.reminderRecoveryIdentity(invoice).orElse(null);
        if (identity == null || !Objects.equals(identity.target(), prepared.chatCompany())
                || !Objects.equals(identity.clientId(), prepared.managerClientId()) || !Objects.equals(identity.groupId(), prepared.groupId())) return Optional.empty();
        return Optional.of(new Check(invoice.getId(), source, prepared, new InvoiceSource(invoice.getStatus(), invoice.getAmountKopecks(),
                invoice.getPaidKopecks(), invoice.getSentAt(), invoice.getLastReminderAt(), invoice.getNextReminderAt(), invoice.getUpdatedAt(),
                invoice.getAccount().getId(), identity), now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15)
    public Optional<LocalDateTime> restore(Check checked) {
        if (checked.checkedAt().isBefore(LocalDateTime.now().minusMinutes(5))) return Optional.empty();
        var invoice = settlement.lockedInvoice(checked.invoiceId()).orElse(null);
        var fresh = check(invoice, true).orElse(null);
        if (fresh == null || !Objects.equals(checked.queue(), fresh.queue()) || !Objects.equals(checked.prepared(), fresh.prepared())
                || !Objects.equals(checked.invoice(), fresh.invoice())) return Optional.empty();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime contact = invoice.getLastReminderAt() != null && invoice.getLastReminderAt().isAfter(now)
                ? invoice.getLastReminderAt() : now;
        LocalDateTime next = planner.futurePaymentReminderAt(contact);
        if (next == null || next.isBefore(now.plusDays(2))) throw new IllegalStateException("Future reminder interval is missing");
        jdbc.update("""
                INSERT INTO client_reminder_restart_audit
                  (request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,new_generation,next_attempt_at)
                SELECT ?,'COMMON_INVOICE',q.invoice_id,q.operation_id,'system:common-invoice-periodic-recovery',?,
                  JSON_OBJECT('envelope_ciphertext',q.envelope_ciphertext,'state',q.state,'attempts',q.attempts,
                    'error_code',q.error_code,'created_at',q.created_at,'next_attempt_at',q.next_attempt_at,
                    'invoice_status',?,'amount_kopecks',?,'paid_kopecks',?,'invoice_last_error',?,
                    'invoice_last_reminder',?,'invoice_next_reminder',?),NULL,?
                FROM common_invoice_message_queue q WHERE operation_id=?
                """, "future:" + fresh.queue().operationId(), REASON, invoice.getStatus().name(), invoice.getAmountKopecks(),
                invoice.getPaidKopecks(), invoice.getLastError(), invoice.getLastReminderAt(), invoice.getNextReminderAt(), next, fresh.queue().operationId());
        jdbc.update("""
                UPDATE common_invoice_message_queue SET state='SUPERSEDED',error_code='future_periodic_reminder_scheduled',
                  claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND state='UNKNOWN'
                """, fresh.queue().operationId());
        invoice.setPaymentMessageOperationId(null); invoice.setPaymentMessageOperationKind(null);
        invoice.setPaymentMessageConfirmed(false); invoice.setPaymentMessageChannel(null);
        invoice.setLastError(null); invoice.setNextReminderAt(next);
        invoices.save(invoice);
        if (reminderEvents != null) reminderEvents.publishEvent(new com.hunt.otziv.client_messages.api.ClientReminderQueueRestored(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Irkutsk"))));
        return Optional.of(next);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 10)
    public Optional<LocalDateTime> existing(long invoiceId) {
        var invoice = invoices.findByIdWithAccount(invoiceId).orElse(null);
        if (invoice == null || invoice.getClosedAt() != null || invoice.getAmountKopecks() <= invoice.getPaidKopecks()
                || invoice.getPaymentMessageOperationId() != null || invoice.getLastError() != null
                || invoice.getNextReminderAt() == null || !invoice.getNextReminderAt().isAfter(LocalDateTime.now())
                || !CommonInvoiceDeliveryService.REMINDER_STATUSES.contains(invoice.getStatus())) return Optional.empty();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM client_reminder_restart_audit WHERE subject_type='COMMON_INVOICE' AND subject_id=? AND reason=? AND next_attempt_at=?",
                Integer.class, invoiceId, REASON, invoice.getNextReminderAt());
        return count != null && count > 0 ? Optional.of(invoice.getNextReminderAt()) : Optional.empty();
    }
}
