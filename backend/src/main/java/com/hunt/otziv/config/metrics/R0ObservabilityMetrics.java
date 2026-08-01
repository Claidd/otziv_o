package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-cardinality operational metrics for security decisions and transaction outcomes.
 *
 * <p>The public API deliberately accepts only fixed enums for transaction tags and maps
 * worker-access strings to small allowlists. User, request, entity and external-system IDs
 * must never be added as tags here.</p>
 */
@Component
public class R0ObservabilityMetrics {

    static final String WORKER_ACCESS_DECISION = "otziv.worker.cellular.access.decision";
    static final String TRANSACTION_COMPLETION = "otziv.transaction.flow.completion";
    static final String TRANSACTION_CAUGHT_FAILURE = "otziv.transaction.flow.caught.failure";

    private static final Set<String> WORKER_MODES = Set.of("audit", "enforce");
    private static final Set<String> WORKER_REASONS = Set.of(
            "allowed",
            "non_cellular_network",
            "vpn_proxy_or_datacenter",
            "desktop_or_unknown_device",
            "unknown_network"
    );
    private static final Set<String> WORKER_SCOPES = Set.of(
            "nagul",
            "publish",
            "recovery",
            "bad",
            "protected_worker_action"
    );

    private final MeterRegistry meterRegistry;

    public R0ObservabilityMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordWorkerAccessDecision(
            String mode,
            boolean allowed,
            boolean wouldDeny,
            boolean blocked,
            String reason,
            String scope
    ) {
        String decision = allowed
                ? "allow"
                : blocked ? "deny" : wouldDeny ? "would_deny" : "audit_allow";
        safelyIncrement(
                WORKER_ACCESS_DECISION,
                "Worker cellular-access decisions without user or request identifiers",
                1,
                "mode", allowlisted(mode, WORKER_MODES),
                "decision", decision,
                "reason", allowlisted(reason, WORKER_REASONS),
                "scope", allowlisted(scope, WORKER_SCOPES)
        );
    }

    public void observeTransactionCompletion(TransactionFlow flow) {
        if (flow == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            recordTransactionCompletion(flow, "no_transaction");
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    String completion = switch (status) {
                        case TransactionSynchronization.STATUS_COMMITTED -> "committed";
                        case TransactionSynchronization.STATUS_ROLLED_BACK -> "rolled_back";
                        default -> "unknown";
                    };
                    recordTransactionCompletion(flow, completion);
                }
            });
        } catch (RuntimeException ignored) {
            // Metrics must never change the transaction or business outcome.
        }
    }

    public void recordCaughtFailure(TransactionFlow flow, CaughtFailureStage stage) {
        if (flow == null || stage == null) {
            return;
        }
        safelyIncrement(
                TRANSACTION_CAUGHT_FAILURE,
                "Caught dependency failures in transaction-sensitive business flows",
                1,
                "flow", flow.tagValue,
                "stage", stage.tagValue
        );
    }

    private void recordTransactionCompletion(TransactionFlow flow, String completion) {
        safelyIncrement(
                TRANSACTION_COMPLETION,
                "Completion status of transaction-sensitive business flows",
                1,
                "flow", flow.tagValue,
                "completion", completion
        );
    }

    private void safelyIncrement(
            String name,
            String description,
            double amount,
            String... tags
    ) {
        try {
            Counter.builder(name)
                    .description(description)
                    .tags(tags)
                    .register(meterRegistry)
                    .increment(amount);
        } catch (RuntimeException ignored) {
            // Observability is passive: registry failures cannot affect production behavior.
        }
    }

    private String allowlisted(String value, Set<String> allowed) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return allowed.contains(normalized) ? normalized : "other";
    }

    public enum TransactionFlow {
        ORDER_PAYMENT("order_payment"),
        COMMON_INVOICE_CLOSE("common_invoice_close");

        private final String tagValue;

        TransactionFlow(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    public enum CaughtFailureStage {
        CLOSE_ORDER("close_order"),
        OPEN_NEXT_ORDER("open_next_order");

        private final String tagValue;

        CaughtFailureStage(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
