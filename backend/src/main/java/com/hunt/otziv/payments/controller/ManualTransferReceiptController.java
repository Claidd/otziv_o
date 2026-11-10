package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.service.ManualTransferReceiptService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ManualTransferReceiptController {
    private final ManualTransferReceiptService receipts;

    @PostMapping("/api/manager/manual-transfer-receipts")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER','MANAGER')")
    public List<ManagerManualCardPaymentResultResponse> record(
            @Valid @RequestBody ManualTransferReceiptService.Transfer request, Authentication actor) {
        return receipts.record(request, actor);
    }
}
