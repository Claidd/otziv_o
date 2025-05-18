package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import com.hunt.otziv.whatsapp.service.service.LeadProcessorService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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
    private final ObjectProvider<LeadSenderServiceImpl> leadSenderServiceProvider; // безопасное получение


    private static final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean notificationSent = new AtomicBoolean(false);

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_SENT = "К рассылке";

    private final AtomicInteger totalSentMessages = new AtomicInteger(0);
    private List<WhatsAppProperties.ClientConfig> operatorClients;

    @Transactional
    @Override
    public void processLead(WhatsAppProperties.ClientConfig client) {
        Long telephoneId = Long.valueOf(client.getId().replace("client", ""));
        log.info("telephoneId: {}", telephoneId);

        // 🆕 Инициализация operatorClients один раз
        if (operatorClients == null) {
            operatorClients = leadSenderServiceProvider.getIfAvailable().getActiveOperatorClients();
        }

        Optional<Lead> leadOpt = leadRepository
                .findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
                        telephoneId,
                        STATUS_NEW,
                        LocalDate.now()
                );

        if (leadOpt.isEmpty()) {
            log.info("🔁 Нет новых лидов для телефона {} ({}). Планировщик завершится", telephoneId, client.getId());
            finishedClients.add(client.getId());
            Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId()); // 💥 остановка планировщика
            checkAllClientsFinished();
            return;
        }

        Lead lead = leadOpt.get();
        log.info("lead: {}", lead);

        String message = lead.getTelephone().getBeginText();
        String result = whatsAppService.sendMessage(client.getId(), normalizePhone(lead.getTelephoneLead()), message);

        log.info("📤 Сообщение отправлено: {}", result);

        if (result != null && !result.isBlank() && result.contains("ok")) {
            lead.setLidStatus(STATUS_SENT);
            lead.setUpdateStatus(LocalDate.now());
            leadRepository.save(lead);
            totalSentMessages.incrementAndGet();
        } else {
            log.warn("⚠️ Сообщение не было отправлено, статус лида не изменён");
        }
    }


    private void checkAllClientsFinished() {
        if (operatorClients == null) return;

        if (!notificationSent.get() && finishedClients.size() == operatorClients.size()) {
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

