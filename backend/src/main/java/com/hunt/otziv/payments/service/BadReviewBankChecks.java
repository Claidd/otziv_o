package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.BadReviewBankRepricing;
import com.hunt.otziv.payments.api.UnpaidBankObservation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class BadReviewBankChecks {
    private final List<BadReviewBankRepricing> workflows;
    private final UnpaidBankObservation bank;
    private final TransactionTemplate transactions;

    @Scheduled(initialDelay = 30000, fixedDelay = 20000)
    public void resume() {
        for (var workflow : workflows) {
            for (long id : workflow.due()) {
                try {
                    var source = transactions.execute(tx -> workflow.claim(id));
                    if (source == null || source.operation().isBlank()) continue;
                    var observation = bank.observe(source);
                    if (observation.outcome() != UnpaidBankObservation.Outcome.WAITING)
                        transactions.executeWithoutResult(tx -> workflow.finish(source, observation));
                } catch (RuntimeException failure) {
                    log.warn("Bad-review bank check deferred for {} {} ({})", workflow.getClass().getSimpleName(), id, failure.getClass().getSimpleName());
                }
            }
        }
    }
}
