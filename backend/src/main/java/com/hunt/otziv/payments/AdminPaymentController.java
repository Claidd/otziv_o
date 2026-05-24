package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.TbankPaymentProfilesResponse;
import com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PaymentProfileService paymentProfileService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-links")
    public List<AdminPaymentLinkResponse> tbankLinks() {
        return paymentLinkService.adminLinks();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/admin/payments/tbank-links/{linkId}/cancel")
    public AdminPaymentLinkResponse cancelTbankPayment(@PathVariable Long linkId) {
        return paymentLinkService.cancel(linkId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/admin/payments/tbank-profiles")
    public TbankPaymentProfilesResponse tbankProfiles() {
        return paymentProfileService.managementState();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/api/admin/payments/tbank-profiles/manager-assignments")
    public TbankPaymentProfilesResponse updateTbankProfileAssignments(
            @RequestBody UpdateManagerPaymentProfilesRequest request
    ) {
        return paymentProfileService.updateManagerAssignments(request);
    }
}
