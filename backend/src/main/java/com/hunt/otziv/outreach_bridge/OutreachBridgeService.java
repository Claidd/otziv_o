package com.hunt.otziv.outreach_bridge;

import com.hunt.otziv.l_lead.event.LeadEventPublisher;
import com.hunt.otziv.l_lead.model.Lead;
import com.hunt.otziv.l_lead.repository.LeadsRepository;
import com.hunt.otziv.l_lead.service.VpsSyncService;
import com.hunt.otziv.text_generator.alltext.service.HelloTextService;
import com.hunt.otziv.text_generator.alltext.service.OfferTextService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.whatsapp.service.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(prefix = "outreach-bridge", name = "enabled", havingValue = "true")
public class OutreachBridgeService {
    private final LeadsRepository repository;
    private final LeadEventPublisher leadEventPublisher;
    private final VpsSyncService vpsSyncService;
    private final HelloTextService helloTextService;
    private final OfferTextService offerTextService;
    private final NotificationService notificationService;
    private final OutreachBridgeProperties properties;

    public OutreachBridgeService(
            LeadsRepository repository,
            LeadEventPublisher leadEventPublisher,
            VpsSyncService vpsSyncService,
            HelloTextService helloTextService,
            OfferTextService offerTextService,
            NotificationService notificationService,
            OutreachBridgeProperties properties
    ) {
        this.repository = repository;
        this.leadEventPublisher = leadEventPublisher;
        this.vpsSyncService = vpsSyncService;
        this.helloTextService = helloTextService;
        this.offerTextService = offerTextService;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<OutreachBridgeDtos.LeadResponse> findForScan(
            String sourceId, String gatewayClientId, int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 1_000));
        return repository.findOutreachBatch(sourceId(sourceId), properties.getStatuses().getScan(), today(),
                        PageRequest.of(0, limit)).stream()
                .map(lead -> toResponse(lead, gatewayClientId))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<OutreachBridgeDtos.LeadResponse> findNext(String sourceId, String gatewayClientId) {
        return repository.findNextOutreachLead(sourceId(sourceId), properties.getStatuses().getReady(), today(),
                        PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(lead -> toResponse(lead, gatewayClientId));
    }

    @Transactional(readOnly = true)
    public Optional<OutreachBridgeDtos.LeadResponse> findByPhone(String phone) {
        return repository.findOutreachByTelephoneLead(phone)
                .map(lead -> toResponse(lead, configuredGatewayClientId(lead)));
    }

    @Transactional
    public void recordLastSeen(long leadId, OutreachBridgeDtos.LastSeenUpdate update) {
        BridgeStage stage = stage(update.stage());
        Lead lead = requiredLead(leadId);
        Instant lastSeen = update.lastSeen();
        lead.setLastSeen(lastSeen == null ? null
                : LocalDateTime.ofInstant(lastSeen, ZoneId.of(properties.getZone())));
        lead.setLidStatus(status(stage));
        if (update.managerId() != null) {
            Manager manager = new Manager();
            manager.setId(update.managerId());
            lead.setManager(manager);
        }
        saveAndPublish(lead);
        if (stage == BridgeStage.READY) {
            vpsSyncService.sendLeadAsync(lead);
        }
    }

    @Transactional
    public void updateStage(long leadId, OutreachBridgeDtos.StageUpdate update) {
        Lead lead = requiredLead(leadId);
        lead.setLidStatus(status(stage(update.stage())));
        saveAndPublish(lead);
    }

    @Transactional
    public void markOfferSent(long leadId) {
        Lead lead = requiredLead(leadId);
        lead.setOffer(true);
        saveAndPublish(lead);
    }

    public Optional<OutreachBridgeDtos.TextResponse> randomInitialTemplate() {
        return randomText(helloTextService.findAllTexts()).map(OutreachBridgeDtos.TextResponse::new);
    }

    public Optional<OutreachBridgeDtos.TextResponse> randomOfferTemplate() {
        return randomText(offerTextService.findAllTexts()).map(OutreachBridgeDtos.TextResponse::new);
    }

    public void notifyLastSeen(OutreachBridgeDtos.LastSeenReport report) {
        notifyAdmins("📊 Проверка lastSeen завершена\n"
                + "Обработано: " + report.processed() + "\n"
                + "Можно отправлять: " + report.eligible() + "\n"
                + "Давно не в сети: " + report.stale() + "\n"
                + "Нет WhatsApp/скрыт статус: " + (report.noWhatsApp() + report.unavailable()) + "\n"
                + "Ошибок: " + report.failed());
    }

    public void notifyDispatch(OutreachBridgeDtos.DispatchReport report) {
        notifyAdmins("📨 Рассылка завершена\n"
                + "Проверено: " + report.examined() + "\n"
                + "Отправлено: " + report.sent() + "\n"
                + "Пропущено по lastSeen: " + report.stale() + "\n"
                + "Нет WhatsApp: " + report.noWhatsApp() + "\n"
                + "Ошибок: " + report.failed());
    }

    public void notifyReplyAfterOffer(OutreachBridgeDtos.ReplyAfterOfferNotification value) {
        String header = value.containsLink()
                ? "✅ *Клиент прислал ссылку после оффера*"
                : "🔔 *Клиент ответил после оффера*";
        notifyAdmins(header + "\n\nТелефон: `" + escape(value.phone())
                + "`\nWhatsApp-клиент: `" + escape(value.clientId()) + "`\n\n" + escape(value.message()));
    }

    public void notifyFailure(OutreachBridgeDtos.FailureNotification value) {
        notifyAdmins("⚠️ Ошибка outreach\nОперация: " + escape(value.operation())
                + "\nLead ID: " + value.leadId() + "\nПричина: " + escape(value.detail()));
    }

    private void notifyAdmins(String message) {
        if (!properties.getAdminChatIds().isEmpty()) {
            notificationService.sendAdminAlert(message, properties.getAdminChatIds());
        }
    }

    private void saveAndPublish(Lead lead) {
        repository.save(lead);
        leadEventPublisher.publishUpdate(lead);
    }

    private Lead requiredLead(long leadId) {
        return repository.findById(leadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }

    private OutreachBridgeDtos.LeadResponse toResponse(Lead lead, String gatewayClientId) {
        Instant lastSeen = lead.getLastSeen() == null ? null
                : lead.getLastSeen().atZone(ZoneId.of(properties.getZone())).toInstant();
        boolean initialMessageSent = lead.isOffer()
                || properties.getStatuses().getInitialSent().equals(lead.getLidStatus());
        return new OutreachBridgeDtos.LeadResponse(
                lead.getId(), lead.getTelephoneLead(), gatewayClientId, lastSeen,
                lead.isOffer(), initialMessageSent);
    }

    private String configuredGatewayClientId(Lead lead) {
        return lead.getTelephone() == null ? null : lead.getTelephone().getClient();
    }


    private long sourceId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lead source id must be numeric");
        }
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }

