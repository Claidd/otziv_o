package com.hunt.otziv.client_messages.controller;

import com.hunt.otziv.client_messages.service.ClientMessageOperationFence;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Administrative receipt attestation, never an automatic retry or an UNKNOWN→unsent transition. */
@RestController
@RequestMapping("/api/admin/client-message-operations")
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class ClientMessageOperationAdminController {
    private final ClientMessageOperationFence fence;
    public ClientMessageOperationAdminController(ClientMessageOperationFence fence){this.fence=fence;}
    @GetMapping("/{id}")
    public ClientMessageOperationFence.Snapshot get(@PathVariable String id,Authentication actor){
        ClientMessageOperationFence.validateAdministrator(actor);
        return fence.lookup(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Операция не найдена"));
    }
    @PostMapping("/{id}/confirm-delivered")
    public ClientMessageOperationFence.Snapshot confirm(@PathVariable String id,@RequestBody Confirmation body,Authentication actor){
        return fence.confirmDelivered(actor,id,body.expectedClaimToken(),body.expectedEnvelopeHash(),body.providerMessageId(),body.reason());
    }
    public record Confirmation(String expectedClaimToken,String expectedEnvelopeHash,String providerMessageId,String reason){}
}
