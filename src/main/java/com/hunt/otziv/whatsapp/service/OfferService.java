package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class OfferService {

    private final WhatsAppService whatsAppService;
    private final LeadService leadService;
    private final LeadEventPublisher leadEventPublisher;

    // Потокобезопасный набор: номера в процессе отправки
    private final Set<String> phonesInProgress = ConcurrentHashMap.newKeySet();

    @Async
    public void sendOfferAsync(Lead lead, String clientId, String telephoneNumber, String offerText) {
        if (!phonesInProgress.add(telephoneNumber)) {
            log.info("⚠️ Оффер для {} уже в процессе. Повтор блокирован.", telephoneNumber);
            return;
        }

        try {
            delayBeforeSending(telephoneNumber);

            String result = whatsAppService.sendMessage(clientId, telephoneNumber, offerText);

            if (result != null && result.contains("ok")) {
                log.info("✅ Оффер успешно отправлен клиенту {}", telephoneNumber);
                lead.setOffer(true);
                leadService.saveLead(lead);
                leadEventPublisher.publishUpdate(lead);
            } else {
                log.warn("❌ Ошибка при отправке оффера клиенту {}", telephoneNumber);
            }
        } finally {
            phonesInProgress.remove(telephoneNumber);
        }
    }

    private void delayBeforeSending(String telephoneNumber) {
        int delaySeconds = ThreadLocalRandom.current().nextInt(10, 61); // 10–60 сек
        log.info("⏳ Ждём {} секунд перед отправкой оффера клиенту {}", delaySeconds, telephoneNumber);
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⏰ Ожидание прервано для {}", telephoneNumber);
        }
    }

}
