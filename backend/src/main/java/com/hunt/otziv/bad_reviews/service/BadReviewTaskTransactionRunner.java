package com.hunt.otziv.bad_reviews.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs the locked part of a bad-review task mutation in a fresh transaction. */
@Service
public class BadReviewTaskTransactionRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T required(Supplier<T> work) {
        return work.get();
    }
}
