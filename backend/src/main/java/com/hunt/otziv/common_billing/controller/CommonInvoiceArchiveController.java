package com.hunt.otziv.common_billing.controller;

import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveDetailsResponse;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveListItem;
import com.hunt.otziv.common_billing.dto.CommonInvoiceArchiveRestoreResult;
import com.hunt.otziv.common_billing.service.CommonInvoiceArchiveService;
import com.hunt.otziv.manager.dto.api.PageResponse;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/common-billing/archive")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')")
public class CommonInvoiceArchiveController {

    private final CommonInvoiceArchiveService service;

    @GetMapping("/invoices")
    public PageResponse<CommonInvoiceArchiveListItem> find(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(defaultValue = "desc") String sortDirection,
            Principal principal,
            Authentication authentication
    ) {
        return service.find(keyword, pageNumber, pageSize, sortDirection, principal, authentication);
    }

    @GetMapping("/invoices/{invoiceId}")
    public CommonInvoiceArchiveDetailsResponse details(
            @PathVariable Long invoiceId,
            Principal principal,
            Authentication authentication
    ) {
        return service.details(invoiceId, principal, authentication);
    }

    @PostMapping("/invoices/{invoiceId}/restore")
    public CommonInvoiceArchiveRestoreResult restore(
            @PathVariable Long invoiceId,
            @RequestParam(defaultValue = "false") boolean confirm,
            Principal principal,
            Authentication authentication
    ) {
        return service.restore(invoiceId, confirm, principal, authentication);
    }
}
