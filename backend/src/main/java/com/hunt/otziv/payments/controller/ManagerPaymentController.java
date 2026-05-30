package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ManagerPaymentController {

    private final PaymentLinkService paymentLinkService;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
    @PostMapping("/api/manager/orders/{orderId}/payment-link")
    public ManagerPaymentLinkResponse createPaymentLink(@PathVariable Long orderId) {
        return paymentLinkService.createForOrder(orderId);
    }
}
