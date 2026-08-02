package com.hunt.otziv.config.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.COMMON_INVOICE_CLOSE;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.ORDER_PAYMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R0ObservabilityMetricsTest {

    private static final Map<String, Set<String>> ALLOWED_TAG_KEYS = Map.of(
            R0ObservabilityMetrics.WORKER_ACCESS_DECISION, Set.of("mode", "decision", "reason", "scope"),
            R0ObservabilityMetrics.TRANSACTION_COMPLETION, Set.of("flow", "completion"),
            R0ObservabilityMetrics.TRANSACTION_CAUGHT_FAILURE, Set.of("flow", "stage")
    );

    private static final Map<String, Set<String>> ALLOWED_TAG_VALUES = Map.of(
            "mode", Set.of("audit", "enforce", "other"),
            "decision", Set.of("allow", "audit_allow", "would_deny", "deny"),
            "reason", Set.of(
                    "allowed",
                    "non_cellular_network",
                    "vpn_proxy_or_datacenter",
                    "desktop_or_unknown_device",
                    "unknown_network",
                    "other"
            ),
            "scope", Set.of("nagul", "publish", "recovery", "bad", "protected_worker_action", "other"),
            "flow", Set.of("order_payment", "common_invoice_close"),
            "completion", Set.of("committed", "rolled_back", "unknown", "no_transaction"),
            "stage", Set.of("close_order", "open_next_order")
    );

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void recordsWouldDenyAndMapsUntrustedTagInputsToOther() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        R0ObservabilityMetrics metrics = new R0ObservabilityMetrics(registry);

        metrics.recordWorkerAccessDecision(
                "AUDIT",
                false,
                true,
                false,
                "NON_CELLULAR_NETWORK",
                "publish"
        );
        metrics.recordWorkerAccessDecision(
                "AUDIT,user=alice",
                false,
                false,
                false,
                "alice@example.com",
                "/orders/42"
        );

        assertEquals(1.0, registry.get(R0ObservabilityMetrics.WORKER_ACCESS_DECISION)
                .tags(
                        "mode", "audit",
                        "decision", "would_deny",
                        "reason", "non_cellular_network",
                        "scope", "publish"
                )
                .counter()
                .count());
        assertEquals(1.0, registry.get(R0ObservabilityMetrics.WORKER_ACCESS_DECISION)
                .tags(
                        "mode", "other",
                        "decision", "audit_allow",
                        "reason", "other",
                        "scope", "other"
                )
                .counter()
                .count());
        assertOnlyAllowlistedTags(registry);
    }

    @Test
    void recordsTransactionCompletionAfterSynchronizationAndCaughtFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        R0ObservabilityMetrics metrics = new R0ObservabilityMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        metrics.observeTransactionCompletion(ORDER_PAYMENT);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        metrics.recordCaughtFailure(COMMON_INVOICE_CLOSE, OPEN_NEXT_ORDER);

        assertEquals(1.0, registry.get(R0ObservabilityMetrics.TRANSACTION_COMPLETION)
                .tags("flow", "order_payment", "completion", "rolled_back")
                .counter()
                .count());
        assertEquals(1.0, registry.get(R0ObservabilityMetrics.TRANSACTION_CAUGHT_FAILURE)
                .tags("flow", "common_invoice_close", "stage", "open_next_order")
                .counter()
                .count());
        assertOnlyAllowlistedTags(registry);
    }

    @Test
    void recordsNoTransactionWithoutRegisteringSynchronization() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        R0ObservabilityMetrics metrics = new R0ObservabilityMetrics(registry);

        metrics.observeTransactionCompletion(COMMON_INVOICE_CLOSE);

        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertEquals(1.0, registry.get(R0ObservabilityMetrics.TRANSACTION_COMPLETION)
                .tags("flow", "common_invoice_close", "completion", "no_transaction")
                .counter()
                .count());
        assertOnlyAllowlistedTags(registry);
    }

    private void assertOnlyAllowlistedTags(SimpleMeterRegistry registry) {
        for (Meter meter : registry.getMeters()) {
            String meterName = meter.getId().getName();
            assertTrue(ALLOWED_TAG_KEYS.containsKey(meterName), () -> "Unexpected R0 meter: " + meterName);
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(
                        ALLOWED_TAG_KEYS.get(meterName).contains(tag.getKey()),
                        () -> "Unexpected tag key: " + tag.getKey()
                );
                assertTrue(
                        ALLOWED_TAG_VALUES.get(tag.getKey()).contains(tag.getValue()),
                        () -> "Unexpected tag value: " + tag.getKey() + '=' + tag.getValue()
                );
                String value = tag.getValue().toLowerCase();
                assertFalse(value.contains("alice"));
                assertFalse(value.contains("@"));
                assertFalse(value.contains("/42"));
            }
        }
    }
}
