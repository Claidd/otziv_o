package com.hunt.otziv.whatsapp.service;


import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import com.hunt.otziv.whatsapp.service.service.LeadProcessorService;
import com.hunt.otziv.whatsapp.service.service.LeadSenderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;


@Service
@Slf4j
@RequiredArgsConstructor
public class LeadSenderServiceImpl implements LeadSenderService {

    private final WhatsAppProperties properties;
    private final LeadProcessorService leadProcessorService;
    private final AdminNotifierService adminNotifierService;
    private final LeadService leadService;

    private List<WhatsAppProperties.ClientConfig> clients;
    private final List<ScheduledExecutorService> executors = new ArrayList<>();
    private final Map<String, Boolean> activeClients = new ConcurrentHashMap<>();

    private final String NEW_STATUS = "Новый";

    @PostConstruct
    public void initClients() {
        log.info("\n=========================== INIT CLIENTS ===========================");

        List<WhatsAppProperties.ClientConfig> loadedClients = properties.getClients();
        if (loadedClients == null) {
            log.warn("⚠️ В конфигурации WhatsAppProperties нет clients — список пустой");
            this.clients = new ArrayList<>();
        } else {
            this.clients = loadedClients.stream()
                    .filter(client -> "operator".equalsIgnoreCase(client.getRole()))
                    .collect(Collectors.toList());
        }
        resetClientStates();

        log.info("==================================================================\n");
    }

    public void resetClientStates() {
        log.info("\n======================= RESET CLIENT STATES =======================");

        activeClients.clear();
        for (WhatsAppProperties.ClientConfig client : clients) {
            activeClients.put(client.getId(), true);
            log.info("🟦 [DISPATCH] 🔁 Клиент {} активен после сброса", client.getId());
        }
        log.info("🟦 [DISPATCH] 🔄 Все клиенты активированы");

        log.info("==================================================================\n");
    }

//    @Scheduled(cron = "0 00 11 * * *")
    public void startDailyDispatch() {
        log.info("\n===================== START DAILY DISPATCH =======================");

        log.info("🟦 [DISPATCH] ⏰ Ежедневный запуск рассылки");

        if (clients == null || clients.isEmpty()) {
            log.warn("🟥 [DISPATCH] ❌ Нет клиентов с ролью operator — рассылка не запущена");
            adminNotifierService.notifyAdmin("⚠️ Рассылка не запущена: нет активных клиентов с ролью operator");
            return;
        }

        boolean noLeads = clients.stream()
                .map(c -> Long.valueOf(c.getId().replaceAll("\\D+", "")))
                .map(id -> leadService.countNewLeadsByClient(id, NEW_STATUS))
                .allMatch(count -> count == 0);

        if (noLeads) {
            log.warn("🟥 [DISPATCH] 📭 У всех клиентов отсутствуют новые лиды");
            adminNotifierService.notifyAdmin("📭 Рассылка завершена: у всех клиентов нет новых лидов");
            return;
        }

        adminNotifierService.notifyAdmin("🚀 Началась ежедневная рассылка сообщений по клиентам");
        resetClientStates();

        for (int i = 0; i < clients.size(); i++) {
            WhatsAppProperties.ClientConfig client = clients.get(i);

            int delayStepSeconds = ThreadLocalRandom.current().nextInt(60, 181); //Клиент 1 — через 60 - 180 сек
            int initialDelay = i * delayStepSeconds;

            Long telephoneId = Long.valueOf(client.getId().replaceAll("\\D+", ""));
            int leadCount = leadService.countNewLeadsByClient(telephoneId, NEW_STATUS);

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executors.add(executor);

            log.info("🟨 [DISPATCH] 📅 Клиент {}: старт через {} сек, лидов: {}", client.getId(), initialDelay, leadCount);

            executor.schedule(() -> {
                leadProcessorService.processLead(client);
                scheduleNextMessage(executor, client, leadCount);
            }, initialDelay, TimeUnit.SECONDS);
        }

        leadProcessorService.resetState();
        log.info("🧹 [DISPATCH] Лимиты и ошибки сброшены — клиенты готовы");
        log.info("🟩 [DISPATCH] ✅ Планировщик запущен: {} клиентов", clients.size());

        log.info("==================================================================\n");
    }

