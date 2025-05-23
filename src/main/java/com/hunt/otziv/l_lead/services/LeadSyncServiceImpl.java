package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.LeadSyncService;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.repository.MarketologRepository;
import com.hunt.otziv.u_users.repository.OperatorRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lead.sync.outbound.enabled", havingValue = "true", matchIfMissing = false)
public class LeadSyncServiceImpl implements LeadSyncService {

    private final RestTemplate restTemplate;
    private final LeadMapper leadMapper;
    private final LeadService leadService;
    private final OperatorRepository operatorRepo;
    private final ManagerRepository managerRepo;
    private final MarketologRepository marketologRepo;
    private final TelephoneRepository telephoneRepo;
    private final JwtService jwtService;

    @Value("${lead.synchrony.url}") // https://o-ogo.ru/api/leads/modified
    private String remoteSyncUrl;

    @PostConstruct
    public void init() {
        log.info("✅ LeadSyncServiceImpl инициализирован");
    }

    private LocalDateTime lastSync = LocalDateTime.now().minusHours(1); // инициализация

    @Scheduled(fixedRate = 5 * 60 * 1000) // каждые 5 минут
    public void syncModifiedLeads() {
        String url = remoteSyncUrl + "?since=" + lastSync;
        log.info("🔄 Запуск синхронизации лидов: {}", url);

        try {
            String token = jwtService.generateSyncToken();

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<LeadDtoTransfer[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    LeadDtoTransfer[].class
            );

            HttpStatus status = (HttpStatus) response.getStatusCode();
            log.info("📡 Ответ от сервера: {} {}", status.value(), status.getReasonPhrase());

            LeadDtoTransfer[] dtos = response.getBody();
            int count = dtos != null ? dtos.length : 0;

            if (count > 0) {
                log.info("📥 Получено {} лидов. Примеры: {}", count,
                        Arrays.stream(dtos)
                                .limit(3)
                                .map(LeadDtoTransfer::getTelephoneLead)
                                .toList());

                for (LeadDtoTransfer dto : dtos) {
                    Lead lead = leadMapper.toEntity(dto, operatorRepo, managerRepo, marketologRepo, telephoneRepo);
                    leadService.saveOrUpdateByTelephoneLead(lead);
                }

                log.info("✅ Импортировано {} лидов с сервера", count);
            } else {
                log.info("📭 Нет новых лидов для импорта");
            }

            lastSync = LocalDateTime.now(); // ✅ Обновляем точку синхронизации только после обработки

        } catch (Exception e) {
            log.error("❌ Ошибка синхронизации лидов с сервера: {}", e.getMessage(), e);
        }
    }

}

