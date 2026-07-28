package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.workload_shadow.dto.WorkloadTransferPreferenceResponse;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferPreferenceRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkloadTransferPreferenceService {

    private final WorkloadTransferPreferenceRepository preferenceRepository;
    private final BusinessAuditService businessAuditService;
    private final WorkloadShadowRefreshSignal refreshSignal;

    @Transactional(readOnly = true)
    public WorkloadTransferPreferenceResponse current(String username) {
        return preferenceRepository.findByUsername(requiredUsername(username))
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Профиль специалиста не найден"
                ));
    }

    @Transactional
    public WorkloadTransferPreferenceResponse update(
            String username,
            boolean acceptsCompanyTransfers
    ) {
        String requiredUsername = requiredUsername(username);
        WorkloadTransferPreferenceResponse before =
                preferenceRepository.findByUsername(requiredUsername)
                        .map(this::toResponse)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Профиль специалиста не найден"
                        ));
        if (before.acceptsCompanyTransfers() == acceptsCompanyTransfers) {
            return before;
        }

        LocalDateTime changedAt = LocalDateTime.now();
        int updated = preferenceRepository.updatePreference(
                before.workerId(),
                requiredUsername,
                acceptsCompanyTransfers,
                changedAt
        );
        if (updated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Настройку не удалось сохранить"
            );
        }

        WorkloadTransferPreferenceResponse after =
                new WorkloadTransferPreferenceResponse(
                        before.workerId(),
                        acceptsCompanyTransfers,
                        changedAt
                );
        businessAuditService.recordSafely(
                "UPDATE_WORKLOAD_TRANSFER_PREFERENCE",
                "WORKER",
                before.workerId(),
                null,
                null,
                before,
                after,
                acceptsCompanyTransfers
                        ? "Специалист включил получение новых компаний"
                        : "Специалист исключил себя из списка получения новых компаний"
        );
        markProjectionDirtyAfterCommit();
        return after;
    }

    private void markProjectionDirtyAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshSignal.markDirty();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        refreshSignal.markDirty();
                    }
                }
        );
    }

    private WorkloadTransferPreferenceResponse toResponse(
            WorkloadTransferPreferenceRepository.PreferenceProjection projection
    ) {
        return new WorkloadTransferPreferenceResponse(
                projection.getWorkerId(),
                Boolean.TRUE.equals(projection.getAcceptsCompanyTransfers()),
                projection.getChangedAt()
        );
    }

    private String requiredUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Пользователь не определён"
            );
        }
        return username.trim();
    }
}
