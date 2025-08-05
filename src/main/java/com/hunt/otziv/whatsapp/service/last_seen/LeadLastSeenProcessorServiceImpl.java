package com.hunt.otziv.whatsapp.service.last_seen;

import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.services.serv.VpsSyncService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.dto.WhatsAppUserStatusDto;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;


@Service
@Slf4j
@RequiredArgsConstructor
public class LeadLastSeenProcessorServiceImpl {

    private final LeadsRepository leadRepository;
    private final WhatsAppService whatsAppService;
    private final ObjectProvider<LeadLastSeenCollectorServiceImpl> collectorProvider;
    private static final ZoneId IRKUTSK_ZONE = ZoneId.of("Asia/Irkutsk");

    private final VpsSyncService vpsSyncService;         // отдельный сервис для синхронизации

    // Менеджеры для ротации
    private static final List<Long> MANAGER_IDS = List.of(2L, 3L);
    private final AtomicInteger managerCounter = new AtomicInteger(0);

    /**
     * Обрабатывает одного лида: проверяет регистрацию и lastSeen, сохраняет в БД.
     * Если номер не зарегистрирован — сразу завершает обработку.
     */
    public void processLead(String clientId, Lead lead) {
        String phone = normalizePhone(lead.getTelephoneLead());
        long startTime = System.currentTimeMillis();

        log.info("▶ [PROCESS LEAD] Старт обработки лида {} (номер: {}) для клиента {} в {}",
                lead.getId(), phone, clientId, LocalDateTime.now(IRKUTSK_ZONE));

        try {
            log.info("⏱ [{}] Шаг 1: Запрашиваем статус WhatsApp у Node.js (телефон: {})", clientId, phone);
            long statusStart = System.currentTimeMillis();

            Optional<WhatsAppUserStatusDto> statusOpt =
                    whatsAppService.getUserStatusWithLastSeen(clientId, phone);

            long statusElapsed = System.currentTimeMillis() - statusStart;
            log.info("⏱ [{}] Шаг 1 завершён за {} мс", clientId, statusElapsed);

            if (statusOpt.isEmpty()) {
                log.warn("⚠ [{}] Не удалось получить статус WhatsApp для {} (elapsed: {} мс)",
                        clientId, phone, System.currentTimeMillis() - startTime);
                collectorProvider.getObject().incrementStat(clientId, 1, 0, 0, 1);
                return;
            }

            WhatsAppUserStatusDto status = statusOpt.get();
            String stage = status.getStage() != null ? status.getStage() : "unknown";

            log.info("ℹ [{}] Ответ получен (stage={}): registered={}, lastSeenRaw={}, parsedLastSeen={}",
                    clientId, stage, status.getRegistered(), status.getRawLastSeen(), status.getParsedLastSeen());

            long dbStart = System.currentTimeMillis();

            // Если номер НЕ зарегистрирован — сразу оффлайн
            if (Boolean.FALSE.equals(status.getRegistered())) {
                lead.setLastSeen(null);
                lead.setLidStatus("Оффлайн");
                leadRepository.save(lead);
                collectorProvider.getObject().incrementStat(clientId, 1, 0, 0, 1);
                log.info("📵 [{}] {} — номер не зарегистрирован (stage={}), статус 'Оффлайн' (DB save {} мс)",
                        clientId, phone, stage, System.currentTimeMillis() - dbStart);
                return;
            }

            // Если lastSeen доступен — сохраняем и назначаем менеджера
            if (status.getParsedLastSeen() != null) {
                lead.setLastSeen(status.getParsedLastSeen());
                lead.setLidStatus("Новый");

                // Чередуем менеджеров (2 → 3 → 2 → 3 …)
                Long nextManagerId = MANAGER_IDS.get(managerCounter.getAndIncrement() % MANAGER_IDS.size());
                Manager manager = new Manager();
                manager.setId(nextManagerId);
                lead.setManager(manager);

                leadRepository.save(lead);

                // Асинхронная отправка на VPS
                vpsSyncService.sendLeadAsync(lead);

                collectorProvider.getObject().incrementStat(clientId, 1, 1, 1, 0);
                log.info("📅 [{}] {} — lastSeen={}, менеджер назначен ID={} (DB save {} мс)",
                        clientId, phone, status.getParsedLastSeen(), nextManagerId, System.currentTimeMillis() - dbStart);
            } else {
                // lastSeen отсутствует — ставим оффлайн
                lead.setLastSeen(null);
                lead.setLidStatus("Оффлайн");
                leadRepository.save(lead);
                collectorProvider.getObject().incrementStat(clientId, 1, 1, 0, 1);
                log.info("📴 [{}] {} — lastSeen отсутствует (stage={}), статус 'Оффлайн' (DB save {} мс)",
                        clientId, phone, stage, System.currentTimeMillis() - dbStart);
            }

            log.info("✅ [{}] Лид {} ({}): обработка завершена за {} мс (с начала)",
                    clientId, lead.getId(), phone, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("❌ [{}] Ошибка обработки lastSeen для {}: {} (elapsed: {} мс)",
                    clientId, phone, e.getMessage(), System.currentTimeMillis() - startTime);
            collectorProvider.getObject().incrementStat(clientId, 1, 0, 0, 1);
        }
    }



    /**
     * Нормализует телефон (заменяет 8 на 7, убирает мусор).
     */
    private String normalizePhone(String rawPhone) {
        String digits = rawPhone.replaceAll("[^\\d]", "");
        return digits.startsWith("8") ? "7" + digits.substring(1) : digits;
    }

    /**
     * Проверяет, можно ли присвоить статус "оффлайн"
     * (чтобы не перезаписать важные статусы, если уже стоит что-то вроде "назначен").
     */
    private boolean shouldMarkOffline(Lead lead) {
        String currentStatus = lead.getLidStatus();
        return currentStatus == null ||
                currentStatus.isBlank() ||
                currentStatus.equalsIgnoreCase("Оффлайн");
    }


}








