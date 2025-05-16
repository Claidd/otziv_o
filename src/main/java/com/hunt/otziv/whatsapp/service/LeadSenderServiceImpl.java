package com.hunt.otziv.whatsapp.service;


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


@Service
@Slf4j
@RequiredArgsConstructor
public class LeadSenderServiceImpl implements LeadSenderService {

    private final WhatsAppProperties properties;
    private final LeadProcessorService leadProcessorService;
    private final AdminNotifierService adminNotifierService; // уведомление в Telegram

    private List<WhatsAppProperties.ClientConfig> clients;
    private final List<ScheduledExecutorService> executors = new ArrayList<>();
    private final Map<String, Boolean> activeClients = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();



    @PostConstruct
    public void initClients() {
        this.clients = properties.getClients();
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

    @Scheduled(cron = "0 54 17 * * *") // каждый день в 16:25
    public void startDailyDispatch() {
        log.info("⏰ Ежедневный запуск рассылки для всех клиентов");
        adminNotifierService.notifyAdmin("🚀 Началась ежедневная рассылка сообщений по клиентам");
        resetClientStates();

        int delayStepSeconds = 60;

        for (int i = 0; i < clients.size(); i++) {
            WhatsAppProperties.ClientConfig client = clients.get(i);
            int initialDelay = i * delayStepSeconds;

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executors.add(executor);

            ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
                if (Boolean.FALSE.equals(activeClients.get(client.getId()))) {
                    log.info("🛑 У клиента {} закончились лиды — планировщик останавливается", client.getId());
                    futures.get(client.getId()).cancel(false);
                    return;
                }
                leadProcessorService.processLead(client);
            }, initialDelay, 180, TimeUnit.SECONDS);

            futures.put(client.getId(), future);
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
        ScheduledFuture<?> future = futures.get(clientId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            futures.remove(clientId); // 🧹 очищаем из памяти
            log.info("🛑 Планировщик для клиента {} остановлен вручную", clientId);
        } else {
            log.info("ℹ️ Планировщик для клиента {} уже был остановлен", clientId);
        }
    }
}







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



