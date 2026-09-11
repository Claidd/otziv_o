package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.hunt.otziv.logs.util.LogMasking.*;

/** Existing mapping rules; the receiver owns the surrounding receipt/business transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class LeadInboundMutationService {
    private final LeadsRepository leadRepository;
    private final LeadMapper leadMapper;
    private final OperatorRepository operatorRepo;
    private final ManagerRepository managerRepo;
    private final MarketologRepository marketologRepo;
    private final TelephoneRepository telephoneRepo;
    private final jakarta.persistence.EntityManager entityManager;

    public record Result(int status, Object body, Long targetLeadId) {}

    public Result applyVersioned(String kind,Object payload,Long targetId) {
        return "UPDATE".equals(kind) ? update((LeadUpdateDto)payload,targetId)
                : sync((LeadDtoTransfer)payload,targetId,true);
    }

    private Lead current(Long targetId) {
        Lead lead=leadRepository.findByIdForWorkTransition(targetId).orElse(null);
        if(lead!=null)entityManager.refresh(lead,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return lead;
    }

    public Result legacyImport(LeadDtoTransfer dto) {
        Lead lead = leadMapper.toEntity(dto,operatorRepo,managerRepo,marketologRepo,telephoneRepo);
        leadRepository.save(lead);
        return new Result(200,"Лид успешно импортирован",lead.getId());
    }

    /** Создаёт нового лида или обновляет существующего (по telephoneLead).
     *  Токен + checksum валидирует JwtAuthFilter.
     */
    public Result sync(LeadDtoTransfer dto) {
        return sync(dto,null,false);
    }

    private Result sync(LeadDtoTransfer dto,Long targetId,boolean versioned) {
        log.info("\n==================== [SYNC LEAD] ====================");
        log.info("Lead transfer received: telephone={}, email={}, cityPresent={}, operatorId={}, managerId={}, marketologId={}, telephoneId={}",
                maskPhone(dto.getTelephoneLead()), maskEmail(dto.getEmails()), hasText(dto.getCityLead()),
                dto.getOperatorId(), dto.getManagerId(), dto.getMarketologId(), dto.getTelephoneId());

        Lead existing = targetId!=null ? current(targetId) : versioned ? findByAnyPhoneCandidate(buildPhoneCandidates(dto.getTelephoneLead()))
                : leadRepository.findByTelephoneLead(dto.getTelephoneLead()).orElse(null);
        if(targetId!=null && existing==null)return new Result(409,Map.of("error","LEAD_BOUND_TARGET_MISSING"),null);
        if(existing!=null)existing=current(existing.getId());

        if (existing != null) {
            leadMapper.updateEntityFromTransfer(existing, dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo,versioned);
            leadRepository.save(existing);
            log.info("🟩 Лид обновлён: id={}, telephone={}", existing.getId(), maskPhone(existing.getTelephoneLead()));
            log.info("==================== [END SYNC LEAD] ====================\n");
            return new Result(200,"Лид обновлён",existing.getId());
        } else {
            Lead newLead = leadMapper.toEntity(dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
            leadRepository.save(newLead);
            log.info("🟢 Лид создан: id={}, telephone={}", newLead.getId(), maskPhone(newLead.getTelephoneLead()));
            log.info("==================== [END SYNC LEAD] ====================\n");
            return new Result(200,"Лид создан",newLead.getId());
        }
    }

    /** Строгое обновление по телефону.
     *  Никаких созданий: если не нашли — 404.
     *  JWT (subject=lead-sync) проверяет JwtAuthFilter.
     */
    public Result update(LeadUpdateDto dto) {
        return update(dto,null);
    }

    private Result update(LeadUpdateDto dto,Long targetId) {
        log.info("\n==================== [SYNC UPDATE] ====================");
        log.info("[SYNC] Lead update received: leadId={}, telephone={}, email={}, cityPresent={}, operatorId={}, managerId={}, marketologId={}, telephoneId={}",
                dto.getLeadId(), maskPhone(dto.getTelephoneLead()), maskEmail(dto.getEmails()), hasText(dto.getCityLead()),
                dto.getOperatorId(), dto.getManagerId(), dto.getMarketologId(), dto.getTelephoneId());

        if (dto.getTelephoneLead() == null || dto.getTelephoneLead().isBlank()) {
            log.warn("🟥 [SYNC] Отсутствует telephoneLead в запросе");
            return new Result(400,Map.of("error", "Missing telephoneLead"),null);
        }

        // Ищем по нормализованным вариантам телефона
        List<String> candidates = buildPhoneCandidates(dto.getTelephoneLead());
        Lead lead = targetId!=null ? current(targetId) : findByAnyPhoneCandidate(candidates);

        if (lead == null) {
            log.warn("🟥 [SYNC] Лид не найден по телефонам {}", maskPhones(candidates));
            log.info("==================== [END SYNC UPDATE] ====================\n");
            return new Result(404,Map.of("error","Lead not found","telephoneLead",dto.getTelephoneLead()),null);
        }

        lead = current(lead.getId());
        Lead oldCopy = cloneLead(lead);
        leadMapper.updateEntity(lead, dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
        leadRepository.save(lead);

        Map<String, String> changes = collectChangedFields(oldCopy, lead);
        log.info("🟩 [SYNC] ✅ Лид #{} обновлён на сервере", lead.getId());
        changes.forEach((k, v) -> log.info("🔄 [SYNC] {}: {}", k, v));
        log.info("==================== [END SYNC UPDATE] ====================\n");

        return new Result(200,changes,lead.getId());
    }

    // ==================== helpers ====================

    /** Пробуем найти лид по любому из телефонных кандидатов. */
    private Lead findByAnyPhoneCandidate(List<String> candidates) {
        for (String phone : candidates) {
            Optional<Lead> opt = leadRepository.findByTelephoneLead(phone);
            if (opt.isPresent()) return opt.get();
        }
        return null;
    }

    /** Генерируем набор «кандидатов» телефона на всякий случай: 79..., +79..., 89..., +89..., как прислали. */
    private List<String> buildPhoneCandidates(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("8")) digits = "7" + digits.substring(1);

        List<String> list = new ArrayList<>();
        list.add(digits);           // 79...
        list.add("+" + digits);     // +79...
        list.add(raw);              // как прислали
        if (digits.startsWith("7")) {
            list.add("8" + digits.substring(1));   // 89...
            list.add("+8" + digits.substring(1));  // +89...
        }
        return list.stream().distinct().toList();
    }

    private Lead cloneLead(Lead original) {
        return Lead.builder()
                .id(original.getId())
                .telephoneLead(original.getTelephoneLead())
                .companyName(original.getCompanyName())
                .phones(original.getPhones())
                .mobilePhones(original.getMobilePhones())
                .whatsappPhones(original.getWhatsappPhones())
                .emails(original.getEmails())
                .websites(original.getWebsites())
                .vkUrl(original.getVkUrl())
                .telegramUrl(original.getTelegramUrl())
                .industries(original.getIndustries())
                .companyType(original.getCompanyType())
                .region(original.getRegion())
                .address(original.getAddress())
                .cityLead(original.getCityLead())
                .commentsLead(original.getCommentsLead())
                .lidStatus(original.getLidStatus())
                .createDate(original.getCreateDate())
                .updateStatus(original.getUpdateStatus())
                .dateNewTry(original.getDateNewTry())
                .offer(original.isOffer())
                .manager(original.getManager())
                .operator(original.getOperator())
                .marketolog(original.getMarketolog())
                .telephone(original.getTelephone())
                .lastSeen(original.getLastSeen())
                .build();
    }

    private Map<String, String> collectChangedFields(Lead oldLead, Lead newLead) {
        Map<String, String> changes = new LinkedHashMap<>();
        if (!Objects.equals(oldLead.getTelephoneLead(), newLead.getTelephoneLead()))
            changes.put("Телефон", maskPhone(oldLead.getTelephoneLead()) + " -> " + maskPhone(newLead.getTelephoneLead()));
        if (!Objects.equals(oldLead.getCompanyName(), newLead.getCompanyName()))
            changes.put("Компания", oldLead.getCompanyName() + " -> " + newLead.getCompanyName());
        if (!Objects.equals(oldLead.getPhones(), newLead.getPhones()))
            changes.put("Телефоны", "changed");
        if (!Objects.equals(oldLead.getMobilePhones(), newLead.getMobilePhones()))
            changes.put("Мобильные", "changed");
        if (!Objects.equals(oldLead.getWhatsappPhones(), newLead.getWhatsappPhones()))
            changes.put("WhatsApp", "changed");
        if (!Objects.equals(oldLead.getEmails(), newLead.getEmails()))
            changes.put("Email", maskEmail(oldLead.getEmails()) + " -> " + maskEmail(newLead.getEmails()));
        if (!Objects.equals(oldLead.getWebsites(), newLead.getWebsites()))
            changes.put("Сайты", oldLead.getWebsites() + " -> " + newLead.getWebsites());
        if (!Objects.equals(oldLead.getVkUrl(), newLead.getVkUrl()))
            changes.put("VK", oldLead.getVkUrl() + " -> " + newLead.getVkUrl());
        if (!Objects.equals(oldLead.getTelegramUrl(), newLead.getTelegramUrl()))
            changes.put("TG", oldLead.getTelegramUrl() + " -> " + newLead.getTelegramUrl());
        if (!Objects.equals(oldLead.getIndustries(), newLead.getIndustries()))
            changes.put("Отрасли", oldLead.getIndustries() + " -> " + newLead.getIndustries());
        if (!Objects.equals(oldLead.getCompanyType(), newLead.getCompanyType()))
            changes.put("Тип", oldLead.getCompanyType() + " -> " + newLead.getCompanyType());
        if (!Objects.equals(oldLead.getRegion(), newLead.getRegion()))
            changes.put("Регион", oldLead.getRegion() + " -> " + newLead.getRegion());
        if (!Objects.equals(oldLead.getAddress(), newLead.getAddress()))
            changes.put("Адрес", "changed");
        if (!Objects.equals(oldLead.getCityLead(), newLead.getCityLead()))
            changes.put("Город", oldLead.getCityLead() + " -> " + newLead.getCityLead());
        if (!Objects.equals(oldLead.getCommentsLead(), newLead.getCommentsLead()))
            changes.put("Комментарий", "changed");
        if (!Objects.equals(oldLead.getLidStatus(), newLead.getLidStatus()))
            changes.put("Статус", oldLead.getLidStatus() + " -> " + newLead.getLidStatus());
        if (!Objects.equals(oldLead.getManager(), newLead.getManager()))
            changes.put("Менеджер", safeUserId(oldLead.getManager()) + " -> " + safeUserId(newLead.getManager()));
        if (!Objects.equals(oldLead.getOperator(), newLead.getOperator()))
            changes.put("Оператор", safeUserId(oldLead.getOperator()) + " -> " + safeUserId(newLead.getOperator()));
        if (!Objects.equals(oldLead.getMarketolog(), newLead.getMarketolog()))
            changes.put("Маркетолог", safeUserId(oldLead.getMarketolog()) + " -> " + safeUserId(newLead.getMarketolog()));
        if (!Objects.equals(oldLead.getLastSeen(), newLead.getLastSeen()))
            changes.put("Last Seen", String.valueOf(oldLead.getLastSeen()) + " -> " + newLead.getLastSeen());
        return changes;
    }

    private String safeUserId(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Manager m) return "Manager#" + m.getId();
        if (obj instanceof Operator o) return "Operator#" + o.getId();
        if (obj instanceof Marketolog mk) return "Marketolog#" + mk.getId();
        return "unknown";
    }
}
