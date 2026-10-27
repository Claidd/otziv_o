package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.p_products.next_order.repository.NextOrderWorkerRoutingRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NextOrderWorkerRoutingService {
    private final NextOrderWorkerRoutingRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public long resolveWorkerId(Long companyId, Long sourceWorkerId, Long sourceManagerId) {
        if (companyId == null || sourceWorkerId == null) {
            throw new IllegalStateException("Не определены компания или специалист исходного заказа");
        }
        Long managerId = repository.lockCompanyManager(companyId);
        if (managerId == null || !Objects.equals(managerId, sourceManagerId)) {
            throw new IllegalStateException("Менеджер исходного заказа не совпадает с менеджером компании");
        }
        var memberships = new HashMap<Long, NextOrderWorkerRoutingRepository.Membership>();
        repository.lockMemberships(companyId, managerId)
                .forEach(member -> memberships.put(member.workerId(), member));
        long workerId = sourceWorkerId;
        // Preserve intentional shared ownership. A removed membership needs
        // the latest successful transfers of this exact company, never an
        // arbitrary recipient chosen from the company's other specialists.
        var transfers = new HashMap<Long, Long>();
        if (!memberships.containsKey(workerId)) {
            repository.lockAppliedTransfers(companyId).forEach(transfer ->
                    transfers.putIfAbsent(transfer.sourceWorkerId(), transfer.targetWorkerId()));
        }
        var visited = new HashSet<Long>();
        while (visited.add(workerId)) {
            var membership = memberships.get(workerId);
            if (membership != null) {
                if (!membership.active() || !membership.managerMatches()) {
                    throw new IllegalStateException("Текущий специалист компании неактивен или сменил менеджера");
                }
                return workerId;
            }
            Long target = transfers.get(workerId);
            if (target == null) {
                throw new IllegalStateException("Специалист больше не закреплён за компанией " + companyId
                        + "; подтверждённый получатель передачи не найден");
            }
            workerId = target;
        }
        throw new IllegalStateException("Обнаружен цикл передач компании " + companyId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void auditReroute(long requestId, long sourceOrderId, long createdOrderId,
            long sourceWorkerId, long targetWorkerId) {
        if (sourceWorkerId != targetWorkerId) {
            repository.auditReroute(requestId, sourceOrderId, createdOrderId, sourceWorkerId, targetWorkerId);
        }
    }
}
