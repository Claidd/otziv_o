package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.config.jwt.service.LeadIntegrationHeaders;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.service.LeadCommandReceiver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadReceiverController {
    private final LeadCommandReceiver receiver;

    @PostMapping("/import")
    public ResponseEntity<?> importLead(@Valid @RequestBody LeadDtoTransfer dto,
            @RequestHeader(value=LeadIntegrationHeaders.TOKEN,required=false) String token,
            @RequestHeader(value="Idempotency-Key",required=false) String operationId) {
        var result=receiver.receive("IMPORT",dto,token,operationId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @ExceptionHandler(LeadCommandReceiver.Rejected.class)
    ResponseEntity<?> rejected(LeadCommandReceiver.Rejected error) {
        return ResponseEntity.status(error.status).body(error.body);
    }
}
