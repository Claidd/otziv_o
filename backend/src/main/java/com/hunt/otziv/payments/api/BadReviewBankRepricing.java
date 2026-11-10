package com.hunt.otziv.payments.api;

import java.util.List;

/** Durable bad-review work bound to the bank attempt that still carries the previous amount. */
public interface BadReviewBankRepricing {
    List<Long> due();
    UnpaidBankObservation.Source claim(long id);
    void finish(UnpaidBankObservation.Source source, UnpaidBankObservation.Result observation);
}
