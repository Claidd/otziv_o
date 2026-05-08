package com.hunt.otziv.p_products.worker_flow;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkerFlowLockService {

    private final WorkerFlowLockRepository repository;

    @Transactional
    public boolean syncPublicationLock(
            String lockKey,
            Long workerId,
            boolean hasFlowOrders,
            boolean hasStaleOrders
    ) {
        if (!hasFlowOrders) {
            repository.deleteByLockKey(lockKey);
            return false;
        }

        if (hasStaleOrders) {
            lock(lockKey, workerId);
            return true;
        }

        return repository.existsById(lockKey);
    }

    private void lock(String lockKey, Long workerId) {
        WorkerFlowLock lock = repository.findById(lockKey)
                .orElseGet(() -> WorkerFlowLock.builder()
                        .lockKey(lockKey)
                        .build());

        lock.setWorkerId(workerId);
        repository.save(lock);
    }
}
