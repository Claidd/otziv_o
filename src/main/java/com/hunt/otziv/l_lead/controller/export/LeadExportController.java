package com.hunt.otziv.l_lead.controller.export;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadExportController {

    private final LeadService leadService;
    private final LeadMapper leadMapper;

    @GetMapping("/modified")
    public ResponseEntity<List<LeadDtoTransfer>> getModifiedLeads(
            @RequestParam(name = "since") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        List<Lead> modifiedLeads = leadService.findModifiedSince(since);
        List<LeadDtoTransfer> dtos = modifiedLeads.stream()
                .map(leadMapper::toDtoTransfer)
                .toList();

        return ResponseEntity.ok(dtos);
    }
}
