package com.hunt.otziv.contractor_payments.controller;

import com.hunt.otziv.contractor_payments.service.ManualReceiptCorrections;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/payments/manual-receipts")
@PreAuthorize("hasRole('ADMIN')")
public class ManualReceiptCorrectionController {
    private final ManualReceiptCorrections receipts;

    @GetMapping
    public List<ManualReceiptCorrections.Receipt> find(@RequestParam long target, @RequestParam boolean invoice) {
        return receipts.find(target, invoice);
    }

    @GetMapping("/recipients")
    public List<ManualReceiptCorrections.Recipient> recipients() { return receipts.recipients(); }

    @PostMapping("/{id}/correction")
    public void correct(@PathVariable long id,@Valid @RequestBody ManualReceiptCorrections.Request request,Principal actor) {
        receipts.correct(id,request,actor);
    }
}
