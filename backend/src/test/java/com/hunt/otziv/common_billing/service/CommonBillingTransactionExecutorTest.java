package com.hunt.otziv.common_billing.service;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonBillingTransactionExecutorTest {

    @Test
    void requiredAlwaysOpensIndependentTransaction() throws Exception {
        Transactional transactional = CommonBillingTransactionExecutor.class
                .getMethod("required", Supplier.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation());
    }
}
