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
                .companyName(lead.getCompanyName())
                .phones(lead.getPhones())
                .mobilePhones(lead.getMobilePhones())
                .whatsappPhones(lead.getWhatsappPhones())
                .emails(lead.getEmails())
                .websites(lead.getWebsites())
                .vkUrl(lead.getVkUrl())
                .telegramUrl(lead.getTelegramUrl())
                .industries(lead.getIndustries())
                .companyType(lead.getCompanyType())
                .region(lead.getRegion())
                .address(lead.getAddress())
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
                .lastSeen(lead.getLastSeen())
                .build();
    }

    public LeadUpdateDto toUpdateDto(Lead lead) {
        return LeadUpdateDto.builder()
                .leadId(lead.getId())
                .telephoneLead(lead.getTelephoneLead())
                .companyName(lead.getCompanyName())
                .phones(lead.getPhones())
                .mobilePhones(lead.getMobilePhones())
                .whatsappPhones(lead.getWhatsappPhones())
                .emails(lead.getEmails())
                .websites(lead.getWebsites())
                .vkUrl(lead.getVkUrl())
                .telegramUrl(lead.getTelegramUrl())
                .industries(lead.getIndustries())
                .companyType(lead.getCompanyType())
                .region(lead.getRegion())
                .address(lead.getAddress())
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
                .lastSeen(lead.getLastSeen())
                .build();
    }

    public Lead toEntity(LeadDtoTransfer dto,
                         OperatorRepository operatorRepository,
                         ManagerRepository managerRepository,
                         MarketologRepository marketologRepository,
                         TelephoneRepository telephoneRepository) {
        return Lead.builder()
                .telephoneLead(dto.getTelephoneLead())
                .companyName(dto.getCompanyName())
                .phones(dto.getPhones())
                .mobilePhones(dto.getMobilePhones())
                .whatsappPhones(dto.getWhatsappPhones())
                .emails(dto.getEmails())
                .websites(dto.getWebsites())
                .vkUrl(dto.getVkUrl())
                .telegramUrl(dto.getTelegramUrl())
                .industries(dto.getIndustries())
                .companyType(dto.getCompanyType())
                .region(dto.getRegion())
                .address(dto.getAddress())
                .cityLead(dto.getCityLead())
                .commentsLead(dto.getCommentsLead())
                .lidStatus(dto.getLidStatus())
                .createDate(dto.getCreateDate())
                .updateStatus(dto.getUpdateStatus())
                .dateNewTry(dto.getDateNewTry())
                .offer(dto.isOffer())
                .lastSeen(dto.getLastSeen())
                .operator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null)
                .manager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null)
                .marketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null)
                .telephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null)
                .build();
    }

    /** Полный апдейт (МЕНЯЕТ телефон). Используй в /sync, НО не в /update. */
    public void updateEntity(Lead lead,
                             LeadUpdateDto dto,
                             OperatorRepository operatorRepository,
                             ManagerRepository managerRepository,
                             MarketologRepository marketologRepository,
                             TelephoneRepository telephoneRepository) {

        lead.setTelephoneLead(dto.getTelephoneLead()); // <-- меняет телефон
        lead.setCompanyName(dto.getCompanyName());
        lead.setPhones(dto.getPhones());
        lead.setMobilePhones(dto.getMobilePhones());
        lead.setWhatsappPhones(dto.getWhatsappPhones());
        lead.setEmails(dto.getEmails());
        lead.setWebsites(dto.getWebsites());
        lead.setVkUrl(dto.getVkUrl());
        lead.setTelegramUrl(dto.getTelegramUrl());
        lead.setIndustries(dto.getIndustries());
        lead.setCompanyType(dto.getCompanyType());
        lead.setRegion(dto.getRegion());
        lead.setAddress(dto.getAddress());
        lead.setCityLead(dto.getCityLead());
        lead.setCommentsLead(dto.getCommentsLead());
        lead.setLidStatus(dto.getLidStatus());
        lead.setCreateDate(dto.getCreateDate());
        lead.setUpdateStatus(dto.getUpdateStatus());
        lead.setDateNewTry(dto.getDateNewTry());
        lead.setOffer(dto.isOffer());
        lead.setLastSeen(dto.getLastSeen());

        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
    }

    /** Апдейт для /update: телефон НЕ меняем. */
    public void updateEntityExceptPhone(Lead lead,
                                        LeadUpdateDto dto,
                                        OperatorRepository operatorRepository,
                                        ManagerRepository managerRepository,
                                        MarketologRepository marketologRepository,
                                        TelephoneRepository telephoneRepository) {

        // телефон НЕ трогаем
        lead.setCompanyName(dto.getCompanyName());
        lead.setPhones(dto.getPhones());
        lead.setMobilePhones(dto.getMobilePhones());
        lead.setWhatsappPhones(dto.getWhatsappPhones());
        lead.setEmails(dto.getEmails());
        lead.setWebsites(dto.getWebsites());
        lead.setVkUrl(dto.getVkUrl());
        lead.setTelegramUrl(dto.getTelegramUrl());
        lead.setIndustries(dto.getIndustries());
        lead.setCompanyType(dto.getCompanyType());
        lead.setRegion(dto.getRegion());
        lead.setAddress(dto.getAddress());
        lead.setCityLead(dto.getCityLead());
        lead.setCommentsLead(dto.getCommentsLead());
        lead.setLidStatus(dto.getLidStatus());
        lead.setCreateDate(dto.getCreateDate());
        lead.setUpdateStatus(dto.getUpdateStatus());
        lead.setDateNewTry(dto.getDateNewTry());
        lead.setOffer(dto.isOffer());
        lead.setLastSeen(dto.getLastSeen());

        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
    }

    public void updateEntityFromTransfer(Lead lead,
                                         LeadDtoTransfer dto,
                                         OperatorRepository operatorRepository,
                                         ManagerRepository managerRepository,
                                         MarketologRepository marketologRepository,
                                         TelephoneRepository telephoneRepository) {

        if (dto.getTelephoneLead() != null) lead.setTelephoneLead(dto.getTelephoneLead());
        if (dto.getCompanyName() != null) lead.setCompanyName(dto.getCompanyName());
        if (dto.getPhones() != null) lead.setPhones(dto.getPhones());
        if (dto.getMobilePhones() != null) lead.setMobilePhones(dto.getMobilePhones());
        if (dto.getWhatsappPhones() != null) lead.setWhatsappPhones(dto.getWhatsappPhones());
        if (dto.getEmails() != null) lead.setEmails(dto.getEmails());
        if (dto.getWebsites() != null) lead.setWebsites(dto.getWebsites());
        if (dto.getVkUrl() != null) lead.setVkUrl(dto.getVkUrl());
        if (dto.getTelegramUrl() != null) lead.setTelegramUrl(dto.getTelegramUrl());
        if (dto.getIndustries() != null) lead.setIndustries(dto.getIndustries());
        if (dto.getCompanyType() != null) lead.setCompanyType(dto.getCompanyType());
        if (dto.getRegion() != null) lead.setRegion(dto.getRegion());
        if (dto.getAddress() != null) lead.setAddress(dto.getAddress());
        if (dto.getCityLead() != null) lead.setCityLead(dto.getCityLead());
        if (dto.getCommentsLead() != null) lead.setCommentsLead(dto.getCommentsLead());
        if (dto.getLidStatus() != null) lead.setLidStatus(dto.getLidStatus());
        if (dto.getCreateDate() != null) lead.setCreateDate(dto.getCreateDate());
        if (dto.getUpdateStatus() != null) lead.setUpdateStatus(dto.getUpdateStatus());
        if (dto.getDateNewTry() != null) lead.setDateNewTry(dto.getDateNewTry());
        lead.setOffer(dto.isOffer());
        if (dto.getLastSeen() != null) lead.setLastSeen(dto.getLastSeen());

        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
    }

    /** Нужна только если где-то действительно создаёшь лид из UpdateDto. Для строгого /update — не используется. */
    public Lead toEntityFromUpdateDto(LeadUpdateDto dto,
                                      OperatorRepository operatorRepository,
                                      ManagerRepository managerRepository,
                                      MarketologRepository marketologRepository,
                                      TelephoneRepository telephoneRepository) {
        return Lead.builder()
                .telephoneLead(dto.getTelephoneLead())
                .companyName(dto.getCompanyName())
                .phones(dto.getPhones())
                .mobilePhones(dto.getMobilePhones())
                .whatsappPhones(dto.getWhatsappPhones())
                .emails(dto.getEmails())
                .websites(dto.getWebsites())
                .vkUrl(dto.getVkUrl())
                .telegramUrl(dto.getTelegramUrl())
                .industries(dto.getIndustries())
                .companyType(dto.getCompanyType())
                .region(dto.getRegion())
                .address(dto.getAddress())
                .cityLead(dto.getCityLead())
                .commentsLead(dto.getCommentsLead())
                .lidStatus(dto.getLidStatus())
                .createDate(dto.getCreateDate())
                .updateStatus(dto.getUpdateStatus())
                .dateNewTry(dto.getDateNewTry())
                .offer(dto.isOffer())
                .lastSeen(dto.getLastSeen())
                .operator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null)
                .manager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null)
                .marketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null)
                .telephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null)
                .build();
    }
}

