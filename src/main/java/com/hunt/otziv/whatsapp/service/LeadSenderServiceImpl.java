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
    private final AdminNotifierService adminNotifierService; // уведомление в Telegram
    private final LeadService leadService;

    private List<WhatsAppProperties.ClientConfig> clients;
    private final List<ScheduledExecutorService> executors = new ArrayList<>();
    private final Map<String, Boolean> activeClients = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();




    private final String NEW_STATUS = "Новый";

    @PostConstruct
    public void initClients() {
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
    }



    public void resetClientStates() {
        activeClients.clear();
        for (WhatsAppProperties.ClientConfig client : clients) {
            activeClients.put(client.getId(), true);
            log.info("🔁 Клиент {} активен после сброса", client.getId());
        }
        log.info("🔄 Состояния всех клиентов сброшены и активированы");
    }

    @Scheduled(cron = "0 0 6 * * *") // каждый день в 6:00
    public void startDailyDispatch() {
        log.info("⏰ Ежедневный запуск рассылки для всех клиентов");

        if (clients == null || clients.isEmpty()) {
            log.warn("❌ Нет клиентов с ролью operator — рассылка не запущена");
            adminNotifierService.notifyAdmin("⚠️ Рассылка не запущена: нет активных клиентов с ролью operator");
            return;
        }

        boolean noLeads = clients.stream()
                .map(c -> Long.valueOf(c.getId().replaceAll("\\D+", "")))
                .map(id -> leadService.countNewLeadsByClient(id, NEW_STATUS))
                .allMatch(count -> count == 0);

        if (noLeads) {
            log.warn("📭 У всех клиентов отсутствуют новые лиды");
            adminNotifierService.notifyAdmin("📭 Рассылка завершена: у всех клиентов нет новых лидов");
            return;
        }

        adminNotifierService.notifyAdmin("🚀 Началась ежедневная рассылка сообщений по клиентам");
        resetClientStates();

        for (int i = 0; i < clients.size(); i++) {
            WhatsAppProperties.ClientConfig client = clients.get(i);

            int delayStepSeconds = ThreadLocalRandom.current().nextInt(30, 121);
            int initialDelay = i * delayStepSeconds;

            Long telephoneId = Long.valueOf(client.getId().replaceAll("\\D+", ""));
            int leadCount = leadService.countNewLeadsByClient(telephoneId, NEW_STATUS);
            int periodSeconds = calculateRandomPeriodByLeadCount(leadCount);

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executors.add(executor);

            log.info("📅 Планируем запуск клиента {}: initialDelay={} сек, период={} сек (лидов: {})",
                    client.getId(), initialDelay, periodSeconds, leadCount);

            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                if (Boolean.FALSE.equals(activeClients.get(client.getId()))) {
                    log.info("🛑 У клиента {} закончились лиды — планировщик останавливается", client.getId());
                    futures.get(client.getId()).cancel(false);
                    return;
                }
                leadProcessorService.processLead(client);
            }, initialDelay, periodSeconds, TimeUnit.SECONDS);

            futures.put(client.getId(), future);
        }

        leadProcessorService.resetState();
        log.info("🧹 Сброшены лимиты и счётчики ошибок после запуска задач — все клиенты готовы к следующей рассылке");
        log.info("✅ Планировщик успешно запущен: {} клиентов запланировано", clients.size());
    }


    /**
     * Вычисление периода запуска на основе количества лидов.
     */
    private int calculateRandomPeriodByLeadCount(int leadCount) {

        if (leadCount <= 5) {
            return ThreadLocalRandom.current().nextInt(300, 3601); // от 5 до 60 мин
        } else if (leadCount <= 10) {
            return ThreadLocalRandom.current().nextInt(300, 2401); // от 5 до 30 мин
        }  else if (leadCount <= 20) {
                return ThreadLocalRandom.current().nextInt(300, 1801); // от 5 до 30 мин
        } else if (leadCount <= 30) {
            return ThreadLocalRandom.current().nextInt(300, 901); // от 5 до 15 мин
        } else {
            return 300; // минимум — 5 минут
        }
    }

    @PreDestroy
    public void shutdownExecutors() {
        log.info("🛑 Завершаем все планировщики...");
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
    }

    public void stopClientScheduler(String clientId) {
        ScheduledFuture<?> future = futures.remove(clientId); // безопаснее, сразу удаляет
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.info("🛑 Планировщик для клиента {} остановлен вручную", clientId);
        } else {
            log.info("ℹ️ Планировщик для клиента {} уже был остановлен или не найден", clientId);
        }
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



