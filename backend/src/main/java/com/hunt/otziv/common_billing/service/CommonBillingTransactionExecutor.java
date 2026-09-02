package com.hunt.otziv.common_billing.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Opens an independent transaction for common-billing work started after another commit. */
@Service
public class CommonBillingTransactionExecutor {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T required(Supplier<T> work) {
        return work.get();
    }
}