    private void scheduleNextMessage(ScheduledExecutorService executor, WhatsAppProperties.ClientConfig client, int initialLeadCount) {
        int delay = calculateRandomPeriodByLeadCount(initialLeadCount);

        executor.schedule(() -> {
            if (Boolean.FALSE.equals(activeClients.get(client.getId()))) {
                log.info("🟩 [DISPATCH] ✅ Клиент {} завершён (нет лидов)", client.getId());
                return;
            }

            leadProcessorService.processLead(client);
            scheduleNextMessage(executor, client, initialLeadCount);

        }, delay, TimeUnit.SECONDS);

        LocalDateTime nextTime = LocalDateTime.now().plusSeconds(delay);
        log.info("⏱ [DISPATCH] Клиент {}: следующее сообщение в {} (через {} сек, лидов было: {})",
                client.getId(),
                nextTime.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                delay,
                initialLeadCount);
    }

    private int calculateRandomPeriodByLeadCount(int leadCount) {
        if (leadCount <= 5) {
            // 5–60 минут (в секундах)
            return ThreadLocalRandom.current().nextInt(900, 3601);
        } else if (leadCount <= 10) {
            // 5–40 минут
            return ThreadLocalRandom.current().nextInt(900, 2401);
        } else if (leadCount <= 20) {
            // 5–30 минут
            return ThreadLocalRandom.current().nextInt(600, 1801);
        } else if (leadCount <= 30) {
            // 5–15 минут
            return ThreadLocalRandom.current().nextInt(300, 901);
        } else {
            // фиксированное значение: 5 минут (минимум)
            return 300;
        }
    }


    @PreDestroy
    public void shutdownExecutors() {
        log.info("\n====================== SHUTDOWN EXECUTORS =========================");

        for (ScheduledExecutorService executor : executors) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("==================================================================\n");
    }

    public void stopClientScheduler(String clientId) {
        activeClients.put(clientId, false);
        log.info("🛑 [DISPATCH] Клиент {} деактивирован вручную", clientId);
    }

    public List<WhatsAppProperties.ClientConfig> getActiveOperatorClients() {
        return clients;
    }
}





//@Scheduled(cron = "0 0 1 * * *") // каждый день в 1:00
//public void startDailyDispatch() {
//    log.info("⏰ Ежедневный запуск рассылки для всех клиентов");
//    adminNotifierService.notifyAdmin("🚀 Началась ежедневная рассылка сообщений по клиентам");
//    resetClientStates();
//
//    int delayStepSeconds = 60;
//
//    for (int i = 0; i < clients.size(); i++) {
//        WhatsAppProperties.ClientConfig client = clients.get(i);
//        int initialDelay = i * delayStepSeconds;
//
//        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
//        executors.add(executor);
//
//        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
//            if (Boolean.FALSE.equals(activeClients.get(client.getId()))) {
//                log.info("🛑 У клиента {} закончились лиды — планировщик останавливается", client.getId());
//                futures.get(client.getId()).cancel(false);
//                return;
//            }
//            leadProcessorService.processLead(client);
//        }, initialDelay, 360, TimeUnit.SECONDS);
//
//        futures.put(client.getId(), future);
//    }
//}



//
//    private final AtomicInteger clientPointer = new AtomicInteger(0);
//

//
//    @Scheduled(fixedDelay = 180_000) // каждые 3 минуты
//    @Transactional
//    public void sendNextLeadMessage() {
//        if (clients.isEmpty()) return;
//
//        // выбрать клиента по кругу
//        WhatsAppProperties.ClientConfig currentClient = clients.get(clientPointer.getAndIncrement() % clients.size());
//        Long telephoneId = Long.valueOf(currentClient.getId().replace("client", "")); // "client1" → 1
//
//        Optional<Lead> leadOpt = leadRepository
//                .findFirstByTelephoneIdAndLidStatusAndCreateDateLessThanEqualOrderByCreateDateAsc(
//                        telephoneId,
//                        "В работе",
//                        LocalDate.now()
//                );
////        Optional<Lead> leadOpt = leadRepository.findById(2L);
//
//        if (leadOpt.isEmpty()) {
//            log.info("🔁 Нет новых лидов для телефона {}", telephoneId);
//            return;
//        }
//
//        Lead lead = leadOpt.get();
//
//        System.out.println(lead);
//
//        String message = "Здравствуйте! У нас есть предложение..."; // или сгенерировать
//        String result = whatsAppService.sendMessage(currentClient.getId(), normalizePhone( lead.getTelephoneLead()), message);
//
//        log.info("📤 Сообщение отправлено: {}", result);
//
//        lead.setLidStatus("SENT");
//        lead.setUpdateStatus(LocalDate.now());
//    }



