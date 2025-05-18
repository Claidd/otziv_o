package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.LeadService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class OfferService {

    private final WhatsAppService whatsAppService;
    private final LeadService leadService;

    // Потокобезопасный набор: номера в процессе отправки
    private final Set<String> phonesInProgress = ConcurrentHashMap.newKeySet();

    @Async
    public void sendOfferAsync(Lead lead, String clientId, String telephoneNumber, String offerText) {
        if (!phonesInProgress.add(telephoneNumber)) {
            log.info("⚠️ Оффер для {} уже в процессе. Повтор блокирован.", telephoneNumber);
            return;
        }

        try {
            Thread.sleep(10_000); // Задержка перед отправкой
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⏰ Ожидание прервано для {}", telephoneNumber);
        }

        try {
            String result = whatsAppService.sendMessage(clientId, telephoneNumber, offerText);

            if (result != null && result.contains("ok")) {
                log.info("✅ Оффер успешно отправлен клиенту {}", telephoneNumber);
                lead.setOffer(true);
                leadService.saveLead(lead);
            } else {
                log.warn("❌ Ошибка при отправке оффера клиенту {}", telephoneNumber);
            }
        } finally {
            phonesInProgress.remove(telephoneNumber); // очищаем даже при ошибке
        }
    }
}
