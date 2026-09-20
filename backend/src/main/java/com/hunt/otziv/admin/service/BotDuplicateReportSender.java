package com.hunt.otziv.admin.service;

import com.hunt.otziv.admin.repository.BotDuplicateReportRepository;
import com.hunt.otziv.admin.repository.BotDuplicateReportRepository.Claim;
import com.hunt.otziv.t_telegrambot.api.TelegramAdminDocuments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class BotDuplicateReportSender {
    private final BotDuplicateReportRepository repository;
    private final TelegramAdminDocuments telegramService;
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(fixedDelayString = "${otziv.bot-import.reports.fixed-delay-ms:30000}",
            initialDelayString = "${otziv.bot-import.reports.initial-delay-ms:60000}")
    public void sendPending() {
        if (!telegramService.canSendDocuments() || !running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < 10; i++) {
                var next = repository.claimNext();
                if (next.isEmpty()) break;
                deliver(next.get());
            }
        } catch (RuntimeException exception) {
            log.warn("Очередь отчётов о дублях временно недоступна: {}", exception.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    private void deliver(Claim claim) {
        try {
            List<Long> pending = claim.pendingRecipients();
            if (pending == null) {
                pending = recipients();
                if (pending.isEmpty()) {
                    log.warn("Отчёт о дублях ожидает Telegram администратора: reportId={}", claim.id());
                    repository.retryLater(claim);
                    return;
                }
                if (!repository.savePendingRecipients(claim, pending)) return;
            }
            List<Long> remaining = new ArrayList<>(pending);
            // The TXT attachment is created in memory: there is no temporary disk file to leak.
            byte[] document = claim.reportText().getBytes(StandardCharsets.UTF_8);
            for (Long chatId : pending) {
                if (!repository.renewLease(claim)) return;
                if (telegramService.sendDocumentOnceMessageId(chatId, document, claim.fileName()).isPresent()) {
                    remaining.remove(chatId);
                    if (!repository.savePendingRecipients(claim, List.copyOf(remaining))) return;
                }
            }
            if (remaining.isEmpty()) {
                if (repository.deleteDelivered(claim)) {
                    log.info("Отчёт о дублях доставлен, временное содержимое удалено: reportId={}", claim.id());
                }
            } else {
                repository.retryLater(claim);
            }
        } catch (RuntimeException exception) {
            log.warn("Отправка отчёта о дублях отложена: reportId={}, errorType={}",
                    claim.id(), exception.getClass().getSimpleName());
            repository.retryLater(claim);
        }
    }

    private List<Long> recipients() {
        return telegramService.adminDocumentRecipients().stream()
                .filter(id -> id != null && id != 0L).distinct().toList();
    }
}
