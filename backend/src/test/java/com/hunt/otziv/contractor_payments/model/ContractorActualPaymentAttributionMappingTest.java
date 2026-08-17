package com.hunt.otziv.contractor_payments.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

class ContractorActualPaymentAttributionMappingTest {

    @Test
    void externalTaskRecipientTypesAreNullableLikeV252Schema() throws Exception {
        Column original = ContractorActualPaymentAttribution.class
                .getDeclaredField("originalRecipientType").getAnnotation(Column.class);
        Column actual = ContractorActualPaymentAttribution.class
                .getDeclaredField("actualRecipientType").getAnnotation(Column.class);

        assertTrue(original.nullable());
        assertTrue(actual.nullable());
    }
}
