package com.hunt.otziv.whatsapp.service.last_seen;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.services.serv.LeadService;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import com.hunt.otziv.whatsapp.dto.LastSeenStatDto;
import com.hunt.otziv.whatsapp.dto.StatDto;
import com.hunt.otziv.whatsapp.service.service.AdminNotifierService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadLastSeenCollectorServiceImpl {

    private final WhatsAppProperties properties;
    private final LeadsRepository leadRepository;
    private final AdminNotifierService adminNotifierService;
    private final ObjectProvider<LeadLastSeenProcessorServiceImpl> processorProvider;
    private final Map<String, Boolean> activeClients = new ConcurrentHashMap<>();

    private final Map<String, Queue<Lead>> leadQueues = new ConcurrentHashMap<>();
    private final Map<String, ScheduledExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<String, LastSeenStatDto> statsPerClient = new ConcurrentHashMap<>();
    private final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean summarySent = new AtomicBoolean(false);

    private LocalTime startTime;
    private LocalTime endTime;
    private volatile long collectionRunId = 0;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

//    @Scheduled(cron = "0 40 18 * * *", zone = "Asia/Irkutsk")
//    public void scheduleNightlyCheck() {
//        startLastSeenCollection();
//    }

    @PostConstruct
    public void initActiveClients() {
        List<WhatsAppProperties.ClientConfig> clients = properties.getClients();
        if (clients != null) {
            clients.forEach(c -> activeClients.put(c.getId(), true));
        }
    }

    public synchronized void startLastSeenCollection() {
        log.info("\n================== START LAST SEEN COLLECTION ==================");
        long runId = ++collectionRunId;
        resetCollectionState();

        List<WhatsAppProperties.ClientConfig> clients = properties.getClients();
        if (clients == null || clients.isEmpty()) {
            log.warn("⚠️ Нет активных клиентов для проверки lastSeen");
            return;
        }

        startTime = LocalTime.now(ZoneId.of("Asia/Irkutsk"));
        activeClients.clear();
        clients.forEach(c -> activeClients.put(c.getId(), true));

        adminNotifierService.notifyAdmin("🚀 Началась ночная проверка lastSeen по всем клиентам");

        for (WhatsAppProperties.ClientConfig client : clients) {
            Long telephoneId = Long.valueOf(client.getId().replaceAll("\\D+", ""));
            List<Lead> leads = leadRepository.findAllByTelephoneAndStatusBeforeDate(
                    telephoneId, "Проверка", LocalDate.now());

            if (leads.isEmpty()) {
                log.info("📭 Нет лидов для клиента {}", client.getId());
                markClientFinished(client.getId());
                continue;
            }

            leadQueues.put(client.getId(), new ConcurrentLinkedQueue<>(leads));
            statsPerClient.put(client.getId(), new LastSeenStatDto(client.getId(), 0, 0, 0, 0, null, null, new HashSet<>()));

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            executors.put(client.getId(), executor);

            int initialDelay = ThreadLocalRandom.current().nextInt(10, 30);
            log.info("🟨 [COLLECT] Клиент {}: старт через {} сек (лидов: {})",
                    client.getId(), initialDelay, leads.size());

            executor.schedule(() -> runQueueProcessor(client.getId(), runId), initialDelay, TimeUnit.SECONDS);
        }
    }

    private void runQueueProcessor(String clientId, long runId) {
        Queue<Lead> queue = leadQueues.get(clientId);
        ScheduledExecutorService executor = executors.get(clientId);

        if (queue == null || executor == null || executor.isShutdown() || runId != collectionRunId) return;

        try {
            executor.execute(() -> {
                while (!queue.isEmpty() && !executor.isShutdown()) {
                    if (runId != collectionRunId) {
                        return;
                    }

                    Lead lead = queue.poll();
                    if (lead != null) {
                        processorProvider.getObject().processLead(clientId, lead);
                    }

                    if (!queue.isEmpty()) {
                        int delay = calculateRandomPeriodByLeadCount(queue.size());
                        LocalDateTime nextRun = LocalDateTime.now().plusSeconds(delay);
                        log.info("⏳ [SCHEDULE] Следующий лид для {} будет обработан через {} сек (в {})",
                                clientId, delay, nextRun.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

                        try {
                            TimeUnit.SECONDS.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                markClientFinished(clientId, runId);
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private int calculateRandomPeriodByLeadCount(int leadCount) {
        if (leadCount <= 5) {
            return ThreadLocalRandom.current().nextInt(10, 60); // 10–120 сек
        } else if (leadCount <= 10) {
            return ThreadLocalRandom.current().nextInt(10, 80); // 5–40 мин
        } else if (leadCount <= 20) {
            return ThreadLocalRandom.current().nextInt(10, 100); // 5–30 мин
        } else if (leadCount >= 30) {
            return ThreadLocalRandom.current().nextInt(10, 120);  // 5–15 мин
        } else {
            return 60; // минимум 5 мин
        }
    }

    public void incrementStat(String clientId, int processed, int hasWhatsApp, int hasLastSeen, int hidden) {
        statsPerClient.putIfAbsent(clientId, new LastSeenStatDto(clientId, 0, 0, 0, 0, null, null, new HashSet<>()));
        var stat = statsPerClient.get(clientId);
        stat.setProcessed(stat.getProcessed() + processed);
        stat.setHasWhatsApp(stat.getHasWhatsApp() + hasWhatsApp);
        stat.setHasLastSeen(stat.getHasLastSeen() + hasLastSeen);
        stat.setHiddenOrNotWhatsApp(stat.getHiddenOrNotWhatsApp() + hidden);
    }

    public synchronized void markClientFinished(String clientId) {
        markClientFinished(clientId, collectionRunId);
    }

    private synchronized void markClientFinished(String clientId, long runId) {
        if (runId != collectionRunId) {
            return;
        }

        finishedClients.add(clientId);
        ScheduledExecutorService executor = executors.remove(clientId);
        if (executor != null) executor.shutdown();
        leadQueues.remove(clientId);
        checkAllClientsFinished();
    }

    private void checkAllClientsFinished() {
        if (finishedClients.size() == currentClientCount() && summarySent.compareAndSet(false, true)) {
            endTime = LocalTime.now(ZoneId.of("Asia/Irkutsk"));

            StringBuilder report = new StringBuilder();
            // Заголовок (жирный, MarkdownV2)
            report.append("*📊 Итоговая проверка lastSeen*\n")
                    .append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            // Начало моноширинного блока
            report.append("```\n");
            report.append(String.format("%-12s %-10s %-10s %-10s %-12s\n",
                    "Клиент", "Лидов", "WA", "✅ lastSeen", "🚫 Скрыто/нет"));

            report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            // Данные по клиентам
            statsPerClient.values().forEach(stat -> {
                String line = String.format(
                        "%-12s %-10d %-10d %-10d %-12d",
                        stat.getClientId(),
                        stat.getProcessed(),
                        stat.getHasWhatsApp(),
                        stat.getHasLastSeen(),
                        stat.getHiddenOrNotWhatsApp()
                );
                report.append(line).append("\n");
            });

            // Итоговая линия
            report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            int totalProcessed = statsPerClient.values().stream().mapToInt(LastSeenStatDto::getProcessed).sum();
            int totalWhatsApp = statsPerClient.values().stream().mapToInt(LastSeenStatDto::getHasWhatsApp).sum();
            int totalLastSeen = statsPerClient.values().stream().mapToInt(LastSeenStatDto::getHasLastSeen).sum();
            int totalHidden = statsPerClient.values().stream().mapToInt(LastSeenStatDto::getHiddenOrNotWhatsApp).sum();

            report.append(String.format("%-12s %-10d %-10d %-10d %-12d\n",
                    "ИТОГО",
                    totalProcessed,
                    totalWhatsApp,
                    totalLastSeen,
                    totalHidden));

            // Закрытие блока моноширинного текста
            report.append("```\n");

            // Легенда с пояснениями
            report.append("\n")
                    .append("✅ lastSeen доступен: ").append(totalLastSeen).append("\n")
                    .append("🚫 Скрыто/нет WA: ").append(totalHidden).append("\n")
                    .append("❓ Всего с WhatsApp: ").append(totalWhatsApp).append("\n")
                    .append("\n")
                    .append("🕓 Время: ")
                    .append(startTime.format(TIME_FORMAT))
                    .append(" – ")
                    .append(endTime.format(TIME_FORMAT));

            String reportStr = report.toString();

            log.info("\n==================== [LAST SEEN SUMMARY] ====================\n{}\n============================================================", reportStr);

            adminNotifierService.notifyAdmin(reportStr);
        }
    }



    @PreDestroy
    public synchronized void shutdownExecutors() {
        log.info("\n====================== SHUTDOWN EXECUTORS =========================");

        stopExecutors(true);
        leadQueues.clear();
        log.info("==================================================================\n");
    }

    public synchronized void stopClientScheduler(String clientId) {
        activeClients.put(clientId, false);
        ScheduledExecutorService executor = executors.remove(clientId);
        if (executor != null) {
            executor.shutdownNow();
        }
        leadQueues.remove(clientId);
        finishedClients.add(clientId);
        log.info("🛑 [COLLECT] Клиент {} деактивирован вручную", clientId);
        checkAllClientsFinished();
    }

    private void resetCollectionState() {
        stopExecutors(false);
        leadQueues.clear();
        statsPerClient.clear();
        finishedClients.clear();
        summarySent.set(false);
        endTime = null;
    }

    private int currentClientCount() {
        List<WhatsAppProperties.ClientConfig> clients = properties.getClients();
        return clients == null ? 0 : clients.size();
    }

    private void stopExecutors(boolean awaitTermination) {
        List<ScheduledExecutorService> toStop = new ArrayList<>(executors.values());
        executors.clear();

        for (ScheduledExecutorService executor : toStop) {
            shutdownExecutor(executor, awaitTermination);
        }
    }

    private void shutdownExecutor(ScheduledExecutorService executor, boolean awaitTermination) {
        if (executor == null) {
            return;
        }

        try {
            if (awaitTermination) {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } else {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public List<WhatsAppProperties.ClientConfig> getActiveOperatorClients() {
        return properties.getClients() != null
                ? properties.getClients().stream()
                .filter(c -> Boolean.TRUE.equals(activeClients.get(c.getId())))
                .toList()
                : List.of();
    }
}

