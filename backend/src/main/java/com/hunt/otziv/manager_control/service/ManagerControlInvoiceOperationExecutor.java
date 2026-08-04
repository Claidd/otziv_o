package com.hunt.otziv.manager_control.service;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspends the Manager Control transaction while common-invoice operations
 * use their own transaction boundaries and pessimistic locks.
 */
@Service
public class ManagerControlInvoiceOperationExecutor {

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> T execute(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }
}
