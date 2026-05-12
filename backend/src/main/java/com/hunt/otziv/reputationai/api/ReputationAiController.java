package com.hunt.otziv.reputationai.api;

import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationAiModelProfile;
import com.hunt.otziv.reputationai.api.dto.ReputationAiStatus;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewCheckRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyResponse;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteResponse;
import com.hunt.otziv.reputationai.application.CompanyResearchService;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchJobService;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchService;
import com.hunt.otziv.reputationai.application.ReputationContentPackService;
import com.hunt.otziv.reputationai.application.ReputationContentPackJobService;
import com.hunt.otziv.reputationai.application.ReviewDraftService;
import com.hunt.otziv.reputationai.application.ReviewReplyService;
import com.hunt.otziv.reputationai.application.ReviewSafetyService;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.config.DeepResearchProfile;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationContentPackJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;
import com.hunt.otziv.reputationai.infrastructure.ai.AiProviderRouter;
import com.hunt.otziv.reputationai.infrastructure.search.SearchProviderRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ai/reputation")
@PreAuthorize("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'MARKETOLOG')")
public class ReputationAiController {

    private final CompanyResearchService researchService;
    private final DeepCompanyResearchJobService deepCompanyResearchJobService;
    private final DeepCompanyResearchService deepCompanyResearchService;
    private final ReputationContentPackService contentPackService;
    private final ReputationContentPackJobService contentPackJobService;
    private final ReviewDraftService reviewDraftService;
    private final ReviewReplyService reviewReplyService;
    private final ReviewSafetyService reviewSafetyService;
    private final AiProviderRouter aiProviderRouter;
    private final SearchProviderRouter searchProviderRouter;
    private final ReputationAiProperties properties;

    @GetMapping("/status")
    public ReputationAiStatus status() {
        boolean aiAvailable = aiProviderRouter.activeProviderAvailable();
        boolean searchAvailable = searchProviderRouter.activeProviderAvailable();
        boolean yandexGptConfigured = !isBlank(properties.getYandex().getApiKey())
                && !isBlank(properties.getYandex().getFolderId());
        boolean yandexSearchConfigured = !isBlank(properties.getSearch().getYandex().getApiKey())
                && !isBlank(properties.getSearch().getYandex().getFolderId());
        boolean openAiConfigured = !isBlank(properties.getOpenai().getApiKey())
                && !isBlank(properties.getOpenai().getModel());
        boolean openAiProxyEnabled = properties.getOpenai().getProxy().isEnabled()
                && !isBlank(properties.getOpenai().getProxy().getHost());
        List<String> warnings = new ArrayList<>();
        if (!aiAvailable || "local".equalsIgnoreCase(aiProviderRouter.activeProviderName())) {
            warnings.add("Генерация работает через локальный шаблон. Для OpenAI укажите REPUTATION_AI_PROVIDER=openai и OPENAI_API_KEY, для YandexGPT — REPUTATION_AI_PROVIDER=yandexgpt и ключи.");
        }
        if (!searchAvailable || "local".equalsIgnoreCase(searchProviderRouter.activeProviderName())) {
            warnings.add("Публичный поиск выключен. Для Yandex Search укажите REPUTATION_SEARCH_PROVIDER=yandex и ключи.");
        }

        return new ReputationAiStatus(
                aiProviderRouter.activeProviderName(),
                aiAvailable,
                searchProviderRouter.activeProviderName(),
                searchAvailable,
                yandexGptConfigured,
                yandexSearchConfigured,
                openAiConfigured,
                openAiProxyEnabled,
                properties.getYandex().getModel(),
                properties.getOpenai().getModel(),
                properties.getOpenai().getResearchReport().getModel(),
                ContentPackProfile.QUALITY.model(),
                deepResearchProfiles(),
                contentPackProfiles(),
                warnings
        );
    }

    private List<ReputationAiModelProfile> deepResearchProfiles() {
        return DeepResearchProfile.all().stream()
                .map(profile -> new ReputationAiModelProfile(
                        profile.key(),
                        profile.label(),
                        profile.model(),
                        profile.description(),
                        profile.maxToolCalls(),
                        profile.maxOutputTokens(),
                        profile.reasoningEffort(),
                        profile.searchContextSize()
                ))
                .toList();
    }

    private List<ReputationAiModelProfile> contentPackProfiles() {
        return ContentPackProfile.all().stream()
                .map(profile -> new ReputationAiModelProfile(
                        profile.key(),
                        profile.label(),
                        profile.model(),
                        profile.description(),
                        0,
                        profile.maxOutputTokens(),
                        profile.reasoningEffort(),
                        "off"
                ))
                .toList();
    }

    @PostMapping("/companies/{companyId}/research")
    public ResearchSnapshot research(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        return researchService.createSnapshot(companyId, request);
    }

    @PostMapping("/companies/{companyId}/deep-research")
    public DeepCompanyResearchReport deepResearch(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        try {
            return deepCompanyResearchService.createReport(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/deep-research/jobs")
    public DeepCompanyResearchJobStatus startDeepResearchJob(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        try {
            return deepCompanyResearchJobService.start(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/companies/{companyId}/deep-research/jobs/latest")
    public DeepCompanyResearchJobStatus latestDeepResearchJob(@PathVariable Long companyId) {
        return deepCompanyResearchJobService.findLatest(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Глубокий GPT-отчет пока не запускался"));
    }

    @GetMapping("/companies/{companyId}/research/latest")
    public ResearchSnapshot latestResearch(@PathVariable Long companyId) {
        return researchService.findLatestSnapshot(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI-слепок компании пока не создан"));
    }

    @PostMapping("/companies/{companyId}/content-pack")
    public ReputationContentPack contentPack(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationContentPackRequest request
    ) {
        try {
            return contentPackService.createContentPack(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/content-pack/jobs")
    public ReputationContentPackJobStatus startContentPackJob(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationContentPackRequest request
    ) {
        try {
            return contentPackJobService.start(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/companies/{companyId}/content-pack/jobs/latest")
    public ReputationContentPackJobStatus latestContentPackJob(@PathVariable Long companyId) {
        return contentPackJobService.findLatest(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI-пакет для этой компании пока не запускался"));
    }

    @PostMapping("/companies/{companyId}/review-draft")
    public ReviewDraftResult reviewDraft(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationReviewDraftRequest request
    ) {
        return reviewDraftService.createDraft(companyId, request);
    }

    @PostMapping("/review/rewrite")
    public ReputationReviewRewriteResponse rewriteReview(@RequestBody ReputationReviewRewriteRequest request) {
        return reviewDraftService.rewrite(request);
    }

    @PostMapping("/review/check")
    public ReviewSafetyReport checkReview(@RequestBody ReputationReviewCheckRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст для проверки не указан");
        }

        return reviewSafetyService.check(request.text(), request.allowedFacts());
    }

    @PostMapping("/companies/{companyId}/reply/positive")
    public ReputationReviewReplyResponse positiveReply(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationReviewReplyRequest request
    ) {
        return reviewReplyService.positive(companyId, request);
    }

    @PostMapping("/companies/{companyId}/reply/negative")
    public ReputationReviewReplyResponse negativeReply(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationReviewReplyRequest request
    ) {
        return reviewReplyService.negative(companyId, request);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
