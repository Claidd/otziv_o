package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.SpecialistManualPaymentReviewResponse;
import com.hunt.otziv.payments.dto.SpecialistPaymentAbsentRequest;
import com.hunt.otziv.payments.dto.SpecialistPaymentVerifyRequest;
import com.hunt.otziv.payments.service.SpecialistManualPaymentReviewService;
import com.hunt.otziv.payments.service.SpecialistManualPaymentReviewSettlementService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SpecialistManualPaymentReviewAdminController {

    private final SpecialistManualPaymentReviewService reviews;
    private final SpecialistManualPaymentReviewSettlementService settlements;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/specialist-reviews")
    public ResponseEntity<List<SpecialistManualPaymentReviewResponse>> open() {
        return noCache(reviews.openForAdministration());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/specialist-reviews/{id}")
    public ResponseEntity<SpecialistManualPaymentReviewResponse> read(@PathVariable Long id) {
        return noCache(reviews.readForAdministration(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/specialist-reviews/{id}/retry-notification")
    public ResponseEntity<SpecialistManualPaymentReviewResponse> retry(@PathVariable Long id) {
        reviews.resetDeliveryAttempt(id);
        reviews.deliver(id);
        return noCache(reviews.readForAdministration(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/specialist-reviews/{id}/reconcile")
    public ResponseEntity<SpecialistManualPaymentReviewResponse> reconcile(@PathVariable Long id) {
        return noCache(reviews.reconcileOperatorOutcome(id));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/specialist-reviews/{id}/mark-absent")
    public ResponseEntity<SpecialistManualPaymentReviewResponse> markAbsent(
            @PathVariable Long id,
            @RequestBody SpecialistPaymentAbsentRequest request,
            Authentication authentication) {
        return noCache(reviews.markAbsent(id,
                request != null && Boolean.TRUE.equals(request.recipientStatementChecked()),
                request != null && Boolean.TRUE.equals(request.paymentAbsent()),
                request == null ? null : request.note(), authentication));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/admin/payments/specialist-reviews/{id}/verify-received")
    public ResponseEntity<SpecialistManualPaymentReviewResponse> verifyReceived(
            @PathVariable Long id,
            @RequestBody SpecialistPaymentVerifyRequest request,
            Authentication authentication) {
        return noCache(settlements.verifyReceived(id,
                request != null && Boolean.TRUE.equals(request.recipientStatementChecked()),
                request != null && Boolean.TRUE.equals(request.paymentReceived()),
                request == null ? null : request.receivedAmountKopecks(),
                request == null ? null : request.note(), authentication));
    }

    private <T> ResponseEntity<T> noCache(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
