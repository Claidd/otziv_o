package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentAllocationJournalItemResponse;
import com.hunt.otziv.contractor_payments.dto.ContractorReturnAmountRequest;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentVisibilityService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentQueueHealthResponse;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentQueueHealthService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/contractor-payment-allocations")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ContractorPaymentJournalAdminController {

    private final ContractorPaymentVisibilityService visibilityService;
    private final ContractorPaymentShadowService shadowService;
    private final ContractorPaymentQueueHealthService queueHealthService;
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy;

    @GetMapping("/health")
    public ContractorPaymentQueueHealthResponse health() {
        return queueHealthService.health();
    }

    @GetMapping
    public Page<ContractorPaymentAllocationJournalItemResponse> journal(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) ContractorAllocationStatus status,
            @RequestParam(required = false) ContractorAllocationMode mode,
            @RequestParam(required = false) ContractorAllocationSourceType sourceType,
            @RequestParam(required = false) Long sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return visibilityService.journal(userId, status, mode, sourceType, sourceId, page, size);
    }

    @PutMapping("/{allocationId}/returned-amount")
    public void recordReturnedAmount(
            @PathVariable Long allocationId,
            @Valid @RequestBody ContractorReturnAmountRequest request
    ) {
        targetAccessPolicy.requireCanManageAllocationRecipient(allocationId);
        try {
            shadowService.recordObservedReturnAmount(
                    allocationId,
                    request.returnedTotalKopecks(),
                    request.effectiveAt() == null ? LocalDateTime.now() : request.effectiveAt(),
                    "MANUAL_RETURN_TOTAL:" + request.returnedTotalKopecks(),
                    request.reason()
            );
        } catch (IllegalArgumentException ex) {
            throw returnAmountFailure(ex);
        }
    }

    private ResponseStatusException returnAmountFailure(IllegalArgumentException failure) {
        String message = failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Невозможно скорректировать возврат для этого назначения"
                : failure.getMessage();
        HttpStatus status = message.contains("не найдено") || message.contains("больше не существует")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return new ResponseStatusException(status, message, failure);
    }
}
