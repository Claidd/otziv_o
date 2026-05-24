package com.hunt.otziv.payments;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ManagerPaymentController {

    private final PaymentLinkService paymentLinkService;

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/manager/orders/{orderId}/payment-link")
    public ManagerPaymentLinkResponse createPaymentLink(@PathVariable Long orderId) {
        return paymentLinkService.createForOrder(orderId);
    }
}
