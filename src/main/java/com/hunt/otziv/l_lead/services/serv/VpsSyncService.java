package com.hunt.otziv.l_lead.services.serv;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadSyncQueue;
import com.hunt.otziv.l_lead.repository.LeadSyncQueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
@Service
@Slf4j
@RequiredArgsConstructor
public class VpsSyncService {

    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final LeadSyncQueueRepository syncQueueRepo;
    private final LeadMapper leadMapper;

    @Value("${lead.vps.url}") // https://o-ogo.ru/api/leads/sync
    private String vpsSyncUrl;

    @Async
    public void sendLeadAsync(Lead lead) {
        try {
            sendLeadToVps(lead);
        } catch (Exception e) {
            log.warn("⚠ Не удалось отправить лид {} на VPS: {}. Сохраняем в очередь.",
                    lead.getTelephoneLead(), e.getMessage());

            LeadSyncQueue queued = LeadSyncQueue.builder()
                    .leadId(lead.getId())
                    .telephoneLead(lead.getTelephoneLead())
                    .lidStatus(lead.getLidStatus())
                    .lastSeen(lead.getLastSeen())
                    .retryCount(0)
                    .lastError(e.getMessage())
                    .build();

            syncQueueRepo.save(queued);
        }
    }

    private void sendLeadToVps(Lead lead) {
        String token = jwtService.generateSyncToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        LeadDtoTransfer dto = leadMapper.toDtoTransfer(lead);
        dto.setUpdateStatus(LocalDateTime.now());

        HttpEntity<LeadDtoTransfer> entity = new HttpEntity<>(dto, headers);
        ResponseEntity<String> response = restTemplate.exchange(vpsSyncUrl, HttpMethod.POST, entity, String.class);

        log.info("🌐 [VPS] Лид {} синхронизирован. Ответ: {}", lead.getTelephoneLead(), response.getBody());
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void retryFailedSync() {
        List<LeadSyncQueue> pending = (List<LeadSyncQueue>) syncQueueRepo.findAll();
        if (pending.isEmpty()) return;

        log.info("🔄 [VPS] Повторная отправка {} лидов", pending.size());
        for (LeadSyncQueue queued : pending) {
            try {
                Lead lead = new Lead();
                lead.setId(queued.getLeadId());
                lead.setTelephoneLead(queued.getTelephoneLead());
                lead.setLidStatus(queued.getLidStatus());
                lead.setLastSeen(queued.getLastSeen());
                lead.setUpdateStatus(LocalDateTime.now());

                sendLeadToVps(lead);
                syncQueueRepo.delete(queued);
            } catch (Exception e) {
                queued.setRetryCount(queued.getRetryCount() + 1);
                queued.setLastAttemptAt(LocalDateTime.now());
                queued.setLastError(e.getMessage());
                syncQueueRepo.save(queued);

                log.warn("⚠ Повторная отправка лида {} не удалась ({} попыток): {}",
                        queued.getTelephoneLead(), queued.getRetryCount(), e.getMessage());
            }
        }
    }
}




