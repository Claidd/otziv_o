package com.hunt.otziv.gamification.controller;

import com.hunt.otziv.gamification.dto.GamificationBalancesResponse;
import com.hunt.otziv.gamification.dto.GamificationBackfillResponse;
import com.hunt.otziv.gamification.dto.GamificationEventResponse;
import com.hunt.otziv.gamification.dto.GamificationProgressResponse;
import com.hunt.otziv.gamification.dto.GamificationRulesRequest;
import com.hunt.otziv.gamification.dto.GamificationRulesResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardClaimStatusRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardRequest;
import com.hunt.otziv.gamification.dto.GamificationRewardResponse;
import com.hunt.otziv.gamification.dto.GamificationRewardSettings;
import com.hunt.otziv.gamification.dto.GamificationTokenGrantRequest;
import com.hunt.otziv.gamification.dto.GamificationWalletResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerRebuildResponse;
import com.hunt.otziv.gamification.dto.GamificationScoreLedgerSummaryResponse;
import com.hunt.otziv.gamification.dto.GamificationScorePreviewResponse;
import com.hunt.otziv.gamification.dto.GamificationSettingsRequest;
import com.hunt.otziv.gamification.dto.GamificationSettingsResponse;
import com.hunt.otziv.gamification.service.GamificationBackfillService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.gamification.service.GamificationRuleService;
import com.hunt.otziv.gamification.service.GamificationRewardService;
import com.hunt.otziv.gamification.service.GamificationSettingsService;
import com.hunt.otziv.gamification.service.GamificationShadowScoreService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

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
    private final GamificationRewardService rewardService;

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

    @GetMapping("/rewards")
    public List<GamificationRewardResponse> rewards() {
        return rewardService.adminRewards();
    }

    @PostMapping("/rewards")
    public GamificationRewardResponse createReward(@RequestBody GamificationRewardRequest request) {
        return rewardService.save(null, request);
    }

    @PutMapping("/rewards/{rewardId}")
    public GamificationRewardResponse updateReward(
            @PathVariable Long rewardId,
            @RequestBody GamificationRewardRequest request
    ) {
        return rewardService.save(rewardId, request);
    }

    @PostMapping(value = "/rewards/{rewardId}/image", consumes = "multipart/form-data")
    public GamificationRewardResponse uploadRewardImage(
            @PathVariable Long rewardId,
            @RequestPart("file") MultipartFile file
    ) {
        return rewardService.uploadImage(rewardId, file);
    }

    @GetMapping("/reward-claims")
    public List<GamificationRewardClaimResponse> rewardClaims() {
        return rewardService.adminClaims();
    }

    @PutMapping("/reward-claims/{claimId}")
    public GamificationRewardClaimResponse updateRewardClaim(
            @PathVariable Long claimId,
            @RequestBody GamificationRewardClaimStatusRequest request
    ) {
        return rewardService.updateClaim(claimId, request);
    }

    @PostMapping("/tokens/grant")
    public GamificationWalletResponse grantTokens(@RequestBody GamificationTokenGrantRequest request) {
        return rewardService.grantTokens(request);
    }

    @GetMapping("/reward-settings")
    public GamificationRewardSettings rewardSettings() {
        return rewardService.settings();
    }

    @PutMapping("/reward-settings")
    public GamificationRewardSettings updateRewardSettings(@RequestBody GamificationRewardSettings request) {
        return rewardService.updateSettings(request);
    }
}
