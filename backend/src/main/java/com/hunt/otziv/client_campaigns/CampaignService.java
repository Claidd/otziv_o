package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import com.hunt.otziv.config.settings.api.OutboundMessagePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CampaignService {
    private final CampaignStore store;
    private final CampaignSender sender;
    private final OutboundMessagePolicy settings;
    private final Clock clock;
    /** Autoresponder master switch and pause also stop campaigns (owner decision, field-injected). */
    @Autowired(required = false)
    private com.hunt.otziv.client_messages.api.BackgroundDispatchBudget dispatchBudget;
    @Autowired
    public CampaignService(CampaignStore store, CampaignSender sender, OutboundMessagePolicy settings) {
        this(store,sender,settings,Clock.systemUTC());
    }
    CampaignService(CampaignStore store, CampaignSender sender, OutboundMessagePolicy settings, Clock clock) {
        this.store=store; this.sender=sender; this.settings=settings; this.clock=clock;
    }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public boolean liveEnabled() {
        try { return settings.clientMessagesEnabled(); }
        catch (RuntimeException unavailable) { return false; }
    }
    public Board board() {
        var today = CampaignSchedule.day(now());
        List<Summary> campaigns = store.list().stream().map(c -> new Summary(c,store.counts(c.id()),
                today.equals(c.budgetDay()) ? c.budgetUsed() : 0)).toList();
        return new Board(liveEnabled(),campaigns);
    }
    @Scheduled(fixedDelayString="${client-offers.tick-ms:10000}", scheduler="clientOfferScheduler")
    public void tick() {
        if (!liveEnabled()) return;
        for (String id : store.due(now())) {
            try {
                var claim = store.claim(id,now());
                if (claim == null) continue;
                // Re-read the switch and campaign immediately before handing work to the transport.
                if (!liveEnabled() || !"RUNNING".equals(store.get(id).state())) {
                    store.releaseWithoutSend(claim);
                    continue;
                }
                var admission = autoresponderAdmission(claim);
                if (!admission.allowed()) {
                    // Nothing was sent: the recipient, its daily quota unit and the slot return to the campaign.
                    store.releaseWithoutSend(claim, retryAtUtc(admission));
                    continue;
                }
                var result = sender.send(claim);
                store.finish(claim,result,now());
            } catch (RuntimeException failure) {
                // The committed SENDING row survives failures, including failure to record a receipt.
                log.warn("Offer campaign tick failed: campaignId={}, errorType={}",id,failure.getClass().getSimpleName());
            }
        }
    }

    /** Master switch, pause and the sending account's interval shared with the autoresponder. */
    private com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Admission autoresponderAdmission(Claim claim) {
        if (dispatchBudget == null) {
            return com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Admission.allow();
        }
        var recipient = claim == null ? null : claim.recipient();
        long sourceId = recipient == null ? 0L : recipient.id();
        return dispatchBudget.reserveForSender(
                com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Purpose.OFFER_CAMPAIGN,
                "OFFER_CAMPAIGN", sourceId, platform(recipient), recipient == null ? null : recipient.clientId());
    }

    /** Platform of the account that sends to this recipient: WhatsApp client id, Telegram or MAX bot. */
    static String platform(Recipient recipient) {
        String key = recipient == null || recipient.destinationKey() == null ? "" : recipient.destinationKey();
        if (key.startsWith("WHATSAPP:") || key.startsWith("WHATSAPP_PHONE:")) return "WHATSAPP";
        if (key.startsWith("TELEGRAM:")) return "TELEGRAM";
        if (key.startsWith("MAX:")) return "MAX";
        return null;
    }

    /** Admission times are messaging storage time (JVM zone); campaigns schedule in UTC. */
    private LocalDateTime retryAtUtc(com.hunt.otziv.client_messages.api.BackgroundDispatchBudget.Admission admission) {
        LocalDateTime now = now();
        LocalDateTime next = admission.nextAttemptAt() == null ? null
                : admission.nextAttemptAt().atZone(java.time.ZoneId.systemDefault())
                        .withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
        if (next == null || next.isBefore(now.plusSeconds(10))) return now.plusSeconds(10);
        return next.isAfter(now.plusDays(1)) ? now.plusDays(1) : next;
    }
}