    private BridgeStage stage(String value) {
        try {
            return BridgeStage.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown outreach stage");
        }
    }

    private String status(BridgeStage stage) {
        OutreachBridgeProperties.Statuses values = properties.getStatuses();
        return switch (stage) {
            case READY -> values.getReady();
            case INITIAL_SENT, OFFER_SENT -> values.getInitialSent();
            case DECLINED -> values.getDeclined();
            case NO_WHATSAPP -> values.getNoWhatsApp();
            case LAST_SEEN_STALE -> values.getLastSeenStale();
            case LAST_SEEN_UNAVAILABLE -> values.getLastSeenUnavailable();
            case FAILED -> values.getFailed();
        };
    }

    private Optional<String> randomText(List<String> values) {
        if (values == null) {
            return Optional.empty();
        }
        List<String> usable = values.stream().filter(value -> value != null && !value.isBlank()).toList();
        return usable.isEmpty() ? Optional.empty()
                : Optional.of(usable.get(ThreadLocalRandom.current().nextInt(usable.size())));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("_", "\\_").replace("*", "\\*")
                .replace("[", "\\[").replace("`", "\\`")
                .replace("~", "\\~").replace(">", "\\>");
    }

    private enum BridgeStage {
        READY, INITIAL_SENT, OFFER_SENT, DECLINED, NO_WHATSAPP,
        LAST_SEEN_STALE, LAST_SEEN_UNAVAILABLE, FAILED
    }
}
