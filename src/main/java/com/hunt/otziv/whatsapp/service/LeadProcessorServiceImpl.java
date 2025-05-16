package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import com.hunt.otziv.whatsapp.service.service.LeadProcessorService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


@Service
@Slf4j
@RequiredArgsConstructor
public class LeadProcessorServiceImpl implements LeadProcessorService {

    private final LeadsRepository leadRepository;
    private final WhatsAppService whatsAppService;
    private final AdminNotifierService adminNotifierService; // уведомление в Telegram
    private final WhatsAppProperties properties;
    @Lazy
    private final LeadSenderServiceImpl leadSenderService; // для остановки планировщика

    private static final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean notificationSent = new AtomicBoolean(false);

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_SENT = "К рассылке";

    private final AtomicInteger totalSentMessages = new AtomicInteger(0);


    @Transactional
    @Override
    public void processLead(WhatsAppProperties.ClientConfig client) {
        Long telephoneId = Long.valueOf(client.getId().replace("client", ""));
        log.info("telephoneId: {}", telephoneId);

        Optional<Lead> leadOpt = leadRepository
                .findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
                        telephoneId,
                        STATUS_NEW,
                        LocalDate.now()
                );

        if (leadOpt.isEmpty()) {
            log.info("🔁 Нет новых лидов для телефона {} ({}). Планировщик завершится", telephoneId, client.getId());
            finishedClients.add(client.getId());
            leadSenderService.stopClientScheduler(client.getId()); // 💥 остановка планировщика
            checkAllClientsFinished();
            return;
        }

        Lead lead = leadOpt.get();
        log.info("lead: {}", lead);

        String message = "Здравствуйте! У нас есть предложение...";
        String result = whatsAppService.sendMessage(client.getId(), normalizePhone(lead.getTelephoneLead()), message);

        log.info("📤 Сообщение отправлено: {}", result);

        // Изменяем статус лида только при успешной отправке
        if (result != null && !result.isBlank() && result.contains("ok")) {
            lead.setLidStatus(STATUS_SENT);
            lead.setUpdateStatus(LocalDate.now());
            leadRepository.save(lead);
            totalSentMessages.incrementAndGet(); // ✅ увеличиваем счётчик
        } else {
            log.warn("⚠️ Сообщение не было отправлено, статус лида не изменён");
        }
    }

    private void checkAllClientsFinished() {
        // Проверка, что все клиенты завершили рассылку
        if (!notificationSent.get() && finishedClients.size() == properties.getClients().size()) {
            notificationSent.set(true);
            String message = "✅ Все клиенты завершили рассылку лидов. Всего отправлено сообщений: " + totalSentMessages.get();
            log.info(message);
            adminNotifierService.notifyAdmin(message);
        }
    }

    private String normalizePhone(String rawPhone) {
        String digits = rawPhone.replaceAll("[^\\d]", "");
        if (digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }
        return digits;
    }
} // конец LeadProcessorServiceImpl

