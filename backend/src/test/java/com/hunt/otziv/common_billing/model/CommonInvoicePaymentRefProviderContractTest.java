package com.hunt.otziv.common_billing.model;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CommonInvoicePaymentRefProviderContractTest {

    @Test
    void entityMapsTheImmutableProviderAttemptSnapshot() throws Exception {
        assertColumn("provider", String.class, "provider", 32, false);
        assertColumn("paymentProfileId", Long.class, "payment_profile_id", 255, true);
        assertColumn("providerOrderId", String.class, "provider_order_id", 64, true);
        assertColumn("providerPaymentId", String.class, "provider_payment_id", 64, true);
        assertColumn("providerMerchantId", String.class, "provider_merchant_id", 64, true);
        assertColumn("providerPaymentMode", String.class, "provider_payment_mode", 32, true);
        assertColumn("providerTestMode", Boolean.class, "provider_test_mode", 255, true);
        assertColumn("providerStatus", String.class, "provider_status", 32, true);
        assertColumn("providerPaymentUrl", String.class, "provider_payment_url", 1024, true);
        assertColumn("providerExpiresAt", LocalDateTime.class, "provider_expires_at", 255, true);

        assertThat(new CommonInvoicePaymentRef().getProvider()).isEqualTo("T_BANK");
    }

    @Test
    void entityDeclaresProviderScopedExternalIdentityUniqueness() {
        Table table = CommonInvoicePaymentRef.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();

        Map<String, Set<String>> constraints = Arrays.stream(table.uniqueConstraints())
                .collect(Collectors.toMap(
                        UniqueConstraint::name,
                        constraint -> Set.of(constraint.columnNames())
                ));

        assertThat(constraints)
                .containsEntry(
                        "uk_common_invoice_payment_ref_provider_order",
                        Set.of("provider", "provider_order_id")
                )
                .containsEntry(
                        "uk_common_invoice_payment_ref_provider_payment",
                        Set.of("provider", "provider_payment_id")
                );
    }

    private static void assertColumn(
            String fieldName,
            Class<?> expectedType,
            String expectedColumnName,
            int expectedLength,
            boolean expectedNullable
    ) throws Exception {
        Field field = CommonInvoicePaymentRef.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(field.getType()).isEqualTo(expectedType);
        assertThat(column).as("@Column on %s", fieldName).isNotNull();
        String mappedName = column.name().isBlank() ? fieldName : column.name();
        assertThat(mappedName).isEqualTo(expectedColumnName);
        assertThat(column.length()).isEqualTo(expectedLength);
        assertThat(column.nullable()).isEqualTo(expectedNullable);
    }
}
