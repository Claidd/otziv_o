package com.hunt.otziv.l_lead.utils;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.hunt.otziv.config.jwt.service.JwtService;
import com.hunt.otziv.l_lead.model.DispatchSettings;
import com.hunt.otziv.l_lead.repository.DispatchSettingsRepository;
import com.hunt.otziv.whatsapp.service.LeadSenderServiceImpl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class DynamicDispatchScheduler {

    private final DispatchSettingsRepository settingsRepository;
    private final LeadSenderServiceImpl leadSenderService;
    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private String lastCronExpression;

    @PostConstruct
    public void scheduleInitialTask() {
        scheduleNext();
    }

    private void scheduleNext() {
        String cron = settingsRepository.findById(1L)
                .map(DispatchSettings::getCronExpression)
                .orElse("0 0 14 * * *");

        lastCronExpression = cron;

        CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING));
        Cron cronDef = parser.parse(cron);
        cronDef.validate();

        ZonedDateTime now = ZonedDateTime.now();
        ExecutionTime executionTime = ExecutionTime.forCron(cronDef);
        Optional<Duration> timeToNextExecution = executionTime.timeToNextExecution(now);

        if (timeToNextExecution.isPresent()) {
            Duration delay = timeToNextExecution.get();
            log.info("📆 Следующий запуск через: {}", delay);
            scheduler.schedule(() -> {
                try {
                    log.info("🚀 Запуск рассылки по расписанию из БД");
                    leadSenderService.startDailyDispatch();

                    // Обновим last_run
                    DispatchSettings settings = settingsRepository.findById(1L).orElse(null);
                    if (settings != null) {
                        settings.setLastRun(java.sql.Timestamp.from(java.time.Instant.now()));
                        settingsRepository.save(settings);
                    }
                } catch (Exception e) {
                    log.error("❌ Ошибка при выполнении рассылки", e);
                } finally {
                    scheduleNext(); // Запланировать следующий запуск
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            log.warn("⚠️ Невозможно рассчитать следующую дату по cron '{}'", cron);
        }
    }

    @Scheduled(fixedRate = 300000) // каждые 5 минут
    public void checkForCronUpdates() {
        String currentCron = settingsRepository.findById(1L)
                .map(DispatchSettings::getCronExpression)
                .orElse("0 0 14 * * *");

        if (!currentCron.equals(lastCronExpression)) {
            log.info("🔄 Cron изменён: {} → {}. Перепланируем...", lastCronExpression, currentCron);

            // ❗ ЗАВЕРШИТЬ старый scheduler
            scheduler.shutdownNow();

            // ❗ СОЗДАТЬ новый
            scheduler = Executors.newSingleThreadScheduledExecutor();

            lastCronExpression = currentCron;
            scheduleInitialTask();
        }
    }


    @Scheduled(fixedRate = 300000) // каждые 5 минут
    public void syncCronFromVps() {
        try {
            log.info("🔄 [SYNC] Проверка cron с VPS...");
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtService.generateSyncToken());
            HttpEntity<Void> entity = new HttpEntity<>(headers);


            ResponseEntity<String> response = restTemplate.exchange(
                    "https://o-ogo.ru/api/dispatch-settings/cron",
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            log.info("✅ Ответ получен: {}", response.getBody());
            String vpsCron = response.getBody();

            DispatchSettings local = settingsRepository.findById(1L).orElse(null);
            if (local != null && !Objects.equals(local.getCronExpression(), vpsCron)) {
                log.info("🌐 Обнаружено обновление cron с VPS: {} → {}", local.getCronExpression(), vpsCron);
                local.setCronExpression(vpsCron);
                settingsRepository.save(local);
            }
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при синхронизации cron с VPS: {}", e.getMessage());
        }
    }
}


