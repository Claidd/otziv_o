package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnResponse;
import com.hunt.otziv.common_billing.service.CommonManualPaymentTaskReturnService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CommonManualPaymentTaskReturnController {

    private final CommonManualPaymentTaskReturnService returnService;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @PostMapping("/api/common-billing/invoices/{invoiceId}/manual-task-return")
    public ResponseEntity<CommonManualPaymentTaskReturnResponse> record(
            @PathVariable Long invoiceId,
            @Valid @RequestBody CommonManualPaymentTaskReturnRequest request,
            Principal principal
    ) {
        CommonManualPaymentTaskReturnResponse response = returnService.record(
                invoiceId,
                request,
                principal == null ? "system:authenticated-admin" : principal.getName()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(response);
    }
}
