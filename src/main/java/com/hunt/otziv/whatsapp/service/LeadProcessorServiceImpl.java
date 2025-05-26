package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.l_lead.dto.TelephoneDTO;
import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.model.LeadStatus;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.services.serv.LeadStatusService;
import com.hunt.otziv.l_lead.services.serv.TelephoneService;
import com.hunt.otziv.text_generator.alltext.service.clas.HelloTextService;
import com.hunt.otziv.text_generator.alltext.service.clas.RandomTextService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.StatDto;
import com.hunt.otziv.whatsapp.service.fichi.MessageHumanizer;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import com.hunt.otziv.whatsapp.service.service.LeadProcessorService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;


@Service
@Slf4j
public class LeadProcessorServiceImpl implements LeadProcessorService {

    @Value("${whatsapp.ban-protection.maxFailures:5}")
    private int maxFailures;

    @Value("${whatsapp.ban-protection.dailyLimit:30}")
    private int dailyMessageLimit;

    @Value("${whatsapp.ban-protection.minDelay:5}")
    private int minDelay;

    @Value("${whatsapp.ban-protection.maxDelay:30}")
    private int maxDelay;

    @Value("${whatsapp.async.executor:leadDispatcherExecutor}")
    private String executorName;

    private LocalTime startTime;
    private LocalTime endTime;

    private final LeadsRepository leadRepository;
    private final WhatsAppService whatsAppService;
    private final AdminNotifierService adminNotifierService;
    private final ObjectProvider<LeadSenderServiceImpl> leadSenderServiceProvider;
    private final TelephoneService telephoneService;
    private final HelloTextService helloTextService;
    private final LeadEventPublisher leadEventPublisher;
    private final RandomTextService randomTextService;
    private final LeadStatusService leadStatusService;

    private final TaskExecutor taskExecutor;

    private static final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean notificationSent = new AtomicBoolean(false);

    public static final String STATUS_NEW = "Новый";
    public static final String STATUS_SENT = LeadStatus.SEND.title;
    public static final String STATUS_FAIL = "Ошибка";

    private List<WhatsAppProperties.ClientConfig> operatorClients;

    private final Map<String, AtomicInteger> failedAttemptsPerClient = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> dailyMessageCount = new ConcurrentHashMap<>();
    private final Map<String, StatDto> statsPerClient = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> controlSendCounter = new ConcurrentHashMap<>();
    private final Map<String, Integer> controlIntervalPerClient = new ConcurrentHashMap<>();

    private final AtomicInteger globalFailureCounter = new AtomicInteger(0);
    private static final int GLOBAL_FAILURE_LIMIT = 10;

    private List<String> myPhoneNumbers;
    private List<String> helloText;
    private List<String> randomText;
    private final MessageHumanizer humanizer = new MessageHumanizer();


    @Autowired
    public LeadProcessorServiceImpl(
            LeadsRepository leadRepository,
            WhatsAppService whatsAppService,
            AdminNotifierService adminNotifierService,
            ObjectProvider<LeadSenderServiceImpl> leadSenderServiceProvider,
            TelephoneService telephoneService,
            HelloTextService helloTextService, LeadEventPublisher leadEventPublisher, RandomTextService randomTextService,
            LeadStatusService leadStatusService,
            @Qualifier("leadDispatcherExecutor") TaskExecutor taskExecutor
    ) {
        this.leadRepository = leadRepository;
        this.whatsAppService = whatsAppService;
        this.adminNotifierService = adminNotifierService;
        this.leadSenderServiceProvider = leadSenderServiceProvider;
        this.telephoneService = telephoneService;
        this.helloTextService = helloTextService;
        this.leadEventPublisher = leadEventPublisher;
        this.randomTextService = randomTextService;
        this.leadStatusService = leadStatusService;
        this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void initTextTemplates() {
        helloText = helloTextService.findAllTexts();
        randomText = randomTextService.findAllTexts();
        myPhoneNumbers = Stream.concat(
                telephoneService.getAllTelephones().stream().map(TelephoneDTO::getNumber),
                Stream.of("79086431055", "79041256288")
        ).toList();
        log.info("🔃 [INIT] Инициализированы helloText, randomText и номера для контрольных отправок");
    }

    @Override
    public void processLead(WhatsAppProperties.ClientConfig client) {
        log.info("\n==================== [PROCESS LEAD] {} ====================", client.getId());

        if (startTime == null) {
            startTime = LocalTime.now(ZoneId.of("Asia/Irkutsk"));
        }

        String digits = client.getId().replaceAll("\\D+", "");
        Long telephoneId;
        try {
            telephoneId = Long.valueOf(digits);
        } catch (NumberFormatException e) {
            log.error("🟥 [PROCESS] ❌ Невозможно извлечь ID телефона из clientId='{}'", client.getId(), e);
            return;
        }
        log.info("📞 [PROCESS] telephoneId: {}", telephoneId);

        if (operatorClients == null) {
            operatorClients = Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).getActiveOperatorClients();
        }

        Optional<Lead> leadOpt = leadRepository.findFirstByTelephone_IdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
                telephoneId, STATUS_NEW, LocalDate.now());

