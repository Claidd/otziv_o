package com.hunt.otziv.reputationai.api;

import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationDeepReviewIdeasUpdateRequest;
import com.hunt.otziv.reputationai.api.dto.OpenAiProviderDiagnostics;
import com.hunt.otziv.reputationai.api.dto.ReputationAiModelProfile;
import com.hunt.otziv.reputationai.api.dto.ReputationAiStatus;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewCheckRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesApplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewTemplatesRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationAiPromptUpdateRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewReplyResponse;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationReviewRewriteResponse;
import com.hunt.otziv.reputationai.application.CompanyResearchService;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchJobService;
import com.hunt.otziv.reputationai.application.DeepCompanyResearchService;
import com.hunt.otziv.reputationai.application.ReputationAiMarkdownExportService;
import com.hunt.otziv.reputationai.application.ReputationAiPdfExportService;
import com.hunt.otziv.reputationai.application.ReputationAiPromptService;
import com.hunt.otziv.reputationai.application.ReputationContentPackService;
import com.hunt.otziv.reputationai.application.ReputationContentPackJobService;
import com.hunt.otziv.reputationai.application.ReputationReviewTemplateService;
import com.hunt.otziv.reputationai.application.ReputationSingleReviewDraftService;
import com.hunt.otziv.reputationai.application.ReviewDraftService;
import com.hunt.otziv.reputationai.application.ReviewReplyService;
import com.hunt.otziv.reputationai.application.ReviewSafetyService;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.config.DeepResearchProfile;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.domain.ReputationAiPrompt;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptPreview;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptValidation;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptVersion;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationContentPackJobStatus;
import com.hunt.otziv.reputationai.domain.ReputationReviewTemplatesResult;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchJobStatus;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;
import com.hunt.otziv.reputationai.infrastructure.ai.AiProviderRouter;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponsesClient;
import com.hunt.otziv.reputationai.infrastructure.search.SearchProviderRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
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
    private final ReputationAiMarkdownExportService markdownExportService;
    private final ReputationAiPdfExportService pdfExportService;
    private final ReputationReviewTemplateService reviewTemplateService;
    private final ReputationSingleReviewDraftService singleReviewDraftService;
    private final ReviewDraftService reviewDraftService;
    private final ReviewReplyService reviewReplyService;
    private final ReviewSafetyService reviewSafetyService;
    private final ReputationAiPromptService promptService;
    private final AiProviderRouter aiProviderRouter;
    private final SearchProviderRouter searchProviderRouter;
    private final OpenAiResponsesClient openAiResponsesClient;
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
                openAiDiagnostics(),
                deepResearchProfiles(),
                contentPackProfiles(),
                warnings
        );
    }

    @PostMapping("/status/openai-check")
    public OpenAiProviderDiagnostics checkOpenAiRoute() {
        openAiResponsesClient.checkConnection();
        return openAiDiagnostics();
    }

    @GetMapping("/prompts")
    public List<ReputationAiPrompt> prompts() {
        return promptService.listPrompts();
    }

    @GetMapping("/prompts/{key}/history")
    public List<ReputationAiPromptVersion> promptHistory(
            @PathVariable String key,
            @RequestParam(defaultValue = "8") int limit
    ) {
        return promptService.history(key, limit);
    }

    @PostMapping("/prompts/{key}/validate")
    public ReputationAiPromptValidation validatePrompt(
            @PathVariable String key,
            @RequestBody ReputationAiPromptUpdateRequest request
    ) {
        return promptService.validate(key, request == null ? null : request.content());
    }

    @PostMapping("/prompts/{key}/preview")
    public ReputationAiPromptPreview previewPrompt(
            @PathVariable String key,
            @RequestBody ReputationAiPromptUpdateRequest request
    ) {
        return promptService.preview(key, request == null ? null : request.content());
    }

    @PostMapping("/prompts/{key}/presets/{presetKey}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ReputationAiPrompt applyPromptPreset(
            @PathVariable String key,
            @PathVariable String presetKey,
            Principal principal
    ) {
        return promptService.applyPreset(key, presetKey, principalName(principal));
    }

    @PutMapping("/prompts/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ReputationAiPrompt updatePrompt(
            @PathVariable String key,
            @RequestBody ReputationAiPromptUpdateRequest request,
            Principal principal
    ) {
        return promptService.update(key, request == null ? null : request.content(), principalName(principal));
    }

    @DeleteMapping("/prompts/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER')")
    public ReputationAiPrompt resetPrompt(@PathVariable String key, Principal principal) {
        return promptService.reset(key, principalName(principal));
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

    @PostMapping("/companies/{companyId}/deep-research/jobs/refresh-sources")
    public DeepCompanyResearchJobStatus refreshDeepResearchSources(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        try {
            return deepCompanyResearchJobService.refreshSources(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/deep-research/jobs/rebuild-text")
    public DeepCompanyResearchJobStatus rebuildDeepResearchText(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        try {
            return deepCompanyResearchJobService.rebuildText(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/deep-research/jobs/rebuild-section")
    public DeepCompanyResearchJobStatus rebuildDeepResearchSection(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationResearchRequest request
    ) {
        try {
            return deepCompanyResearchJobService.rebuildSection(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/companies/{companyId}/deep-research/jobs/latest")
    public DeepCompanyResearchJobStatus latestDeepResearchJob(@PathVariable Long companyId) {
        return deepCompanyResearchJobService.findLatest(companyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Глубокий GPT-отчет пока не запускался"));
    }

    @GetMapping("/companies/{companyId}/deep-research/jobs/history")
    public List<DeepCompanyResearchJobStatus> deepResearchJobHistory(
            @PathVariable Long companyId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return deepCompanyResearchJobService.history(companyId, limit);
    }

    @PutMapping("/companies/{companyId}/deep-research/jobs/{jobId}/review-ideas")
    public DeepCompanyResearchJobStatus updateDeepResearchReviewIdeas(
            @PathVariable Long companyId,
            @PathVariable Long jobId,
            @RequestBody(required = false) ReputationDeepReviewIdeasUpdateRequest request
    ) {
        try {
            return deepCompanyResearchJobService.updateReviewIdeas(
                    companyId,
                    jobId,
                    request == null ? List.of() : request.reviewIdeas()
            );
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping(value = "/companies/{companyId}/deep-research/jobs/latest/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportLatestDeepResearchMarkdown(@PathVariable Long companyId) {
        return markdownExportService.latestDeepReport(companyId)
                .map(this::markdownResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый глубокий GPT-отчет для экспорта пока не найден"));
    }

    @GetMapping(value = "/companies/{companyId}/deep-research/jobs/{jobId}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportDeepResearchMarkdown(
            @PathVariable Long companyId,
            @PathVariable Long jobId
    ) {
        return markdownExportService.deepReport(companyId, jobId)
                .map(this::markdownResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый глубокий GPT-отчет для экспорта не найден"));
    }

    @GetMapping(value = "/companies/{companyId}/deep-research/jobs/latest/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportLatestDeepResearchPdf(@PathVariable Long companyId) {
        return pdfExportService.latestDeepReport(companyId)
                .map(this::pdfResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый глубокий GPT-отчет для PDF-экспорта пока не найден"));
    }

    @GetMapping(value = "/companies/{companyId}/deep-research/jobs/{jobId}/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportDeepResearchPdf(
            @PathVariable Long companyId,
            @PathVariable Long jobId
    ) {
        return pdfExportService.deepReport(companyId, jobId)
                .map(this::pdfResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый глубокий GPT-отчет для PDF-экспорта не найден"));
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

    @GetMapping(value = "/companies/{companyId}/content-pack/jobs/latest/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportLatestContentPackMarkdown(@PathVariable Long companyId) {
        return markdownExportService.latestContentPack(companyId)
                .map(this::markdownResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый AI-пакет для экспорта пока не найден"));
    }

    @GetMapping(value = "/companies/{companyId}/content-pack/jobs/latest/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportLatestContentPackPdf(@PathVariable Long companyId) {
        return pdfExportService.latestContentPack(companyId)
                .map(this::pdfResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Готовый AI-пакет для PDF-экспорта пока не найден"));
    }

    @PostMapping("/companies/{companyId}/content-pack/review-templates")
    public ReputationReviewTemplatesResult improveReviewTemplates(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationReviewTemplatesRequest request
    ) {
        try {
            return reviewTemplateService.generate(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/content-pack/review-templates/apply")
    public ReputationContentPack applyReviewTemplates(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationReviewTemplatesApplyRequest request
    ) {
        try {
            return reviewTemplateService.apply(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/companies/{companyId}/content-pack/review-draft")
    public ReputationSingleReviewDraftResult singleReviewDraft(
            @PathVariable Long companyId,
            @RequestBody(required = false) ReputationSingleReviewDraftRequest request
    ) {
        try {
            return singleReviewDraftService.generate(companyId, request);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
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

    private String principalName(Principal principal) {
        return principal == null || principal.getName() == null ? "unknown" : principal.getName();
    }

    private ResponseEntity<String> markdownResponse(ReputationAiMarkdownExportService.MarkdownExport export) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(export.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(export.markdown());
    }

    private ResponseEntity<byte[]> pdfResponse(ReputationAiPdfExportService.PdfExport export) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(export.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(export.bytes());
    }

    private OpenAiProviderDiagnostics openAiDiagnostics() {
        ReputationAiProperties.OpenAi openai = properties.getOpenai();
        ReputationAiProperties.OpenAi.Proxy proxy = openai.getProxy();
        boolean configured = !isBlank(openai.getApiKey()) && !isBlank(openai.getBaseUrl()) && !isBlank(openai.getModel());
        boolean proxyConfigured = proxy.isEnabled() && !isBlank(proxy.getHost());
        boolean proxyAuthConfigured = !isBlank(proxy.getUsername());
        OpenAiResponsesClient.OpenAiLastCheck lastCheck = openAiResponsesClient.lastCheck();
        return new OpenAiProviderDiagnostics(
                openai.getBaseUrl(),
                configured,
                proxy.isEnabled(),
                proxyConfigured,
                proxyAuthConfigured,
                proxy.getHost(),
                proxy.getPort(),
                proxyConfigured,
                proxyConfigured ? "proxy" : "direct",
                lastCheck.status(),
                lastCheck.httpStatus(),
                lastCheck.message(),
                lastCheck.checkedAt()
        );
    }
}
