package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.config.jwt.service.LeadIntegrationHeaders;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.service.LeadCommandReceiver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Legacy bodies stay compatible; signed versioned commands receive an atomic receipt. */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadSyncController {
    private final LeadCommandReceiver receiver;

    @PostMapping(value="/sync",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncLead(@Valid @RequestBody LeadDtoTransfer dto,
            @RequestHeader(value=LeadIntegrationHeaders.TOKEN,required=false) String token,
            @RequestHeader(value="Idempotency-Key",required=false) String operationId) {
        var result=receiver.receive("SYNC",dto,token,operationId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping(value="/update",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateLead(@Valid @RequestBody LeadUpdateDto dto,
            @RequestHeader(value=LeadIntegrationHeaders.TOKEN,required=false) String token,
            @RequestHeader(value="Idempotency-Key",required=false) String operationId) {
        var result=receiver.receive("UPDATE",dto,token,operationId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @ExceptionHandler(LeadCommandReceiver.Rejected.class)
    ResponseEntity<?> rejected(LeadCommandReceiver.Rejected error) {
        return ResponseEntity.status(error.status).body(error.body);
    }
}
