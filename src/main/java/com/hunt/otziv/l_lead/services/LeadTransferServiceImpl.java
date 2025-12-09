package com.hunt.otziv.l_lead.services;

import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import com.hunt.otziv.l_lead.dto.LeadUpdateDto;
import com.hunt.otziv.l_lead.mapper.LeadMapper;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.l_lead.services.serv.LeadTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadTransferServiceImpl implements LeadTransferService {

    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final LeadService leadService;
    private final LeadMapper leadMapper;


    @Value("${lead.transfer.url}") // https://o-ogo.ru/api/leads/import
    private String remoteUrl;

    @Value("${lead.update.url}") // https://o-ogo.ru/api/leads/update
    private String remoteUpdateUrl;

    public void sendLeadToServer(Long leadId) {
        LeadDtoTransfer dto = leadService.findByIdToTransfer(leadId);
        String token = jwtService.generateToken(dto);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        HttpEntity<LeadDtoTransfer> request = new HttpEntity<>(dto, headers);
        restTemplate.postForEntity(remoteUrl, request, String.class);
    }



    public void sendLeadUpdate(Lead lead) {
        LeadUpdateDto dto = leadMapper.toUpdateDto(lead);
        String token = jwtService.generateSyncToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON)); // 🔧 Явно ожидаем JSON
        headers.setBearerAuth(token);

        HttpEntity<LeadUpdateDto> entity = new HttpEntity<>(dto, headers);

        log.info("📤 Отправка обновлённого лида на сервер...");
//        log.info("🔎 Lead ID: {}", lead.getId());
//        log.info("📞 Телефон: {}", lead.getTelephoneLead());
//        log.info("📍 Город: {}", lead.getCityLead());
//        log.info("📋 Статус: {}", lead.getLidStatus());
//        log.info("📅 Обновлён: {}", lead.getUpdateStatus());

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("🚀 Попытка {}: отправка POST-запроса на {}", attempt, remoteUpdateUrl);

                ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                        remoteUpdateUrl,
                        HttpMethod.POST,
                        entity,
                        new ParameterizedTypeReference<>() {}
                );

                HttpStatus status = (HttpStatus) response.getStatusCode();
                if (status.is2xxSuccessful()) {
                    log.info("✅ Успех! Сервер ответил: {} {}", status.value(), status.getReasonPhrase());

                    Map<String, String> changes = response.getBody();
                    if (changes != null && !changes.isEmpty()) {
                        log.info("📥 Получены изменения после обновления:");
                        changes.forEach((key, value) -> log.info("🔄 {}: {}", key, value));
                    }
                    return;
                } else {
                    log.warn("⚠️ Сервер вернул неожиданный статус: {} {}", status.value(), status.getReasonPhrase());
                    if (status.is3xxRedirection()) {
                        log.error("🔁 Получен редирект ({}), это ошибка!", status.value());
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ Ошибка при попытке {} отправки лида #{}: {}", attempt, lead.getId(), e.getMessage());
            }

            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException ignored) {
                log.warn("⏸ Ожидание между попытками было прервано");
            }
        }

        log.error("❌ Не удалось отправить лид #{} после 3 попыток", lead.getId());
    }




//    leadEventPublisher.publishUpdate(lead, "В работе", lead.getManager(), null);

}