        if (leadOpt.isEmpty()) {
            log.info("📭 [PROCESS] Нет новых лидов для телефона {} ({}). Планировщик завершится", telephoneId, client.getId());
            finishedClients.add(client.getId());
            Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId());
            checkAllClientsFinished();
            return;
        }

        Lead lead = leadOpt.get();
        log.info("""
            📨 [PROCESS LEAD DETAILS]
            🆔 Лид ID: {}
            📱 Телефон: {}
            📋 Статус: {}
            🕒 Время: {}
            """, lead.getId(), lead.getTelephoneLead(), lead.getLidStatus(), LocalDateTime.now());

        leadStatusService.prepareLeadForSending(lead, STATUS_SENT);

        taskExecutor.execute(() -> {
            int delaySeconds = ThreadLocalRandom.current().nextInt(minDelay, maxDelay + 1);
            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            dailyMessageCount.putIfAbsent(client.getId(), new AtomicInteger(0));
            int sentToday = dailyMessageCount.get(client.getId()).incrementAndGet();

            if (sentToday > dailyMessageLimit) {
                log.warn("🟥 [PROCESS] 🚫 Превышен лимит {} сообщений в день для клиента {}", dailyMessageLimit, client.getId());
                finishedClients.add(client.getId());
                Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(client.getId());
                checkAllClientsFinished();
                return;
            }


            String rawMessage = helloText.get(ThreadLocalRandom.current().nextInt(helloText.size()));
            String message = humanizer.generate(rawMessage);
            log.debug("📨 [MESSAGE] {}", message);
            String result = sendWithRetry(client.getId(), normalizePhone(lead.getTelephoneLead()), message);

            if (result != null && !result.isBlank() && result.contains("ok")) {
                log.info("🟩 [PROCESS] ✅ Успешная отправка сообщения клиенту {}", client.getId());
                failedAttemptsPerClient.put(client.getId(), new AtomicInteger(0));
                statsPerClient.putIfAbsent(client.getId(), new StatDto(client.getId(), 0, 0, null, null, new HashSet<>()));
                statsPerClient.get(client.getId()).incrementSuccess(lead.getId());
                globalFailureCounter.set(0);

                AtomicInteger counter = controlSendCounter.computeIfAbsent(client.getId(), k -> new AtomicInteger(0));
                int count = counter.incrementAndGet();
                if (count > 10000) counter.set(0);

                int interval = controlIntervalPerClient.computeIfAbsent(client.getId(), k -> ThreadLocalRandom.current().nextInt(2, 6));
                if (count % interval == 0) {
                    sendControlMessage(client.getId(), telephoneId, delaySeconds);
                    controlIntervalPerClient.put(client.getId(), ThreadLocalRandom.current().nextInt(2, 6));
                }
            } else {
                log.warn("🟥 [PROCESS] ❌ Ошибка при отправке сообщения клиенту {}", client.getId());
                handleFailure(client, lead);
            }

            log.info("==================== [END PROCESS LEAD] {} ====================\n", client.getId());
        });
    }


    private String normalizePhone(String rawPhone) {
        String digits = rawPhone.replaceAll("[^\\d]", "");
        return digits.startsWith("8") ? "7" + digits.substring(1) : digits;
    }

    private String sendWithRetry(String clientId, String phone, String message) {
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return whatsAppService.sendMessage(clientId, phone, message);
            } catch (Exception e) {
                log.warn("⚠️ [RETRY] Попытка {}: ошибка отправки WhatsApp для {}: {}", attempt, clientId, e.getMessage());
                if (attempt == maxAttempts) {
                    log.error("❌ [RETRY] Все попытки отправки для клиента {} исчерпаны", clientId);
                    return null;
                }
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private void sendControlMessage(String clientId, Long telephoneId, int delaySeconds) {
        String clientPhoneNumber = telephoneService.getTelephoneById(telephoneId).getNumber();

        if (myPhoneNumbers == null || myPhoneNumbers.isEmpty()) {
            log.warn("📵 [CONTROL] Нет своих номеров для контрольной отправки");
            return;
        }
        if (randomText == null || randomText.isEmpty()) {
            log.warn("📝 [CONTROL] Нет текстов для контрольной отправки");
            return;
        }

        String message2 = randomText.get(ThreadLocalRandom.current().nextInt(randomText.size()));
        List<String> availablePhones = myPhoneNumbers.stream()
                .filter(p -> !normalizePhone(p).equals(normalizePhone(clientPhoneNumber)))
                .toList();

        if (availablePhones.isEmpty()) {
            log.warn("📵 [CONTROL] Нет подходящих номеров для контрольной отправки (исключая свой)");
            return;
        }

        String myTelephone = availablePhones.get(ThreadLocalRandom.current().nextInt(availablePhones.size()));

        CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String result2 = sendWithRetry(clientId, normalizePhone(myTelephone), message2);
            if (result2 != null && !result2.isBlank() && result2.contains("ok")) {
                log.info("📨 [CONTROL] Отправлено контрольное сообщение на {}: {}", myTelephone, message2);
            } else {
                log.warn("⚠️ [CONTROL] Ошибка при контрольной отправке на {}: {}", myTelephone, message2);
            }
        });
    }

    private void handleFailure(WhatsAppProperties.ClientConfig client, Lead lead) {
        String clientId = client.getId();
        log.warn("⚠️ [FAILURE] Неудачная попытка отправки для клиента {}", clientId);

        failedAttemptsPerClient.putIfAbsent(clientId, new AtomicInteger(0));
        int failures = failedAttemptsPerClient.get(clientId).incrementAndGet();

        statsPerClient.putIfAbsent(clientId, new StatDto(clientId, 0, 0, null, null, new HashSet<>()));
        statsPerClient.get(clientId).incrementFail(lead.getId());

        int globalFailures = globalFailureCounter.incrementAndGet();
        if (globalFailures >= GLOBAL_FAILURE_LIMIT) {
            log.error("🚨 [FAILURE] Обнаружено {} глобальных сбоев. Останавливаем всех клиентов!", globalFailures);
            operatorClients.forEach(c ->
                    Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(c.getId()));
            adminNotifierService.notifyAdmin("🚨 Глобальный сбой: все клиенты отключены из-за серии ошибок");
        }

        if (failures >= maxFailures) {
            log.error("🚫 [FAILURE] Клиент {} достиг лимита ошибок. Останавливаем.", clientId);
            finishedClients.add(clientId);
            Objects.requireNonNull(leadSenderServiceProvider.getIfAvailable()).stopClientScheduler(clientId);
            adminNotifierService.notifyAdmin(String.format("🚫 Клиент %s отключён после %d ошибок.", clientId, failures));
            checkAllClientsFinished();
        }

        leadStatusService.prepareLeadForSending(lead, STATUS_FAIL);
    }

    @Override
    public void checkAllClientsFinished() {
        List<WhatsAppProperties.ClientConfig> clients = getOperatorClients();
        if (!notificationSent.get() && finishedClients.size() == clients.size()) {
            notificationSent.set(true);
            endTime = LocalTime.now(ZoneId.of("Asia/Irkutsk"));

            int totalSuccess = statsPerClient.values().stream().mapToInt(StatDto::getSuccess).sum();
            int totalFail = statsPerClient.values().stream().mapToInt(StatDto::getFail).sum();

            StringBuilder sb = new StringBuilder("📈 Итог рассылки по всем клиентам:\n");
            statsPerClient.values().forEach(stat -> sb.append(stat.toReportLine()).append("\n"));

            sb.append("\n📊 Всего отправлено: ✅ ")
                    .append(totalSuccess)
                    .append(" / ❌ ")
                    .append(totalFail)
                    .append(" (итого: ")
                    .append(totalSuccess + totalFail)
                    .append(")");

            sb.append(" 🕓 Время: с ")
                    .append(startTime != null ? startTime.format(DateTimeFormatter.ofPattern("HH:mm")) : "--:--")
                    .append(" до ")
                    .append(endTime != null ? endTime.format(DateTimeFormatter.ofPattern("HH:mm")) : "--:--");

            log.info("\n==================== [SUMMARY] ====================\n{}\n====================================================", sb);
            adminNotifierService.notifyAdmin(sb.toString());
        }
    }

    public void resetState() {
        failedAttemptsPerClient.clear();
        dailyMessageCount.clear();
        statsPerClient.clear();
        myPhoneNumbers = Stream.concat(
                telephoneService.getAllTelephones().stream().map(TelephoneDTO::getNumber),
                Stream.of("79086431055", "79041256288")
        ).toList();
        helloText = helloTextService.findAllTexts();
        randomText = randomTextService.findAllTexts();
        log.info("♻️ [RESET] Сброшены лимиты и подгружены контрольные номера и тексты");
    }

    private List<WhatsAppProperties.ClientConfig> getOperatorClients() {
        if (operatorClients == null) {
            LeadSenderServiceImpl sender = leadSenderServiceProvider.getIfAvailable();
            if (sender != null) {
                operatorClients = sender.getActiveOperatorClients();
            } else {
                log.warn("⚠️ [FALLBACK] LeadSenderService недоступен");
                operatorClients = Collections.emptyList();
            }
        }
        return operatorClients;
    }

}







