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
                var result = sender.send(claim);
                store.finish(claim,result,now());
            } catch (RuntimeException failure) {
                // The committed SENDING row survives failures, including failure to record a receipt.
                log.warn("Offer campaign tick failed: campaignId={}, errorType={}",id,failure.getClass().getSimpleName());
            }
        }
    }
}
