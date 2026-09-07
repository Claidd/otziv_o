package com.hunt.otziv.whatsapp.controller;

import com.hunt.otziv.whatsapp.dto.WhatsAppOperationStatus;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/whatsapp-operations")
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class WhatsAppOperationAdminController {
    private final WhatsAppService whatsapp;

    @GetMapping("/{clientId}/{operationId}")
    public ResponseEntity<WhatsAppOperationStatus> status(@PathVariable String clientId, @PathVariable String operationId) {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(whatsapp.getOperationStatus(clientId, operationId));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operation ID");
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operation status unavailable");
        }
    }
}
