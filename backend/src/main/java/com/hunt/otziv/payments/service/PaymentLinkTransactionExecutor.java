package com.hunt.otziv.payments.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Opens a short, independent transaction around payment-link state changes. */
@Service
public class PaymentLinkTransactionExecutor {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T required(Supplier<T> work) {
        return work.get();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public <T> T readOnly(Supplier<T> work) {
        return work.get();
    }

    /**
     * Business validation may intentionally persist a safe terminal/quarantine
     * state before returning a 4xx/5xx response to the caller.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = ResponseStatusException.class
    )
    public <T> T requiredNoRollback(Supplier<T> work) {
        return work.get();
    }
}
