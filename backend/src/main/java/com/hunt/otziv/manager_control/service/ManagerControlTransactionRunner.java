package com.hunt.otziv.manager_control.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ManagerControlTransactionRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T required(Supplier<T> work) {
        return work.get();
    }

    /** Contact reads after a contended order mutex must see the preceding repair/queue commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public <T> T contactReservation(Supplier<T> work) {
        return work.get();
    }
}
