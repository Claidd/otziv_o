package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.service.LeadService;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadReceiverController {

    private final LeadMapper leadMapper;
    private final LeadService leadService;
    private final OperatorRepository operatorRepo;
    private final ManagerRepository managerRepo;
    private final MarketologRepository marketologRepo;
    private final TelephoneRepository telephoneRepo;
    @PostMapping("/import")
    public ResponseEntity<?> importLead(@Valid @RequestBody LeadDtoTransfer dto) {
        Lead lead = leadMapper.toEntity(dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
        leadService.saveLead(lead);
        return ResponseEntity.ok("Лид успешно импортирован");
    }
}
