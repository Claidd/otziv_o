package com.hunt.otziv.l_lead.services.serv;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
public class VpsSyncService {

    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final LeadSyncQueueRepository syncQueueRepo;
    private final LeadMapper leadMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Должен указывать на /api/leads/sync */
    @Value("${lead.vps.url}") // напр. https://o-ogo.ru/api/leads/sync
    private String vpsSyncUrl;

    @Value("${lead.vps.retry.batchSize:100}")
    private int retryBatchSize;

    @Value("${lead.vps.retry.maxAttempts:20}")
    private int maxAttempts;

    /** Простая асинхронная отправка, без afterCommit-магии, как раньше. */
    @Async
    public void sendLeadAsync(Lead lead) {
        try {
            sendLeadToVps(lead);
        } catch (Exception e) {
            log.warn("⚠ Не удалось отправить лид {} на VPS: {}. Сохраняем в очередь.",
                    lead.getTelephoneLead(), safeErr(e));

            LeadSyncQueue queued = LeadSyncQueue.builder()
                    .leadId(lead.getId())
                    .telephoneLead(normalizePhone(lead.getTelephoneLead()))
                    .lidStatus(lead.getLidStatus())
                    .lastSeen(lead.getLastSeen())
                    .retryCount(0)
                    .lastError(safeErr(e))
                    .lastAttemptAt(LocalDateTime.now())
                    .payloadJson(toJsonQuiet(toTransferDtoNormalized(lead))) // сохраняем полезную нагрузку
                    .build();

            syncQueueRepo.save(queued);
        }
    }

    private void sendLeadToVps(Lead lead) {
        sendDtoToVps(toTransferDtoNormalized(lead));
    }

    /** Приводим данные к тому же формату, который ждёт VPS. */
    private LeadDtoTransfer toTransferDtoNormalized(Lead lead) {
        LeadDtoTransfer dto = leadMapper.toDtoTransfer(lead);
        if (dto.getTelephoneLead() != null) {
            dto.setTelephoneLead(normalizePhone(dto.getTelephoneLead()));
        }
        // как раньше — просто ставим текущее время (можно оставить UTC, если так удобнее на VPS)
        dto.setUpdateStatus(LocalDateTime.now(ZoneId.of("UTC")));
        return dto;
    }

    private void sendDtoToVps(LeadDtoTransfer dto) {
        String token = jwtService.generateSyncToken(); // subject = "lead-sync"
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<LeadDtoTransfer> entity = new HttpEntity<>(dto, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    vpsSyncUrl, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("VPS responded with " + response.getStatusCode()
                        + " body=" + response.getBody());
            }

            // 🔊 явный лог успеха — этого в новой версии не хватало
            log.info("🟢 [VPS SYNC OK] {} lastSeen={}, status={}, resp={}",
                    dto.getTelephoneLead(), dto.getLastSeen(), dto.getLidStatus(), response.getBody());

        } catch (RestClientResponseException e) {
            // отдаём наверх, чтобы sendLeadAsync положил запись в очередь
            throw new IllegalStateException("VPS error: " + e.getStatusCode().value()
                    + " body=" + e.getResponseBodyAsString(), e);
        }
    }

    /** Ретраи как раньше: периодически вытаскиваем из очереди и шлём заново. */
    @Scheduled(fixedRateString = "${lead.vps.retry.period.ms:1800000}") // 30 минут по умолчанию
    public void retryFailedSync() {
        List<LeadSyncQueue> pending = syncQueueRepo.findTopOrderByLastAttemptAtAsc(retryBatchSize);
        if (pending.isEmpty()) return;

        log.info("🔄 [VPS] Повторная отправка {} лидов", pending.size());

        for (LeadSyncQueue queued : pending) {
            if (queued.getRetryCount() >= maxAttempts) {
                log.warn("⏭ Пропускаю {} — достигнут предел попыток ({})",
                        queued.getTelephoneLead(), queued.getRetryCount());
                continue;
            }

            try {
                LeadDtoTransfer dto;
                if (queued.getPayloadJson() != null && !queued.getPayloadJson().isBlank()) {
                    dto = objectMapper.readValue(queued.getPayloadJson(), LeadDtoTransfer.class);
                } else {
                    // запасной вариант для старых записей
                    dto = new LeadDtoTransfer();
                    dto.setTelephoneLead(normalizePhone(queued.getTelephoneLead()));
                    dto.setLidStatus(queued.getLidStatus());
                    dto.setLastSeen(queued.getLastSeen());
                    dto.setUpdateStatus(LocalDateTime.now(ZoneId.of("UTC")));
                }

                sendDtoToVps(dto);

                syncQueueRepo.delete(queued);
                log.info("✅ Повторная отправка успешна: {}", dto.getTelephoneLead());

            } catch (Exception e) {
                queued.setRetryCount(queued.getRetryCount() + 1);
                queued.setLastAttemptAt(LocalDateTime.now());
                queued.setLastError(safeErr(e));
                syncQueueRepo.save(queued);

                log.warn("⚠ Повторная отправка лида {} не удалась ({} попыток): {}",
                        queued.getTelephoneLead(), queued.getRetryCount(), safeErr(e));
            }
        }
    }

    // ==== утилиты ====

    private String normalizePhone(String raw) {
        String d = raw.replaceAll("\\D+", "");
        return (d.length() == 11 && d.startsWith("8")) ? "7" + d.substring(1) : d;
    }

    private String toJsonQuiet(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    private String safeErr(Exception e) {
        String m = e.getMessage();
        return (m != null && m.length() > 500) ? m.substring(0, 500) + "…" : String.valueOf(m);
    }
}








