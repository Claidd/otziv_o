package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadLiveDailyQuotaLockRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadLiveDailyQuotaLockService {

    private final WorkloadLiveDailyQuotaLockRepository repository;

    /** The row lock is retained by the surrounding transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LocalDate decisionDate) {
        repository.ensureDay(decisionDate);
        repository.lockDay(decisionDate).orElseThrow(() ->
                new IllegalStateException("Не удалось захватить дневную квоту LIVE"));
    }
}
