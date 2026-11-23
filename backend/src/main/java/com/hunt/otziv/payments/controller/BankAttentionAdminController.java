package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.BankAttentionResponse;
import com.hunt.otziv.payments.service.BankAttentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** «Банк → Требует решения» for the owner and administrators: read-only. */
@RestController
@RequiredArgsConstructor
public class BankAttentionAdminController {

    private final BankAttentionService attention;

    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    @GetMapping("/api/admin/payments/attention")
    public ResponseEntity<BankAttentionResponse> attention() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(attention.attention());
    }
}