//
//@Component
//public class LeadMapper {
//
//    public LeadDtoTransfer toDtoTransfer(Lead lead) {
//        return LeadDtoTransfer.builder()
//                .telephoneLead(lead.getTelephoneLead())
//                .cityLead(lead.getCityLead())
//                .commentsLead(lead.getCommentsLead())
//                .lidStatus(lead.getLidStatus())
//                .createDate(lead.getCreateDate())
//                .updateStatus(lead.getUpdateStatus())
//                .dateNewTry(lead.getDateNewTry())
//                .offer(lead.isOffer())
//                .operatorId(lead.getOperator() != null ? lead.getOperator().getId() : null)
//                .managerId(lead.getManager() != null ? lead.getManager().getId() : null)
//                .marketologId(lead.getMarketolog() != null ? lead.getMarketolog().getId() : null)
//                .telephoneId(lead.getTelephone() != null ? lead.getTelephone().getId() : null)
//                .lastSeen(lead.getLastSeen())
//                .build();
//    }
//
//    public LeadUpdateDto toUpdateDto(Lead lead) {
//        return LeadUpdateDto.builder()
//                .leadId(lead.getId())
//                .telephoneLead(lead.getTelephoneLead())
//                .cityLead(lead.getCityLead())
//                .commentsLead(lead.getCommentsLead())
//                .lidStatus(lead.getLidStatus())
//                .createDate(lead.getCreateDate())
//                .updateStatus(lead.getUpdateStatus())
//                .dateNewTry(lead.getDateNewTry())
//                .offer(lead.isOffer())
//                .managerId(lead.getManager() != null ? lead.getManager().getId() : null)
//                .operatorId(lead.getOperator() != null ? lead.getOperator().getId() : null)
//                .marketologId(lead.getMarketolog() != null ? lead.getMarketolog().getId() : null)
//                .telephoneId(lead.getTelephone() != null ? lead.getTelephone().getId() : null)
//                .lastSeen(lead.getLastSeen())
//                .build();
//    }
//
//    public Lead toEntity(LeadDtoTransfer dto,
//                         OperatorRepository operatorRepository,
//                         ManagerRepository managerRepository,
//                         MarketologRepository marketologRepository,
//                         TelephoneRepository telephoneRepository) {
//        return Lead.builder()
//                .telephoneLead(dto.getTelephoneLead())
//                .cityLead(dto.getCityLead())
//                .commentsLead(dto.getCommentsLead())
//                .lidStatus(dto.getLidStatus())
//                .createDate(dto.getCreateDate())
//                .updateStatus(dto.getUpdateStatus())
//                .dateNewTry(dto.getDateNewTry())
//                .offer(dto.isOffer())
//                .lastSeen(dto.getLastSeen())
//                .operator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null)
//                .manager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null)
//                .marketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null)
//                .telephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null)
//
//                .build();
//    }
//
//    public void updateEntity(Lead lead,
//                             LeadUpdateDto dto,
//                             OperatorRepository operatorRepository,
//                             ManagerRepository managerRepository,
//                             MarketologRepository marketologRepository,
//                             TelephoneRepository telephoneRepository) {
//
//        lead.setTelephoneLead(dto.getTelephoneLead());
//        lead.setCityLead(dto.getCityLead());
//        lead.setCommentsLead(dto.getCommentsLead());
//        lead.setLidStatus(dto.getLidStatus());
//        lead.setCreateDate(dto.getCreateDate());
//        lead.setUpdateStatus(dto.getUpdateStatus());
//        lead.setDateNewTry(dto.getDateNewTry());
//        lead.setOffer(dto.isOffer());
//        lead.setLastSeen(dto.getLastSeen());
//
//        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
//        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
//        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
//        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
//    }
//
//    public void updateEntityFromTransfer(Lead lead,
//                                         LeadDtoTransfer dto,
//                                         OperatorRepository operatorRepository,
//                                         ManagerRepository managerRepository,
//                                         MarketologRepository marketologRepository,
//                                         TelephoneRepository telephoneRepository) {
//
//        if (dto.getTelephoneLead() != null) lead.setTelephoneLead(dto.getTelephoneLead());
//        if (dto.getCityLead() != null) lead.setCityLead(dto.getCityLead());
//        if (dto.getCommentsLead() != null) lead.setCommentsLead(dto.getCommentsLead());
//        if (dto.getLidStatus() != null) lead.setLidStatus(dto.getLidStatus());
//        if (dto.getCreateDate() != null) lead.setCreateDate(dto.getCreateDate());
//        if (dto.getUpdateStatus() != null) lead.setUpdateStatus(dto.getUpdateStatus());
//        if (dto.getDateNewTry() != null) lead.setDateNewTry(dto.getDateNewTry());
//        lead.setOffer(dto.isOffer());
//        if (dto.getLastSeen() != null) lead.setLastSeen(dto.getLastSeen());
//
//        lead.setOperator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null);
//        lead.setManager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null);
//        lead.setMarketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null);
//        lead.setTelephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null);
//    }
//
//    public Lead toEntityFromUpdateDto(LeadUpdateDto dto,
//                                      OperatorRepository operatorRepository,
//                                      ManagerRepository managerRepository,
//                                      MarketologRepository marketologRepository,
//                                      TelephoneRepository telephoneRepository) {
//        return Lead.builder()
//                .telephoneLead(dto.getTelephoneLead())
//                .cityLead(dto.getCityLead())
//                .commentsLead(dto.getCommentsLead())
//                .lidStatus(dto.getLidStatus())
//                .createDate(dto.getCreateDate())
//                .updateStatus(dto.getUpdateStatus())
//                .dateNewTry(dto.getDateNewTry())
//                .offer(dto.isOffer())
//                .lastSeen(dto.getLastSeen())
//                .operator(dto.getOperatorId() != null ? operatorRepository.findById(dto.getOperatorId()).orElse(null) : null)
//                .manager(dto.getManagerId() != null ? managerRepository.findById(dto.getManagerId()).orElse(null) : null)
//                .marketolog(dto.getMarketologId() != null ? marketologRepository.findById(dto.getMarketologId()).orElse(null) : null)
//                .telephone(dto.getTelephoneId() != null ? telephoneRepository.findById(dto.getTelephoneId()).orElse(null) : null)
//                .build();
//    }
//
//
//
//
//
//}


