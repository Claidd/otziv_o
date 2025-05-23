package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Marketolog;
import com.hunt.otziv.u_users.model.Operator;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// README-инструкция (для команды)
/**
 * 📦 Система синхронизации лидов между локальной и серверной базой данных
 *
 * 🔁 Односторонняя синхронизация локалка → сервер:
 * - Использует события @TransactionalEventListener и LeadEventPublisher
 * - После сохранения лида вызывается publishUpdate(lead)
 * - Листенер собирает DTO и отправляет PATCH-запрос на сервер
 * - Сервер принимает DTO, обновляет сущность в БД через LeadMapper
 *
 * 🔄 Двусторонняя синхронизация сервер → локалка:
 * - Каждые 5 минут локалка запрашивает изменённые лиды с помощью GET /api/leads/modified?since=timestamp
 * - Получает массив LeadDtoTransfer[] и обновляет/вставляет в свою БД
 * - Использует JWT с subject="lead-sync"
 *
 * ✅ Безопасность:
 * - Все PATCH и GET защищены JWT-токенами (JwtAuthFilter)
 * - Импорт с checksum-защитой (по желанию)
 *
 * 💡 Примечание:
 * - Лид должен иметь уникальный telephoneLead для upsert
 * - Все изменения автоматически отслеживаются и синхронизируются
 */
@Slf4j
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadSyncController {

    private final LeadsRepository leadRepository;
    private final LeadMapper leadMapper;
    private final OperatorRepository operatorRepo;
    private final ManagerRepository managerRepo;
    private final MarketologRepository marketologRepo;
    private final TelephoneRepository telephoneRepo;

    @PostMapping(value = "/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> updateLead(@RequestBody LeadUpdateDto dto) {
        log.info("📥 Получен LeadUpdateDto: {}", dto);

        if (dto.getLeadId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing leadId"));
        }

        Lead lead = leadRepository.findById(dto.getLeadId()).orElse(null);
        if (lead == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Lead not found"));
        }

        Lead oldCopy = cloneLead(lead);
        leadMapper.updateEntity(lead, dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
        leadRepository.save(lead);

        Map<String, String> changes = collectChangedFields(oldCopy, lead);
        log.info("✅ Лид #{} обновлён на сервере", lead.getId());
        changes.forEach((key, value) -> log.info("🔄 {}: {}", key, value));

        return ResponseEntity.ok(changes);
    }

    private Lead cloneLead(Lead original) {
        return Lead.builder()
                .id(original.getId())
                .telephoneLead(original.getTelephoneLead())
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
                .build();
    }



    private Map<String, String> collectChangedFields(Lead oldLead, Lead newLead) {
        Map<String, String> changes = new LinkedHashMap<>();

        if (!Objects.equals(oldLead.getTelephoneLead(), newLead.getTelephoneLead())) {
            String change = oldLead.getTelephoneLead() + " → " + newLead.getTelephoneLead();
            changes.put("📞 Телефон", change);
            log.info("📞 Телефон: {}", change);
        }

        if (!Objects.equals(oldLead.getCityLead(), newLead.getCityLead())) {
            String change = oldLead.getCityLead() + " → " + newLead.getCityLead();
            changes.put("🌆 Город", change);
            log.info("🌆 Город: {}", change);
        }

        if (!Objects.equals(oldLead.getCommentsLead(), newLead.getCommentsLead())) {
            String change = oldLead.getCommentsLead() + " → " + newLead.getCommentsLead();
            changes.put("💬 Комментарий", change);
            log.info("💬 Комментарий: {}", change);
        }

        if (!Objects.equals(oldLead.getLidStatus(), newLead.getLidStatus())) {
            String change = oldLead.getLidStatus() + " → " + newLead.getLidStatus();
            changes.put("📋 Статус", change);
            log.info("📋 Статус: {}", change);
        }


        if (!Objects.equals(oldLead.getManager(), newLead.getManager())) {
            String change = safeUserId(oldLead.getManager()) + " → " + safeUserId(newLead.getManager());
            changes.put("🧑‍💼 Менеджер", change);
            log.info("🧑‍💼 Менеджер: {}", change);
        }

        if (!Objects.equals(oldLead.getOperator(), newLead.getOperator())) {
            String change = safeUserId(oldLead.getOperator()) + " → " + safeUserId(newLead.getOperator());
            changes.put("🎧 Оператор", change);
            log.info("🎧 Оператор: {}", change);
        }

        if (!Objects.equals(oldLead.getMarketolog(), newLead.getMarketolog())) {
            String change = safeUserId(oldLead.getMarketolog()) + " → " + safeUserId(newLead.getMarketolog());
            changes.put("📈 Маркетолог", change);
            log.info("📈 Маркетолог: {}", change);
        }

        return changes;
    }


    private void logChangedFields(Lead oldLead, Lead newLead) {
        if (!Objects.equals(oldLead.getTelephoneLead(), newLead.getTelephoneLead())) {
            log.info("📞 Телефон: {} → {}", oldLead.getTelephoneLead(), newLead.getTelephoneLead());
        } else {
            log.debug("📞 Телефон не изменился: {}", oldLead.getTelephoneLead());
        }

        if (!Objects.equals(oldLead.getCityLead(), newLead.getCityLead())) {
            log.info("🌆 Город: {} → {}", oldLead.getCityLead(), newLead.getCityLead());
        } else {
            log.debug("🌆 Город не изменился: {}", oldLead.getCityLead());
        }

        if (!Objects.equals(oldLead.getLidStatus(), newLead.getLidStatus())) {
            log.info("📋 Статус: {} → {}", oldLead.getLidStatus(), newLead.getLidStatus());
        } else {
            log.debug("📋 Статус не изменился: {}", oldLead.getLidStatus());
        }

        if (!Objects.equals(oldLead.getCommentsLead(), newLead.getCommentsLead())) {
            log.info("💬 Комментарий: {} → {}", oldLead.getCommentsLead(), newLead.getCommentsLead());
        }else {
            log.debug("📋 Комментарий не изменился: {}", oldLead.getCommentsLead());
        }

        if (!Objects.equals(oldLead.getUpdateStatus(), newLead.getUpdateStatus()))
            log.info("🕒 Дата обновления: {} → {}", oldLead.getUpdateStatus(), newLead.getUpdateStatus());

        if (!Objects.equals(oldLead.getManager(), newLead.getManager()))
            log.info("🧑‍💼 Менеджер: {} → {}", safeUserId(oldLead.getManager()), safeUserId(newLead.getManager()));

        if (!Objects.equals(oldLead.getOperator(), newLead.getOperator()))
            log.info("🎧 Оператор: {} → {}", safeUserId(oldLead.getOperator()), safeUserId(newLead.getOperator()));

        if (!Objects.equals(oldLead.getMarketolog(), newLead.getMarketolog()))
            log.info("📈 Маркетолог: {} → {}", safeUserId(oldLead.getMarketolog()), safeUserId(newLead.getMarketolog()));
    }


    private String safeUserId(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Manager manager) return "Manager#" + manager.getId();
        if (obj instanceof Operator operator) return "Operator#" + operator.getId();
        if (obj instanceof Marketolog marketolog) return "Marketolog#" + marketolog.getId();
        return "unknown";
    }
}