//@Service
//@Slf4j
//@RequiredArgsConstructor
//public class VpsSyncService {
//
//    private final RestTemplate restTemplate;
//    private final JwtService jwtService;
//    private final LeadSyncQueueRepository syncQueueRepo;
//    private final LeadMapper leadMapper;
//
//    @Value("${lead.vps.url}") // https://o-ogo.ru/api/leads/sync
//    private String vpsSyncUrl;
//
//    @Async
//    public void sendLeadAsync(Lead lead) {
//        try {
//            sendLeadToVps(lead);
//        } catch (Exception e) {
//            log.warn("⚠ Не удалось отправить лид {} на VPS: {}. Сохраняем в очередь.",
//                    lead.getTelephoneLead(), e.getMessage());
//
//            LeadSyncQueue queued = LeadSyncQueue.builder()
//                    .leadId(lead.getId())
//                    .telephoneLead(lead.getTelephoneLead())
//                    .lidStatus(lead.getLidStatus())
//                    .lastSeen(lead.getLastSeen())
//                    .retryCount(0)
//                    .lastError(e.getMessage())
//                    .build();
//
//            syncQueueRepo.save(queued);
//        }
//    }
//
//    private void sendLeadToVps(Lead lead) {
//        String token = jwtService.generateSyncToken();
//        HttpHeaders headers = new HttpHeaders();
//        headers.setBearerAuth(token);
//        headers.setContentType(MediaType.APPLICATION_JSON);
//
//        LeadDtoTransfer dto = leadMapper.toDtoTransfer(lead);
//        dto.setUpdateStatus(LocalDateTime.now());
//
//        HttpEntity<LeadDtoTransfer> entity = new HttpEntity<>(dto, headers);
//        ResponseEntity<String> response = restTemplate.exchange(vpsSyncUrl, HttpMethod.POST, entity, String.class);
//
//        log.info("🌐 [VPS] Лид {} синхронизирован. Ответ: {}", lead.getTelephoneLead(), response.getBody());
//    }
//
//    @Scheduled(fixedRate = 30 * 60 * 1000)
//    public void retryFailedSync() {
//        List<LeadSyncQueue> pending = (List<LeadSyncQueue>) syncQueueRepo.findAll();
//        if (pending.isEmpty()) return;
//
//        log.info("🔄 [VPS] Повторная отправка {} лидов", pending.size());
//        for (LeadSyncQueue queued : pending) {
//            try {
//                Lead lead = new Lead();
//                lead.setId(queued.getLeadId());
//                lead.setTelephoneLead(queued.getTelephoneLead());
//                lead.setLidStatus(queued.getLidStatus());
//                lead.setLastSeen(queued.getLastSeen());
//                lead.setUpdateStatus(LocalDateTime.now());
//
//                sendLeadToVps(lead);
//                syncQueueRepo.delete(queued);
//            } catch (Exception e) {
//                queued.setRetryCount(queued.getRetryCount() + 1);
//                queued.setLastAttemptAt(LocalDateTime.now());
//                queued.setLastError(e.getMessage());
//                syncQueueRepo.save(queued);
//
//                log.warn("⚠ Повторная отправка лида {} не удалась ({} попыток): {}",
//                        queued.getTelephoneLead(), queued.getRetryCount(), e.getMessage());
//            }
//        }
//    }
//}




