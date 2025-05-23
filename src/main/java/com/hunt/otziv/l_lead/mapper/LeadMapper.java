package com.hunt.otziv.l_lead.mapper;

import com.hunt.otziv.l_lead.dto.LeadDTO;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import org.springframework.stereotype.Component;

@Component
public class LeadMapper {

    public LeadDtoTransfer toDtoTransfer(Lead lead) {
        return LeadDtoTransfer.builder()
                .telephoneLead(lead.getTelephoneLead())
                .cityLead(lead.getCityLead())
                .commentsLead(lead.getCommentsLead())
                .lidStatus(lead.getLidStatus())
                .createDate(lead.getCreateDate())
                .updateStatus(lead.getUpdateStatus())
                .dateNewTry(lead.getDateNewTry())
                .offer(lead.isOffer())
                .operatorId(lead.getOperator() != null ? lead.getOperator().getId() : null)
                .managerId(lead.getManager() != null ? lead.getManager().getId() : null)
                .marketologId(lead.getMarketolog() != null ? lead.getMarketolog().getId() : null)
                .telephoneId(lead.getTelephone() != null ? lead.getTelephone().getId() : null)
                .build();
    }

    public LeadUpdateDto toUpdateDto(Lead lead) {
        return LeadUpdateDto.builder()
                .leadId(lead.getId())
                .telephoneLead(lead.getTelephoneLead())
                .cityLead(lead.getCityLead())
                .commentsLead(lead.getCommentsLead())
                .lidStatus(lead.getLidStatus())
                .createDate(lead.getCreateDate())
                .updateStatus(lead.getUpdateStatus())
                .dateNewTry(lead.getDateNewTry())
                .offer(lead.isOffer())
                .managerId(lead.getManager() != null ? lead.getManager().getId() : null)
                .operatorId(lead.getOperator() != null ? lead.getOperator().getId() : null)
                .marketologId(lead.getMarketolog() != null ? lead.getMarketolog().getId() : null)
                .telephoneId(lead.getTelephone() != null ? lead.getTelephone().getId() : null)
                .build();
    }

    public Lead toEntity(LeadDtoTransfer dto,
                         OperatorRepository operatorRepository,
                         ManagerRepository managerRepository,
                         MarketologRepository marketologRepository,
                         TelephoneRepository telephoneRepository) {
        return Lead.builder()
                .telephoneLead(dto.getTelephoneLead())
                .cityLead(dto.getCityLead())
                .commentsLead(dto.getCommentsLead())
                .lidStatus(dto.getLidStatus())
                .createDate(dto.getCreateDate())
                .updateStatus(dto.getUpdateStatus())
                .dateNewTry(dto.getDateNewTry())
                .offer(dto.isOffer())
                .operator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null)
                .manager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null)
                .marketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null)
                .telephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null)
                .build();
    }

    public void updateEntity(Lead lead,
                             LeadUpdateDto dto,
                             OperatorRepository operatorRepository,
                             ManagerRepository managerRepository,
                             MarketologRepository marketologRepository,
                             TelephoneRepository telephoneRepository) {

        lead.setTelephoneLead(dto.getTelephoneLead());
        lead.setCityLead(dto.getCityLead());
        lead.setCommentsLead(dto.getCommentsLead());
        lead.setLidStatus(dto.getLidStatus());
        lead.setCreateDate(dto.getCreateDate());
        lead.setUpdateStatus(dto.getUpdateStatus());
        lead.setDateNewTry(dto.getDateNewTry());
        lead.setOffer(dto.isOffer());

        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
    }
}


