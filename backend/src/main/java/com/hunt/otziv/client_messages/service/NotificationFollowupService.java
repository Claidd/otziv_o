package com.hunt.otziv.client_messages.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.api.NotificationFollowup;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.repository.NotificationFollowupRepository;
import com.hunt.otziv.client_messages.repository.NotificationFollowupRepository.Row;
import com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations;
import com.hunt.otziv.whatsapp.service.WhatsAppDeliveryRouteRecovery;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One separately audited informational follow-up. UNKNOWN is never reset or called unsent. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationFollowupService implements NotificationFollowup {
    private static final String PREFIX = "notification-followup-v1:";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final NotificationFollowupRepository records;
    private final ClientMessageTransactionRunner transactions;
    private final ClientMessageDelivery delivery;
    private final WhatsAppBusinessOperations businessOperations;
    private final WhatsAppDeliveryRouteRecovery routes;
    @Value("${client-messages.authorized-followup.enabled:true}") private boolean enabled = true;
    @Value("${client-messages.authorized-followup.delay:PT15M}") private Duration delay = Duration.ofMinutes(15);
    @Value("${client-messages.authorized-followup.gap-seconds:60}") private int gapSeconds = 60;
    public record Envelope(Request request, String groupId) { }

    @Override public Outcome recover(Request request, BooleanSupplier stillAuthorized) {
        requireNoTransaction();
        if (!enabled || !valid(request)) return pending(request, "authorized_followup_unavailable");
        try {
            if (!stillAuthorized.getAsBoolean()) return pending(request,"followup_source_changed");
            Row existing = records.find(request.originalOperationId()).orElse(null);
            if (existing != null) {
                requireIdentity(existing, request);
                if ("STARTED".equals(existing.state()) || "UNKNOWN".equals(existing.state())) existing = reconcile(existing);
                if ("SENT".equals(existing.state())) return stillAuthorized.getAsBoolean() ? confirmed(existing) : pending(request, "followup_source_changed");
                if (!"PENDING".equals(existing.state())) return pending(request,
                        "UNKNOWN".equals(existing.state()) || "STARTED".equals(existing.state()) ? "authorized_followup_unknown" : "followup_closed");
            }
            var original = delivery.recoverRecordedOutcomeFromChat(request.originalOperationId(), request.preparedAt());
            if (confirmed(original)) {
                transactions.runInNewTransaction(() -> records.cancelUnstarted(request.originalOperationId()));
                return new Outcome(request.originalOperationId(), request.originalOperationId(), false, original);
            }
            // No missing ledger, running send or Telegram/MAX fence can be used as permission to repeat.
            var frozen = businessOperations.findFrozen(request.originalOperationId()).orElse(null);
            if (frozen == null || !"send-group".equals(frozen.kind())
                    || !delivery.hasSettledUncertainOperation(request.originalOperationId()) || !stillAuthorized.getAsBoolean())
                return pending(request, "authorized_followup_waiting_for_original");
            if (existing == null) {
                String group = routes.reconfirmGroup(request.target().companyId(), request.target().urlChat(), request.clientId());
                if (group == null || !stillAuthorized.getAsBoolean()) return pending(request, "followup_route_unavailable");
                String payload = JSON.writeValueAsString(new Envelope(request, group));
                existing = transactions.callInPreparationTransaction(() -> records.reserve(request.originalOperationId(),
                        operationId(request.originalOperationId()), request.kind().name(), request.sourceId(), request.sourceVersion(), payload));
                requireIdentity(existing, request);
            }
            if (!"PENDING".equals(existing.state())) return pending(request, "authorized_followup_unknown");
            Envelope saved = JSON.readValue(existing.payload(), Envelope.class);
            String currentGroup = routes.reconfirmGroup(saved.request().target().companyId(), saved.request().target().urlChat(), saved.request().clientId());
            if (!Objects.equals(saved.groupId(), currentGroup) || !stillAuthorized.getAsBoolean()) return pending(request, "followup_route_unavailable");
            int hold = (int) Math.max(300, Math.min(86400, delay.toSeconds()));
            boolean claimed = transactions.callInPreparationTransaction(() -> records.claim(request.originalOperationId(), hold, Math.max(1,gapSeconds)));
            if (!claimed) return pending(request, "authorized_followup_pending");
            boolean authorizedAfterClaim;
            try { authorizedAfterClaim=stillAuthorized.getAsBoolean(); }
            catch(RuntimeException unavailable) { authorizedAfterClaim=false; }
            if (!authorizedAfterClaim) {
                transactions.runInNewTransaction(() -> records.finish(request.originalOperationId(),false,true,null,"followup_authorization_changed"));
                return pending(request, "followup_source_changed");
            }
            ClientMessageSendResult result;
            try {
                result = delivery.deliverWithOperationId(saved.request().target(),saved.request().clientId(),saved.groupId(),
                        saved.request().message(),null,operationId(request.originalOperationId()));
            } catch (RuntimeException unknown) { result = ClientMessageOperationFence.unknown(); }
            var outcome = result;
            transactions.runInNewTransaction(() -> records.finish(request.originalOperationId(),confirmed(outcome),
                    ClientMessageDelivery.isKnownUnsent(outcome),outcome == null ? null : outcome.messageId(),
                    outcome == null ? "empty_result" : safeCode(outcome.errorCode())));
            Row current = records.find(request.originalOperationId()).orElseThrow();
            return "SENT".equals(current.state()) && stillAuthorized.getAsBoolean() ? confirmed(current)
                    : pending(request,"PENDING".equals(current.state()) ? "authorized_followup_pending" : "authorized_followup_unknown");
        } catch (Exception held) {
            log.warn("Authorized notification follow-up held: originalOperation={}, errorType={}",request.originalOperationId(),held.getClass().getSimpleName());
            return pending(request,"authorized_followup_unknown");
        }
    }

    @Override public void markApplied(Outcome confirmed) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || confirmed == null || !confirmed.followup()
                || !confirmed(confirmed.delivery()) || !records.markApplied(confirmed.originalOperationId(),confirmed.operationId(),confirmed.delivery().messageId()))
            throw new IllegalStateException("Follow-up business finalization requires its durable receipt and a transaction");
    }

    /** Receipt lookup only, including follow-ups whose original business task closed in the meantime. */
    @Scheduled(fixedDelayString="${client-messages.authorized-followup.receipt-delay-ms:300000}", initialDelay=180000,
            scheduler="periodicReminderRecoveryScheduler")
    public void reconcileReceipts() {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        try {
            for (String original : records.receiptCandidates()) {
                if (System.nanoTime() >= deadline) break;
                try {
                    if (transactions.callInNewTransaction(() -> records.reserveReceiptCheck(original)))
                        records.find(original).ifPresent(this::reconcile);
                } catch (RuntimeException unavailable) { log.warn("Follow-up receipt reconciliation deferred: originalOperation={}", original); }
            }
        } catch (RuntimeException unavailable) { log.warn("Follow-up receipt scan unavailable: errorType={}",unavailable.getClass().getSimpleName()); }
    }
    private Row reconcile(Row row) {
        if ("PENDING".equals(row.state())) {
            if (confirmed(delivery.recordedOutcome(row.originalId())))
                transactions.runInNewTransaction(()->records.cancelUnstarted(row.originalId()));
            return records.find(row.originalId()).orElseThrow();
        }
        var receipt = delivery.recoverRecordedOutcomeFromChat(row.operationId(),null);
        if (confirmed(receipt)) transactions.runInNewTransaction(() -> records.finish(row.originalId(),true,false,receipt.messageId(),null));
        else transactions.runInNewTransaction(()->records.holdStaleDispatch(row.originalId()));
        return records.find(row.originalId()).orElseThrow();
    }
    private Outcome confirmed(Row row) {
        if (row.messageId()==null || row.messageId().isBlank() || row.messageId().length()>512)
            throw new IllegalStateException("Follow-up receipt is missing");
        return new Outcome(row.originalId(),row.operationId(),true,ClientMessageSendResult.sent("WhatsApp",row.messageId()));
    }
    private Outcome pending(Request r, String code) {
        return new Outcome(r == null ? "" : r.originalOperationId(),r == null ? "" : operationId(r.originalOperationId()),true,
                ClientMessageSendResult.failed(code,"Автоматический повтор учитывает риск дубля; результат проверяется отдельно, третья отправка запрещена"));
    }
    private void requireIdentity(Row row, Request r) {
        if (!row.operationId().equals(operationId(r.originalOperationId())) || row.sourceId()!=r.sourceId()
                || !row.kind().equals(r.kind().name()) || !row.version().equals(r.sourceVersion()))
            throw new IllegalStateException("Follow-up source changed");
    }
    private static boolean valid(Request r) {
        return r!=null && r.kind()!=null && r.sourceId()>0 && r.originalOperationId()!=null
                && r.originalOperationId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") && !r.originalOperationId().startsWith(PREFIX)
                && r.sourceVersion()!=null && r.sourceVersion().matches("[a-f0-9]{64}") && r.preparedAt()!=null
                && r.target()!=null && r.target().companyId()!=null && r.clientId()!=null && !r.clientId().isBlank()
                && r.message()!=null && !r.message().isBlank() && r.message().length()<=4000 && r.sourceSnapshot()!=null;
    }
    private static boolean confirmed(ClientMessageSendResult r) { return r!=null && r.sent() && r.messageId()!=null && !r.messageId().isBlank() && r.messageId().length()<=512; }
    private static String operationId(String original) { return PREFIX + NotificationFollowup.fingerprint(String.valueOf(original)); }
    private static String safeCode(String code) { return code==null ? "unconfirmed_result" : code.substring(0,Math.min(100,code.length())); }
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Follow-up network calls require no business transaction");
    }
}
