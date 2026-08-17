package com.hunt.otziv.outreach_bridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/outreach/v1")
@ConditionalOnProperty(prefix = "outreach-bridge", name = "enabled", havingValue = "true")
public class OutreachBridgeController {
    private final OutreachBridgeService service;

    public OutreachBridgeController(OutreachBridgeService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/leads/scan")
    public List<OutreachBridgeDtos.LeadResponse> scan(
            @RequestParam String sourceId,
            @RequestParam String gatewayClientId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return service.findForScan(sourceId, gatewayClientId, limit);
    }

    @GetMapping("/leads/next")
    public ResponseEntity<OutreachBridgeDtos.LeadResponse> next(
            @RequestParam String sourceId,
            @RequestParam String gatewayClientId
    ) {
        return service.findNext(sourceId, gatewayClientId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/leads/by-phone")
    public ResponseEntity<OutreachBridgeDtos.LeadResponse> byPhone(@RequestParam String phone) {
        return service.findByPhone(phone)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/leads/{leadId}/last-seen")
    public ResponseEntity<Void> recordLastSeen(
            @PathVariable long leadId,
            @RequestBody OutreachBridgeDtos.LastSeenUpdate update
    ) {
        service.recordLastSeen(leadId, update);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/leads/{leadId}/stage")
    public ResponseEntity<Void> updateStage(
            @PathVariable long leadId,
            @RequestBody OutreachBridgeDtos.StageUpdate update
    ) {
        service.updateStage(leadId, update);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/leads/{leadId}/offer-sent")
    public ResponseEntity<Void> markOfferSent(@PathVariable long leadId) {
        service.markOfferSent(leadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/templates/initial")
    public ResponseEntity<OutreachBridgeDtos.TextResponse> initialTemplate() {
        return service.randomInitialTemplate().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/templates/offer")
    public ResponseEntity<OutreachBridgeDtos.TextResponse> offerTemplate() {
        return service.randomOfferTemplate().map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }


    @PostMapping("/notifications/last-seen")
    public ResponseEntity<Void> notifyLastSeen(@RequestBody OutreachBridgeDtos.LastSeenReport report) {
        service.notifyLastSeen(report);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/dispatch")
    public ResponseEntity<Void> notifyDispatch(@RequestBody OutreachBridgeDtos.DispatchReport report) {
        service.notifyDispatch(report);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/reply-after-offer")
    public ResponseEntity<Void> notifyReply(
            @RequestBody OutreachBridgeDtos.ReplyAfterOfferNotification notification
    ) {
        service.notifyReplyAfterOffer(notification);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/failure")
    public ResponseEntity<Void> notifyFailure(@RequestBody OutreachBridgeDtos.FailureNotification notification) {
        service.notifyFailure(notification);
        return ResponseEntity.noContent().build();
    }
}
