package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import com.hunt.otziv.payments.service.PaymentRouteChangeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import com.hunt.otziv.payments.service.PaymentLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ManagerPaymentController {

    private final PaymentLinkService paymentLinkService;
    private final PaymentRouteChangeService paymentRouteChangeService;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/manager/orders/{orderId}/payment-link")
    public ManagerPaymentLinkResponse createPaymentLink(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return paymentLinkService.createForOrderAuthorized(orderId, authentication);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @GetMapping("/api/manager/orders/{orderId}/payment-route-change-context")
    public PaymentRouteChangeContextResponse paymentRouteChangeContext(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return paymentRouteChangeService.context(orderId, authentication);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/manager/orders/{orderId}/payment-route-change")
    public PaymentRouteChangeResponse changePaymentRoute(
            @PathVariable Long orderId,
            @Valid @RequestBody PaymentRouteChangeRequest request,
            Authentication authentication
    ) {
        return paymentRouteChangeService.change(orderId, request, authentication);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/manager/orders/{orderId}/paper-invoice/issued")
    public ManagerPaymentLinkResponse markPaperInvoiceIssued(
            @PathVariable Long orderId,
            Authentication authentication
    ) {
        return paymentLinkService.markPaperInvoiceIssuedAuthorized(orderId, authentication);
    }
}
