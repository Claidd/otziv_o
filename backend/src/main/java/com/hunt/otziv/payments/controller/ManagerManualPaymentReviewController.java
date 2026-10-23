package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManualPaymentDisputeRequest;
import com.hunt.otziv.payments.dto.ManualPaymentReviewView;
import com.hunt.otziv.payments.service.SpecialistManualPaymentReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'OWNER')")
public class ManagerManualPaymentReviewController {
    private final SpecialistManualPaymentReviewService reviews;

    @GetMapping("/api/orders/{orderId}/manual-payment-review")
    public ResponseEntity<java.util.Optional<ManualPaymentReviewView>> read(@PathVariable Long orderId, Authentication authentication) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(java.util.Optional.ofNullable(reviews.readForOrder(orderId, authentication)));
    }

    @PostMapping("/api/orders/{orderId}/manual-payment-review/dispute")
    public ResponseEntity<ManualPaymentReviewView> dispute(@PathVariable Long orderId,
            @RequestBody ManualPaymentDisputeRequest request, Authentication authentication) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reviews.disputeForOrder(orderId,
                request == null ? null : request.expectedReviewId(),
                request != null && Boolean.TRUE.equals(request.recipientReportedMissing()),
                request == null ? null : request.note(), authentication));
    }
}
