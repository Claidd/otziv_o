package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.StatDto;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import com.hunt.otziv.whatsapp.service.service.LeadProcessorService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


@Service
@Slf4j
@RequiredArgsConstructor
public class LeadProcessorServiceImpl implements LeadProcessorService {

    @Value("${whatsapp.ban-protection.maxFailures:5}")
    private int maxFailures;

    @Value("${whatsapp.ban-protection.dailyLimit:30}")
    private int dailyMessageLimit;

    @Value("${whatsapp.ban-protection.minDelay:5}")
    private int minDelay;

    @Value("${whatsapp.ban-protection.maxDelay:30}")
    private int maxDelay;

    private LocalTime startTime;
    private LocalTime endTime;

    private final LeadsRepository leadRepository;
    private final WhatsAppService whatsAppService;
    private final AdminNotifierService adminNotifierService;
    private final ObjectProvider<LeadSenderServiceImpl> leadSenderServiceProvider;

    private static final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean notificationSent = new AtomicBoolean(false);

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_SENT = "К рассылке";

    private List<WhatsAppProperties.ClientConfig> operatorClients;

    private final Map<String, AtomicInteger> failedAttemptsPerClient = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> dailyMessageCount = new ConcurrentHashMap<>();

    // счетчик отправок
    private final Map<String, StatDto> statsPerClient = new ConcurrentHashMap<>();

    private final AtomicInteger globalFailureCounter = new AtomicInteger(0);
    private static final int GLOBAL_FAILURE_LIMIT = 10; // например, 10 подряд неудач



    @Transactional
    @Override
    public void processLead(WhatsAppProperties.ClientConfig client) {
        Long telephoneId = Long.valueOf(client.getId().replace("client", ""));
        log.info("📞 telephoneId: {}", telephoneId);

        if (operatorClients == null) {
            operatorClients = leadSenderServiceProvider.getIfAvailable().getActiveOperatorClients();
        }

        Optional<Lead> leadOpt = leadRepository
                .findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
                        telephoneId, STATUS_NEW, LocalDate.now());

        if (leadOpt.isEmpty()) {
            log.info("🔁 Нет новых лидов для телефона {} ({}). Планировщик завершится", telephoneId, client.getId());
            finishedClients.add(client.getId());
            Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId());
            checkAllClientsFinished();
            return;
        }

        Lead lead = leadOpt.get();
        log.info("📩 Обрабатываем лид: {}", lead);

        int delaySeconds = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
        log.info("⏱ Задержка {} секунд перед отправкой сообщения клиенту {}", delaySeconds, client.getId());

        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("⏰ Ожидание прервано перед отправкой сообщения {}", lead.getTelephoneLead());
        }

        dailyMessageCount.putIfAbsent(client.getId(), new AtomicInteger(0));
        int sentToday = dailyMessageCount.get(client.getId()).incrementAndGet();

        if (sentToday > dailyMessageLimit) {
            log.warn("📛 Превышен лимит {} сообщений в день для клиента {}. Останавливаем.", dailyMessageLimit, client.getId());
            finishedClients.add(client.getId());
            Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId());
            checkAllClientsFinished();
            return;
        }

        String message = lead.getTelephone().getBeginText();
        String result = sendWithRetry(client.getId(), normalizePhone(lead.getTelephoneLead()), message);

        log.info("📤 Ответ от sendMessage: {}", result);

        if (result != null && !result.isBlank() && result.contains("ok")) {
            lead.setLidStatus(STATUS_SENT);
            lead.setUpdateStatus(LocalDate.now());
            leadRepository.save(lead);
            failedAttemptsPerClient.put(client.getId(), new AtomicInteger(0));
            statsPerClient.putIfAbsent(client.getId(), new StatDto(client.getId(), 0, 0, null, null, new HashSet<>()));
            statsPerClient.get(client.getId()).incrementSuccess(lead.getId());
            globalFailureCounter.set(0);

        } else {
            log.warn("⚠️ Неудачная попытка отправки для клиента {}", client.getId());

            failedAttemptsPerClient.putIfAbsent(client.getId(), new AtomicInteger(0));
            int failures = failedAttemptsPerClient.get(client.getId()).incrementAndGet();
            statsPerClient.putIfAbsent(client.getId(), new StatDto(client.getId(), 0, 0, null, null, new HashSet<>()));
            statsPerClient.get(client.getId()).incrementFail(lead.getId());

            int globalFailures = globalFailureCounter.incrementAndGet();
            if (globalFailures >= GLOBAL_FAILURE_LIMIT) {
                log.error("🚨 Обнаружено {} глобальных сбоев. Останавливаем всех клиентов!", globalFailures);
                operatorClients.forEach(c ->
                        Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable())
                                .stopClientScheduler(c.getId())
                );
                adminNotifierService.notifyAdmin("🚨 Глобальный сбой: все клиенты отключены из-за серии ошибок");
            }



            if (failures >= maxFailures) {
                log.error("🚫 Клиент {} достиг лимита ошибок ({}). Останавливаем рассылку.", client.getId(), failures);
                finishedClients.add(client.getId());
                Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId());
                adminNotifierService.notifyAdmin(String.format("🚫 Клиент %s отключён после %d ошибок подряд.", client.getId(), failures));
                checkAllClientsFinished();
            }
        }
    }

    private void checkAllClientsFinished() {
        if (operatorClients == null) return;

        if (!notificationSent.get() && finishedClients.size() == operatorClients.size()) {
            notificationSent.set(true);
            endTime = LocalTime.now(ZoneId.of("Asia/Irkutsk"));

            int totalSuccess = statsPerClient.values().stream().mapToInt(StatDto::getSuccess).sum();
            int totalFail = statsPerClient.values().stream().mapToInt(StatDto::getFail).sum();

            StringBuilder sb = new StringBuilder("\uD83D\uDCC8 Итог рассылки по всем клиентам:\n");

            for (StatDto stat : statsPerClient.values()) {
                sb.append(stat.toReportLine()).append("\n");
            }

            sb.append("\n\uD83D\uDCCA Всего отправлено: ✅ ")
                    .append(totalSuccess)
                    .append(" / ❌ ")
                    .append(totalFail)
                    .append(" (итого: ")
                    .append(totalSuccess + totalFail)
                    .append(")");

            sb.append(" \uD83D\uDD53 Время: с ")
                    .append(startTime != null ? startTime.format(DateTimeFormatter.ofPattern("HH:mm")) : "--:--")
                    .append(" до ")
                    .append(endTime != null ? endTime.format(DateTimeFormatter.ofPattern("HH:mm")) : "--:--");

            log.info(sb.toString());
            adminNotifierService.notifyAdmin(sb.toString());
        }
    }



    private String normalizePhone(String rawPhone) {
        String digits = rawPhone.replaceAll("[^\\d]", "");
        return digits.startsWith("8") ? "7" + digits.substring(1) : digits;
    }

    public void resetState() {
        failedAttemptsPerClient.clear();
        dailyMessageCount.clear();
        statsPerClient.clear();
        log.info("♻️ LeadProcessor: сброшены счётчики ошибок, лимитов и статистика сообщений");
    }

    private String sendWithRetry(String clientId, String phone, String message) {
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return whatsAppService.sendMessage(clientId, phone, message);
            } catch (Exception e) {
                log.warn("⚠️ Попытка {}: ошибка отправки WhatsApp для {}: {}", attempt, clientId, e.getMessage());

                if (attempt == maxAttempts) {
                    log.error("❌ Все попытки отправки для клиента {} исчерпаны", clientId);
                    return null;
                }

                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }


}


