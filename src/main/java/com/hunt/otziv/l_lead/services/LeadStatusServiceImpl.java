package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.services.serv.LeadStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadStatusServiceImpl implements LeadStatusService {
    private final LeadsRepository leadRepository;
    private final LeadEventPublisher leadEventPublisher;


    @Transactional
    public void prepareLeadForSending(Lead lead, String status) {
        lead.setLidStatus(status);
        lead.setUpdateStatus(LocalDateTime.now());
        leadRepository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }
}
