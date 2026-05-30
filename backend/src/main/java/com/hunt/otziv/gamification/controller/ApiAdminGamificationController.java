package com.hunt.otziv.gamification.controller;

import com.hunt.otziv.gamification.dto.GamificationBalancesResponse;
import com.hunt.otziv.gamification.dto.GamificationBackfillResponse;
import com.hunt.otziv.gamification.dto.GamificationEventResponse;
import com.hunt.otziv.gamification.dto.GamificationProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationRulesRequest;
import com.hunt.otziv.gamification.dto.GamificationRulesResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerRebuildResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerSummaryResponse;
import com.hunt.otziv.gamification.dto.GamificationScorePreviewResponse;
import com.hunt.otziv.gamification.dto.GamificationSettingsRequest;
import com.hunt.otziv.gamification.dto.GamificationSettingsResponse;
import com.hunt.otziv.gamification.service.GamificationBackfillService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.gamification.service.GamificationRuleService;
import com.hunt.otziv.gamification.service.GamificationSettingsService;
import com.hunt.otziv.gamification.service.GamificationShadowScoreService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/gamification")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
public class ApiAdminGamificationController {

    private final GamificationSettingsService settingsService;
    private final GamificationEventService eventService;
    private final GamificationRuleService ruleService;
    private final GamificationShadowScoreService shadowScoreService;
    private final GamificationBackfillService backfillService;

    @GetMapping("/settings")
    public GamificationSettingsResponse getSettings() {
        return settingsService.getSettings();
    }

    @PutMapping("/settings")
    public GamificationSettingsResponse updateSettings(@RequestBody GamificationSettingsRequest request) {
        return settingsService.updateSettings(request);
    }

    @GetMapping("/events")
    public List<GamificationEventResponse> getEvents(@RequestParam(required = false) Integer limit) {
        return eventService.latestEvents(limit == null ? 50 : limit);
    }

    @GetMapping("/progress")
    public GamificationProgressResponse getProgress(@RequestParam(required = false) Integer days) {
        return eventService.progress(days == null ? 1 : days);
    }

    @GetMapping("/rules")
    public GamificationRulesResponse getRules() {
        return ruleService.getRules();
    }

    @PutMapping("/rules")
    public GamificationRulesResponse updateRules(@RequestBody GamificationRulesRequest request) {
        return ruleService.updateRules(request);
    }

    @GetMapping("/score-preview")
    public GamificationScorePreviewResponse getScorePreview(@RequestParam(required = false) Integer days) {
        return eventService.scorePreview(days == null ? 1 : days);
    }

    @GetMapping("/score-ledger")
    public GamificationScoreLedgerSummaryResponse getScoreLedger(@RequestParam(required = false) Integer days) {
        int safeDays = days == null ? 1 : days;
        long previewPoints = eventService.scorePreview(safeDays).totalPoints();
        return shadowScoreService.summary(safeDays, previewPoints);
    }

    @PostMapping("/score-ledger/rebuild")
    public GamificationScoreLedgerRebuildResponse rebuildScoreLedger(@RequestParam(required = false) Integer days) {
        return shadowScoreService.rebuild(days == null ? 1 : days);
    }

    @PostMapping("/events/backfill")
    public GamificationBackfillResponse backfillEvents(@RequestParam(required = false) Integer days) {
        return backfillService.backfill(days == null ? 1 : days);
    }

    @GetMapping("/balances")
    public GamificationBalancesResponse getBalances(@RequestParam(required = false) Integer days) {
        return shadowScoreService.balances(days == null ? 1 : days);
    }
}
