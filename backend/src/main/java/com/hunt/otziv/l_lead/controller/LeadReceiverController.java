package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import lombok.RequiredArgsConstructor;
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
    private final JwtService jwtService;

    @PostMapping("/import")
    public ResponseEntity<?> importLead(
            @RequestBody LeadDtoTransfer dto,
            @RequestHeader("Authorization") String authHeader) {

        if (!authHeader.startsWith("Bearer "))
            return ResponseEntity.status(401).body("Unauthorized");

        String token = authHeader.substring(7);
        String checksumFromToken = jwtService.extractChecksum(token);
        String actualChecksum = jwtService.generateChecksum(dto);

        if (!checksumFromToken.equals(actualChecksum))
            return ResponseEntity.status(403).body("Checksum mismatch");

        Lead lead = leadMapper.toEntity(dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
        leadService.saveLead(lead);
        return ResponseEntity.ok("Лид успешно импортирован");
    }
}

