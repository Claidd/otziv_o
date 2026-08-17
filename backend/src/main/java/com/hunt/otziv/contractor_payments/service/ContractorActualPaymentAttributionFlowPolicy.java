package com.hunt.otziv.contractor_payments.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One fail-closed interpretation of the global contractor-accounting mode.
 * TEST/SHADOW and LIVE require durable actual-recipient facts. Only the
 * authoritative LEGACY state may use the old free-text confirmation routes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractorActualPaymentAttributionFlowPolicy {

    private final ContractorActualPaymentAttributionService attributionService;

    public boolean attributionRequired() {
        try {
            return attributionService.actualRecipientAccountingRequired();
        } catch (RuntimeException exception) {
            log.error(
                    "Actual-recipient mode read failed; manual payment fails closed: failure={}",
                    exception.getClass().getSimpleName()
            );
            return true;
        }
    }

    public void requireAttributionFlow() {
        if (!attributionRequired()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Учет фактических получателей выключен: используйте обычное подтверждение оплаты"
            );
        }
    }

    /** A route already issued through typed task accounting remains finishable after the flag is disabled. */
    public void requireAttributionFlowOrFrozenTask(boolean frozenManualTaskRoute) {
        if (!frozenManualTaskRoute) {
            requireAttributionFlow();
        }
    }

    public void requireLegacyFlow() {
        if (attributionRequired()) {
            throw legacyFlowConflict();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireLegacyFlowLocked() {
        if (attributionService.actualRecipientAccountingEnabled()) {
            throw legacyFlowConflict();
        }
    }

    private ResponseStatusException legacyFlowConflict() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Выберите фактических получателей через новое подтверждение оплаты общего счета"
        );
    }
}
