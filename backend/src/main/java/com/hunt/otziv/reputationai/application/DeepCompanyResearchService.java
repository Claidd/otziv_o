package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.CompanyResearchAnswer;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponseResult;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponsesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeepCompanyResearchService {

    private final CompanyRepository companyRepository;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final CompanyResearchService companyResearchService;
    private final ObjectMapper objectMapper;
    private final ReputationAiPromptService promptService;

    public DeepCompanyResearchReport createReport(Long companyId, ReputationResearchRequest request) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException(activeProviderDisplayName() + " не настроен: проверьте ключи AI-провайдера.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));
        ResearchSnapshot sourceSnapshot = externalSearchSnapshot(companyId, safeRequest);

        OpenAiResponseResult response = openAiResponsesClient.createResearchReportResponse(
                instructions(),
                researchInput(company, safeRequest, sourceSnapshot),
                safeRequest.deepResearchProfile()
        );
        if (response.text().isBlank()) {
            String errorMessage = response.errorMessage();
            throw new IllegalStateException(errorMessage.isBlank()
                    ? activeProviderDisplayName() + " не вернул текст глубокого исследования."
                    : errorMessage);
        }

        DeepCompanyResearchReport report;
        try {
            report = parseReport(company, response);
        } catch (ReportParseException exception) {
            report = repairAndParseReport(company, response, exception);
        }
        report = expandThinYandexReportIfNeeded(company, safeRequest, sourceSnapshot, report);
        if (!safeRequest.shouldEnrichCollectionGaps()) {
            return reportWithCollectionGapEnrichmentStatus(
                    company,
                    report,
                    "info",
                    "Автодосбор по рекомендациям пропущен настройкой запуска."
            );
        }
        return enrichCollectionGaps(company, safeRequest, report);
    }

    public DeepCompanyResearchReport refreshSources(
            Long companyId,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException(activeProviderDisplayName() + " не настроен: проверьте ключи AI-провайдера.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для обновления источников.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));
        ResearchSnapshot sourceSnapshot = externalSearchSnapshot(companyId, safeRequest);

        OpenAiResponseResult response = openAiResponsesClient.createSourceRefreshResponse(
                sourceRefreshInstructions(),
                sourceRefreshInput(company, safeRequest, baseReport, sourceSnapshot),
                safeRequest.deepResearchProfile()
        );
        ensureResponseText(response, activeProviderDisplayName() + " не вернул список обновлённых источников.");

        try {
            JsonNode root = readReportJson(response.text());
            List<DeepCompanyResearchReport.Source> refreshedSources = parseSources(root.path("sources"));
            List<DeepCompanyResearchReport.Source> sources = mergeSources(refreshedSources, baseReport.sources());
            List<DeepCompanyResearchReport.Section> sections = baseReport.sections();
            String markdown = baseReport.reportMarkdown().isBlank() ? sectionsToMarkdown(sections) : baseReport.reportMarkdown();
            List<String> warnings = parseWarnings(root.path("warnings"));
            warnings.add("Обновлён только список источников. Текст разделов оставлен из базового отчёта.");

            List<DeepCompanyResearchReport.QualityCheck> qualityChecks = reportQualityChecks(company, sections, sources, markdown);
            DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(company, sections, sources, qualityChecks, markdown);
            warnings.addAll(qualityChecks.stream()
                    .filter(this::isActionableQualityCheck)
                    .map(DeepCompanyResearchReport.QualityCheck::detail)
                    .filter(detail -> detail != null && !detail.isBlank())
                    .toList());

            return new DeepCompanyResearchReport(
                    company.getId(),
                    company.getTitle(),
                    company.getCity(),
                    response.provider(),
                    response.model(),
                    response.responseId(),
                    markdown,
                    sections,
                    sources,
                    warnings,
                    qualityChecks,
                    factSnapshot,
                    LocalDateTime.now()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(activeProviderDisplayName() + " вернул источники не в ожидаемом JSON-формате.", exception);
        }
    }

    public DeepCompanyResearchReport rebuildText(
            Long companyId,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException(activeProviderDisplayName() + " не настроен: проверьте ключи AI-провайдера.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для пересборки текста.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));

        OpenAiResponseResult response = openAiResponsesClient.createResearchReportRewriteResponse(
                textRebuildInstructions(),
                textRebuildInput(company, safeRequest, baseReport),
                safeRequest.deepResearchProfile()
        );
        ensureResponseText(response, activeProviderDisplayName() + " не вернул пересобранный текст отчёта.");

        try {
            return parseReport(
                    company,
                    response,
                    List.of("Текст отчёта пересобран без нового web search: использованы только сохранённые источники и факты базового отчёта.")
            );
        } catch (ReportParseException exception) {
            return repairAndParseReport(company, response, exception);
        }
    }

    public DeepCompanyResearchReport rebuildSection(
            Long companyId,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException(activeProviderDisplayName() + " не настроен: проверьте ключи AI-провайдера.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для пересборки раздела.");
        }
        if (baseReport.sections().isEmpty()) {
            throw new IllegalStateException("В базовом отчёте нет разделов для точечной пересборки.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));

        int sectionIndex = selectedSectionIndex(baseReport.sections(), safeRequest);
        DeepCompanyResearchReport.Section originalSection = baseReport.sections().get(sectionIndex);
        OpenAiResponseResult response = openAiResponsesClient.createResearchReportSectionRewriteResponse(
                sectionRewriteInstructions(),
                sectionRewriteInput(company, safeRequest, baseReport, originalSection, sectionIndex),
                safeRequest.deepResearchProfile()
        );
        ensureResponseText(response, activeProviderDisplayName() + " не вернул пересобранный раздел отчёта.");

        try {
            JsonNode root = readReportJson(response.text());
            List<DeepCompanyResearchReport.Section> parsedSections = parseSections(root.path("section"));
            if (parsedSections.isEmpty()) {
                parsedSections = parseSections(root.path("sections"));
            }
            if (parsedSections.isEmpty()) {
                throw new IllegalStateException(activeProviderDisplayName() + " не вернул section с title/body.");
            }

            DeepCompanyResearchReport.Section parsedSection = parsedSections.get(0);
            DeepCompanyResearchReport.Section rewrittenSection = new DeepCompanyResearchReport.Section(
                    parsedSection.title().isBlank() ? originalSection.title() : parsedSection.title(),
                    parsedSection.body()
            );
            if (rewrittenSection.body().isBlank()) {
                throw new IllegalStateException(activeProviderDisplayName() + " вернул пустой текст раздела.");
            }

            List<DeepCompanyResearchReport.Section> sections = new ArrayList<>(baseReport.sections());
            sections.set(sectionIndex, rewrittenSection);
            List<DeepCompanyResearchReport.Source> sources = baseReport.sources();
            String markdown = sectionsToMarkdown(sections);
            List<String> warnings = new ArrayList<>(baseReport.warnings());
            warnings.addAll(parseWarnings(root.path("warnings")));
            warnings.add("Пересобран только раздел «" + rewrittenSection.title() + "». Остальные разделы и источники оставлены из базового отчёта.");

            List<DeepCompanyResearchReport.QualityCheck> qualityChecks = reportQualityChecks(company, sections, sources, markdown);
            DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(company, sections, sources, qualityChecks, markdown);
            warnings.addAll(qualityChecks.stream()
                    .filter(this::isActionableQualityCheck)
                    .map(DeepCompanyResearchReport.QualityCheck::detail)
                    .filter(detail -> detail != null && !detail.isBlank())
                    .toList());

            return new DeepCompanyResearchReport(
                    company.getId(),
                    company.getTitle(),
                    company.getCity(),
                    response.provider(),
                    response.model(),
                    response.responseId(),
                    markdown,
                    sections,
                    sources,
                    warnings,
                    qualityChecks,
                    factSnapshot,
                    LocalDateTime.now()
            );
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(activeProviderDisplayName() + " вернул раздел не в ожидаемом JSON-формате.", exception);
        }
    }

    private String instructions() {
        return promptService.content(ReputationAiPromptKeys.DEEP_REPORT_INSTRUCTIONS);
    }

    private String researchInput(Company company, ReputationResearchRequest request, ResearchSnapshot sourceSnapshot) {
        return profileGuidance(request) + "\n\n" + promptService.content(ReputationAiPromptKeys.DEEP_REPORT_INPUT)
                .replace("{{companyFacts}}", companyFacts(company))
                .replace("{{manualDescription}}", blankToDash(request.manualDescription()))
                .replace("{{productsOrServices}}", listToText(request.productsOrServices()))
                .replace("{{publicUrls}}", listToText(request.publicUrls()))
                .replace("{{crmPriorityUrls}}", crmPriorityUrls(company, request))
                + sourceSnapshotPrompt(sourceSnapshot);
    }

    private String profileGuidance(ReputationResearchRequest request) {
        String profile = request == null || request.deepResearchProfile() == null
                ? ""
                : request.deepResearchProfile().trim().toLowerCase(Locale.ROOT);
        if (!"economy".equals(profile)) {
            return "Режим отчёта: стандартный.";
        }
        return """
                Режим отчёта: БЫСТРО / gpt-5.4-mini.
                Сожми исследование: сначала официальный сайт и CRM/карты, затем только 4-6 самых релевантных подтверждающих источников.
                Не делай полный проход по 15-20 результатам выдачи. Если официальные источники, карты и несколько качественных страниц уже дали картину, остановись и отметь ограничения в warnings.
                Верни компактный отчёт на 6-8 секций: профиль, услуги/цены, филиалы/логистика, удобства, репутационные сигналы, риски/что уточнить, идеи для постов и идеи для отзывов.
                Не растягивай таблицы и списки; спорные и слабые сведения помечай коротко.
                """;
    }

    private String sourceRefreshInstructions() {
        return promptService.content(ReputationAiPromptKeys.DEEP_REPORT_SOURCE_REFRESH_INSTRUCTIONS);
    }

    private String textRebuildInstructions() {
        return instructions() + "\n\n"
                + promptService.content(ReputationAiPromptKeys.DEEP_REPORT_REBUILD_TEXT_INSTRUCTIONS);
    }

    private String sectionRewriteInstructions() {
        return promptService.content(ReputationAiPromptKeys.DEEP_REPORT_REBUILD_SECTION_INSTRUCTIONS);
    }

    private String sourceRefreshInput(
            Company company,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport,
            ResearchSnapshot sourceSnapshot
    ) {
        return """
                Компания и CRM-факты:
                %s

                Ручное описание:
                %s

                Ручные публичные URL:
                %s

                CRM priority URL:
                %s

                Текущие sources базового отчёта:
                %s

                Краткое содержание базового отчёта, чтобы понять, какие факты нужно перепроверить:
                %s

                Свежий публичный сбор через локальный/Yandex Search:
                %s
                """.formatted(
                companyFacts(company),
                blankToDash(request.manualDescription()),
                listToText(request.publicUrls()),
                crmPriorityUrls(company, request),
                sourcesToPrompt(baseReport.sources()),
                sectionsToPrompt(baseReport.sections(), baseReport.reportMarkdown(), 14000),
                sourceSnapshot == null ? "-" : snapshotToPrompt(sourceSnapshot)
        );
    }

    private ResearchSnapshot externalSearchSnapshot(Long companyId, ReputationResearchRequest request) {
        if (openAiResponsesClient == null || !openAiResponsesClient.usesExternalSearchContext() || companyResearchService == null) {
            return null;
        }
        return companyResearchService.createSnapshot(companyId, request);
    }

    private String activeProviderDisplayName() {
        return openAiResponsesClient == null ? "AI-провайдер" : openAiResponsesClient.activeProviderDisplayName();
    }

    private String sourceSnapshotPrompt(ResearchSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        return "\n\nПредварительно собранные публичные источники и выдержки через локальный/Yandex Search:\n"
                + snapshotToPrompt(snapshot);
    }

    private String snapshotToPrompt(ResearchSnapshot snapshot) {
        if (snapshot == null) {
            return "-";
        }
        String answers = snapshot.researchAnswers().stream()
                .limit(24)
                .map(this::researchAnswerToPrompt)
                .filter(value -> !value.isBlank())
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");
        String sources = snapshot.sources().stream()
                .limit(28)
                .map(this::companySourceToPrompt)
                .filter(value -> !value.isBlank())
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");
        return """
                Компания: %s
                Город: %s
                Провайдер поиска: %s, доступен: %s, результатов: %d, страниц сайта прочитано: %d
                Поисковые запросы:
                %s

                Найденные товары/услуги:
                %s

                Выводы локального фактчека:
                %s

                Источники и выдержки:
                %s

                Предупреждения сбора:
                %s
                """.formatted(
                snapshot.companyName(),
                snapshot.city(),
                snapshot.searchProvider(),
                snapshot.searchAvailable(),
                snapshot.searchResultsCount(),
                snapshot.websitePagesRead(),
                listToText(snapshot.searchQueries()),
                listToText(snapshot.products()),
                answers,
                sources,
                listToText(snapshot.warnings())
        );
    }

    private String researchAnswerToPrompt(CompanyResearchAnswer answer) {
        if (answer == null) {
            return "";
        }
        return "- %s: %s | confidence=%d | evidence=%s | urls=%s".formatted(
                firstNonBlank(answer.question(), answer.key()),
                limitForPrompt(answer.answer(), 700),
                answer.confidence(),
                limitForPrompt(String.join("; ", answer.evidence()), 700),
                String.join(", ", answer.sourceUrls())
        );
    }

    private String companySourceToPrompt(CompanySource source) {
        if (source == null) {
            return "";
        }
        return "- [%s] %s | %s | %s".formatted(
                source.type(),
                source.title(),
                source.url(),
                limitForPrompt(source.excerpt(), 900)
        );
    }

    private String textRebuildInput(
            Company company,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        return """
                Компания и CRM-факты:
                %s

                Ручное описание:
                %s

                Ручные товары/услуги:
                %s

                Сохранённые sources, которые можно использовать:
                %s

                Сохранённые предупреждения:
                %s

                Подтверждённые факты:
                %s

                Сомнительные факты:
                %s

                Текущие sections/markdown базового отчёта:
                %s
                """.formatted(
                companyFacts(company),
                blankToDash(request.manualDescription()),
                listToText(request.productsOrServices()),
                sourcesToPrompt(baseReport.sources()),
                listToText(baseReport.warnings()),
                factsToPrompt(baseReport.factSnapshot().confirmedFacts()),
                factsToPrompt(baseReport.factSnapshot().uncertainFacts()),
                sectionsToPrompt(baseReport.sections(), baseReport.reportMarkdown(), 28000)
        );
    }

    private DeepCompanyResearchReport expandThinYandexReportIfNeeded(
            Company company,
            ReputationResearchRequest request,
            ResearchSnapshot sourceSnapshot,
            DeepCompanyResearchReport report
    ) {
        if (!isYandexReport(report) || !needsDetailExpansion(request, report)) {
            return report;
        }

        OpenAiResponseResult response = openAiResponsesClient.createResearchReportRewriteResponse(
                yandexDetailExpansionInstructions(),
                yandexDetailExpansionInput(company, request, sourceSnapshot, report),
                request.deepResearchProfile()
        );
        if (response.text().isBlank()) {
            String detail = response.errorMessage().isBlank()
                    ? activeProviderDisplayName() + " не вернул текст второго прохода детализации."
                    : response.errorMessage();
            return reportWithWarnings(report, List.of("Yandex detail pass не выполнен: " + detail));
        }

        try {
            DeepCompanyResearchReport expanded = parseReport(
                    company,
                    response,
                    List.of("Yandex detail pass: краткий web_search-отчёт автоматически пересобран в подробную аналитику со строгой JSON-схемой.")
            );
            return reportWithMergedSources(company, expanded, report.sources());
        } catch (ReportParseException exception) {
            return reportWithWarnings(report, List.of("Yandex detail pass вернул повреждённый JSON: " + exception.getMessage()));
        }
    }

    private boolean isYandexReport(DeepCompanyResearchReport report) {
        return report != null && report.provider() != null && report.provider().toLowerCase(Locale.ROOT).contains("yandex");
    }

    private boolean needsDetailExpansion(ReputationResearchRequest request, DeepCompanyResearchReport report) {
        String profile = request == null || request.deepResearchProfile() == null
                ? "maximum"
                : request.deepResearchProfile().trim().toLowerCase(Locale.ROOT);
        if ("economy".equals(profile)) {
            return false;
        }

        String markdown = report.reportMarkdown().isBlank() ? sectionsToMarkdown(report.sections()) : report.reportMarkdown();
        int sectionCount = report.sections().size();
        int totalChars = markdown.length();
        int averageSectionChars = sectionCount == 0 ? 0 : totalChars / sectionCount;
        boolean servicesAreThin = sectionBody(report, "услуг", "цен", "товар", "стоимост").length() < 900;
        boolean scenariosAreThin = sectionBody(report, "утп", "сценар").length() < 700;
        boolean staffMissingOrThin = sectionBody(report, "сотруд", "компет").length() < 500;
        boolean noPriceTable = !markdown.contains("| Позиция") && !markdown.contains("|Позиция");
        boolean reviewIdeasAreShort = countNumberedItems(sectionBody(report, "идеи", "отзыв")) < 30;

        return sectionCount < 14
                || totalChars < 18000
                || averageSectionChars < 900
                || servicesAreThin
                || scenariosAreThin
                || staffMissingOrThin
                || noPriceTable
                || reviewIdeasAreShort;
    }

    private String yandexDetailExpansionInstructions() {
        return textRebuildInstructions() + "\n\n"
                + """
                Критически важно: текущий Yandex web_search-проход может быть слишком кратким. Твоя задача - не резюмировать, а развернуть полноценный аналитический отчет.
                Верни подробный отчет уровня старого OpenAI-режима:
                - 14-16 sections в стабильном порядке из основного промпта;
                - не меньше 18 000 знаков суммарного markdown для профилей Баланс/Максимум, если входных данных не совсем пусто;
                - ключевые sections не должны быть одним абзацем: используй подзаголовки, списки и таблицы;
                - "Услуги, товары и цены" обязательно в markdown-таблице с колонками "Позиция", "Описание", "Условия/сроки", "Цена", "Источник/уверенность";
                - отдельно раскрой УТП, клиентские сценарии, цены/пакеты/доплаты, сотрудников/имена из отзывов, удобства, риски, доверие, что уточнить менеджеру;
                - если точных цен, сотрудников или услуг не найдено, не оставляй раздел коротким: напиши, какие источники проверены, какие сигналы есть, какие поля отсутствуют и что надо дозвонить;
                - идеи для отзывов верни ровно 30 пунктов.
                Не добавляй новые URL вне sources из входа и не выдумывай факты.
                Если среди sources есть одноименный источник, который противоречит CRM-городу, адресу, категории, филиалу или типу бизнеса, не смешивай его с компанией: вынеси его в warnings как сомнительный и не строй на нем услуги, цены, УТП или выводы.
                """.stripIndent().trim();
    }

    private String yandexDetailExpansionInput(
            Company company,
            ReputationResearchRequest request,
            ResearchSnapshot sourceSnapshot,
            DeepCompanyResearchReport baseReport
    ) {
        return """
                Исходный полный input первого прохода, включая CRM, ручные данные, priority URL и предварительный Yandex Search snapshot:
                %s

                Текущий слишком краткий отчет, который надо пересобрать подробнее:
                %s

                Sources текущего отчета:
                %s

                Warnings текущего отчета:
                %s
                """.formatted(
                researchInput(company, request, sourceSnapshot),
                sectionsToPrompt(baseReport.sections(), baseReport.reportMarkdown(), 30000),
                sourcesToPrompt(baseReport.sources()),
                listToText(baseReport.warnings())
        );
    }

    private String sectionBody(DeepCompanyResearchReport report, String... needles) {
        if (report == null || report.sections() == null) {
            return "";
        }
        for (DeepCompanyResearchReport.Section section : report.sections()) {
            String title = section.title() == null ? "" : section.title().toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String needle : needles) {
                if (needle != null && !needle.isBlank() && !title.contains(needle.toLowerCase(Locale.ROOT))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return section.body() == null ? "" : section.body();
            }
        }
        return "";
    }

    private int countNumberedItems(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\R")) {
            if (line.trim().matches("^\\d+[.)].+")) {
                count++;
            }
        }
        return count;
    }

    private String sectionRewriteInput(
            Company company,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport,
            DeepCompanyResearchReport.Section section,
            int sectionIndex
    ) {
        return """
                Компания и CRM-факты:
                %s

                Ручное описание:
                %s

                Ручные товары/услуги:
                %s

                Раздел для пересборки:
                index=%d
                title=%s
                body:
                %s

                Сохранённые sources, которые можно использовать:
                %s

                Подтверждённые факты:
                %s

                Сомнительные факты:
                %s

                Остальные разделы базового отчёта для контекста:
                %s
                """.formatted(
                companyFacts(company),
                blankToDash(request.manualDescription()),
                listToText(request.productsOrServices()),
                sectionIndex,
                section.title(),
                limitForPrompt(section.body(), 8000),
                sourcesToPrompt(baseReport.sources()),
                factsToPrompt(baseReport.factSnapshot().confirmedFacts()),
                factsToPrompt(baseReport.factSnapshot().uncertainFacts()),
                sectionsToPrompt(baseReport.sections(), baseReport.reportMarkdown(), 18000)
        );
    }

    private DeepCompanyResearchReport enrichCollectionGaps(
            Company company,
            ReputationResearchRequest request,
            DeepCompanyResearchReport report
    ) {
        List<String> gapItems = collectionGapItems(report);
        if (gapItems.isEmpty()) {
            return reportWithCollectionGapEnrichmentStatus(
                    company,
                    report,
                    "info",
                    "Автодосбор не требовался: в отчёте нет отдельного списка пунктов для дополнительной публичной проверки."
            );
        }

        OpenAiResponseResult response = openAiResponsesClient.createResearchGapEnrichmentResponse(
                collectionGapEnrichmentInstructions(),
                collectionGapEnrichmentInput(company, request, report, gapItems),
                request.deepResearchProfile()
        );
        if (response.text().isBlank()) {
            String detail = response.errorMessage().isBlank()
                    ? activeProviderDisplayName() + " не вернул текст дополнительного дозапроса."
                    : response.errorMessage();
            return reportWithCollectionGapEnrichmentStatus(
                    company,
                    reportWithWarnings(report, List.of("Автодосбор по рекомендациям не выполнен: " + detail)),
                    "warn",
                    "Автодосбор не выполнен: " + detail
            );
        }

        try {
            JsonNode root = readReportJson(response.text());
            List<DeepCompanyResearchReport.Section> parsedSections = parseSections(root.path("section"));
            if (parsedSections.isEmpty()) {
                parsedSections = parseSections(root.path("sections"));
            }
            if (parsedSections.isEmpty() || parsedSections.get(0).body().isBlank()) {
                return reportWithCollectionGapEnrichmentStatus(
                        company,
                        reportWithWarnings(report, List.of("Автодосбор по рекомендациям не добавлен: AI-провайдер не вернул section с title/body.")),
                        "warn",
                        "Автодосбор не добавлен: AI-провайдер не вернул section с title/body."
                );
            }

            DeepCompanyResearchReport.Section parsedSection = parsedSections.get(0);
            DeepCompanyResearchReport.Section enrichmentSection = new DeepCompanyResearchReport.Section(
                    parsedSection.title().isBlank() ? "Автодосбор по рекомендациям" : parsedSection.title(),
                    parsedSection.body()
            );
            List<DeepCompanyResearchReport.Section> sections = withCollectionGapEnrichmentSection(
                    report.sections(),
                    enrichmentSection
            );
            List<DeepCompanyResearchReport.Source> sources = mergeSources(parseSources(root.path("sources")), report.sources());
            String markdown = sectionsToMarkdown(sections);
            List<String> warnings = new ArrayList<>(report.warnings());
            warnings.addAll(parseWarnings(root.path("warnings")));

            List<DeepCompanyResearchReport.QualityCheck> qualityChecks = reportQualityChecks(company, sections, sources, markdown);
            qualityChecks = withCollectionGapEnrichmentCheck(
                    qualityChecks,
                    "info",
                    "Досбор выполнен: публично проверяемые пункты из раздела «Что ещё собирать» вынесены в отдельную секцию."
            );
            DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(company, sections, sources, qualityChecks, markdown);
            warnings.addAll(qualityChecks.stream()
                    .filter(this::isActionableQualityCheck)
                    .map(DeepCompanyResearchReport.QualityCheck::detail)
                    .filter(detail -> detail != null && !detail.isBlank())
                    .toList());

            return new DeepCompanyResearchReport(
                    report.companyId(),
                    report.companyName(),
                    report.city(),
                    report.provider(),
                    report.model(),
                    report.responseId(),
                    markdown,
                    sections,
                    sources,
                    warnings,
                    qualityChecks,
                    factSnapshot,
                    LocalDateTime.now()
            );
        } catch (Exception exception) {
            String detail = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            return reportWithCollectionGapEnrichmentStatus(
                    company,
                    reportWithWarnings(report, List.of("Автодосбор по рекомендациям не выполнен: " + detail)),
                    "warn",
                    "Автодосбор не выполнен: " + detail
            );
        }
    }

    private String collectionGapEnrichmentInstructions() {
        return """
                Ты делаешь короткий дополнительный публичный дозбор по пунктам из раздела "Что ещё собирать" в уже готовом отчёте.
                Используй web search только для публично проверяемых вещей: сайт, карты, справочники, прайсы, правила, адреса, режим, вход, этаж, парковка, доставка, отзывы, карточки филиалов, документы и страницы услуг.
                Не пытайся закрыть пункты, которые можно узнать только у владельца/менеджера: внутренние договоры, актуальный штат, закрытые прайсы, реальные правила конкретной даты, фото, которых нет публично.
                Не выдумывай факты. Если публичного подтверждения нет, прямо напиши "публично не найдено" и оставь пункт для ручного уточнения.
                Верни только валидный JSON без markdown-обёртки.
                В section.body сделай markdown с тремя блоками: "Что удалось проверить публично", "Что публично не найдено", "Что спросить у владельца". По каждому пункту дай короткий ответ и источник/причину.
                """.stripIndent().trim();
    }

    private String collectionGapEnrichmentInput(
            Company company,
            ReputationResearchRequest request,
            DeepCompanyResearchReport report,
            List<String> gapItems
    ) {
        return """
                Компания и CRM-факты:
                %s

                Ручное описание:
                %s

                Ручные публичные URL:
                %s

                Пункты из "Что ещё собирать", которые нужно попробовать закрыть публичным поиском:
                %s

                Уже сохранённые sources отчёта:
                %s

                Контекст основного отчёта:
                %s
                """.formatted(
                companyFacts(company),
                blankToDash(request.manualDescription()),
                listToText(request.publicUrls()),
                listToText(gapItems),
                sourcesToPrompt(report.sources()),
                sectionsToPrompt(report.sections(), report.reportMarkdown(), 18000)
        );
    }

    List<String> collectionGapItems(DeepCompanyResearchReport report) {
        if (report == null || report.sections().isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (DeepCompanyResearchReport.Section section : report.sections()) {
            if (!isCollectionGapSection(section.title())) {
                continue;
            }
            for (String line : section.body().split("\\R")) {
                String clean = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").trim();
                if (clean.equals(line.trim()) || clean.length() < 12) {
                    continue;
                }
                result.add(clean.replaceAll("\\s+", " "));
                if (result.size() >= 12) {
                    return result;
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private boolean isCollectionGapSection(String title) {
        String normalized = normalizeLookup(title).replace('ё', 'е');
        return normalized.contains("что еще собирать")
                || normalized.contains("что еще собрать")
                || normalized.contains("что собрать")
                || normalized.contains("дособ")
                || (normalized.contains("собир") && normalized.contains("еще"));
    }

    private boolean isCollectionGapEnrichmentSection(String title) {
        String normalized = normalizeLookup(title).replace('ё', 'е');
        return normalized.contains("автодосбор") || normalized.contains("досбор по рекомендац");
    }

    private List<DeepCompanyResearchReport.Section> withCollectionGapEnrichmentSection(
            List<DeepCompanyResearchReport.Section> sections,
            DeepCompanyResearchReport.Section enrichmentSection
    ) {
        List<DeepCompanyResearchReport.Section> result = new ArrayList<>();
        boolean inserted = false;
        for (DeepCompanyResearchReport.Section section : sections) {
            if (isCollectionGapEnrichmentSection(section.title())) {
                if (!inserted) {
                    result.add(enrichmentSection);
                    inserted = true;
                }
                continue;
            }
            result.add(section);
            if (!inserted && isCollectionGapSection(section.title())) {
                result.add(enrichmentSection);
                inserted = true;
            }
        }
        if (!inserted) {
            result.add(enrichmentSection);
        }
        return result;
    }

    private DeepCompanyResearchReport reportWithWarnings(DeepCompanyResearchReport report, List<String> additionalWarnings) {
        List<String> warnings = new ArrayList<>(report.warnings());
        warnings.addAll(additionalWarnings);
        return new DeepCompanyResearchReport(
                report.companyId(),
                report.companyName(),
                report.city(),
                report.provider(),
                report.model(),
                report.responseId(),
                report.reportMarkdown(),
                report.sections(),
                report.sources(),
                warnings,
                report.qualityChecks(),
                report.factSnapshot(),
                report.createdAt()
        );
    }

    private DeepCompanyResearchReport reportWithMergedSources(
            Company company,
            DeepCompanyResearchReport report,
            List<DeepCompanyResearchReport.Source> fallbackSources
    ) {
        List<DeepCompanyResearchReport.Source> sources = mergeSources(report.sources(), fallbackSources);
        if (sources.size() == report.sources().size()) {
            return report;
        }

        String markdown = report.reportMarkdown().isBlank() ? sectionsToMarkdown(report.sections()) : report.reportMarkdown();
        List<String> warnings = new ArrayList<>(report.warnings());
        warnings.add("Источники второго прохода дополнены источниками первого web_search/snapshot-прохода.");
        List<DeepCompanyResearchReport.QualityCheck> qualityChecks = reportQualityChecks(company, report.sections(), sources, markdown);
        DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(company, report.sections(), sources, qualityChecks, markdown);
        warnings.addAll(qualityChecks.stream()
                .filter(this::isActionableQualityCheck)
                .map(DeepCompanyResearchReport.QualityCheck::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .toList());
        return new DeepCompanyResearchReport(
                report.companyId(),
                report.companyName(),
                report.city(),
                report.provider(),
                report.model(),
                report.responseId(),
                markdown,
                report.sections(),
                sources,
                warnings,
                qualityChecks,
                factSnapshot,
                report.createdAt()
        );
    }

    private DeepCompanyResearchReport reportWithCollectionGapEnrichmentStatus(
            Company company,
            DeepCompanyResearchReport report,
            String status,
            String detail
    ) {
        String markdown = report.reportMarkdown().isBlank() ? sectionsToMarkdown(report.sections()) : report.reportMarkdown();
        List<DeepCompanyResearchReport.QualityCheck> qualityChecks = withCollectionGapEnrichmentCheck(
                report.qualityChecks(),
                status,
                detail
        );
        DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(
                company,
                report.sections(),
                report.sources(),
                qualityChecks,
                markdown
        );
        return new DeepCompanyResearchReport(
                report.companyId(),
                report.companyName(),
                report.city(),
                report.provider(),
                report.model(),
                report.responseId(),
                markdown,
                report.sections(),
                report.sources(),
                report.warnings(),
                qualityChecks,
                factSnapshot,
                report.createdAt()
        );
    }

    private List<DeepCompanyResearchReport.QualityCheck> withCollectionGapEnrichmentCheck(
            List<DeepCompanyResearchReport.QualityCheck> checks,
            String status,
            String detail
    ) {
        List<DeepCompanyResearchReport.QualityCheck> result = new ArrayList<>();
        for (DeepCompanyResearchReport.QualityCheck check : checks) {
            if (!"gap_enrichment".equals(check.key())) {
                result.add(check);
            }
        }
        result.add(new DeepCompanyResearchReport.QualityCheck(
                "gap_enrichment",
                "Автодосбор рекомендаций",
                status,
                detail
        ));
        return result;
    }

    private String companyFacts(Company company) {
        List<String> facts = new ArrayList<>();
        add(facts, "Название: " + company.getTitle());
        add(facts, "Город: " + company.getCity());
        add(facts, "Сайт из CRM: " + company.getUrlSite());
        if (company.getCategoryCompany() != null) {
            add(facts, "Категория: " + company.getCategoryCompany().getCategoryTitle());
        }
        if (company.getSubCategory() != null) {
            add(facts, "Подкатегория: " + company.getSubCategory().getSubCategoryTitle());
        }
        add(facts, "Комментарий CRM: " + company.getCommentsCompany());

        Set<Filial> filials = company.getFilial();
        if (filials != null && !filials.isEmpty()) {
            for (Filial filial : filials) {
                if (filial == null) {
                    continue;
                }
                add(facts, "Филиал CRM: " + String.join(" ",
                        List.of(
                                blankToDash(filial.getTitle()),
                                filial.getCity() == null ? "город: -" : "город: " + blankToDash(filial.getCity().getTitle()),
                                blankToDash(filial.getUrl())
                        )));
            }
        }

        return facts.isEmpty() ? "-" : String.join("\n", facts);
    }

    private String crmPriorityUrls(Company company, ReputationResearchRequest request) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        addUrl(urls, company.getUrlSite(), "Официальный сайт из CRM");
        if (request.publicUrls() != null) {
            request.publicUrls().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(urls::add);
        }

        Set<Filial> filials = company.getFilial();
        if (filials != null) {
            for (Filial filial : filials) {
                if (filial == null || filial.getUrl() == null || filial.getUrl().isBlank()) {
                    continue;
                }
                String title = blankToDash(filial.getTitle());
                String city = filial.getCity() == null ? "-" : blankToDash(filial.getCity().getTitle());
                addUrl(urls, filial.getUrl(), "CRM-филиал: " + title + ", " + city);
            }
        }

        return urls.isEmpty() ? "-" : String.join("\n", urls);
    }

    private void addUrl(LinkedHashSet<String> urls, String url, String label) {
        if (url == null || url.isBlank()) {
            return;
        }
        String cleanUrl = url.trim();
        urls.add(label + " - " + cleanUrl);
    }

    DeepCompanyResearchReport parseReport(Company company, OpenAiResponseResult response) {
        return parseReport(company, response, List.of());
    }

    private DeepCompanyResearchReport parseReport(
            Company company,
            OpenAiResponseResult response,
            List<String> additionalWarnings
    ) {
        try {
            JsonNode root = readReportJson(response.text());
            List<DeepCompanyResearchReport.Section> sections = parseSections(root.path("sections"));
            if (sections.isEmpty()) {
                throw new ReportParseException("JSON отчёта не содержит читаемых разделов sections.");
            }

            List<DeepCompanyResearchReport.Source> sources = parseSources(root.path("sources"));

            List<String> warnings = parseWarnings(root.path("warnings"));

            String markdown = root.path("reportMarkdown").asText("");
            if (markdown.isBlank()) {
                markdown = sectionsToMarkdown(sections);
            }
            List<DeepCompanyResearchReport.QualityCheck> qualityChecks = reportQualityChecks(company, sections, sources, markdown);
            DeepCompanyResearchReport.FactSnapshot factSnapshot = factSnapshot(company, sections, sources, qualityChecks, markdown);
            warnings.addAll(qualityChecks.stream()
                    .filter(this::isActionableQualityCheck)
                    .map(DeepCompanyResearchReport.QualityCheck::detail)
                    .filter(detail -> detail != null && !detail.isBlank())
                    .toList());
            warnings.addAll(additionalWarnings);

            return new DeepCompanyResearchReport(
                    company.getId(),
                    company.getTitle(),
                    company.getCity(),
                    response.provider(),
                    response.model(),
                    response.responseId(),
                    markdown,
                    sections,
                    sources,
                    warnings,
                    qualityChecks,
                    factSnapshot,
                    LocalDateTime.now()
            );
        } catch (ReportParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ReportParseException(activeProviderDisplayName() + " вернул отчёт не в ожидаемом JSON-формате.", exception);
        }
    }

    private DeepCompanyResearchReport repairAndParseReport(
            Company company,
            OpenAiResponseResult response,
            ReportParseException firstException
    ) {
        OpenAiResponseResult repairResponse = openAiResponsesClient.createTextResponse(new AiRequest(
                "repair_reputation_report_json",
                """
                        Ты исправляешь повреждённый JSON отчёта о репутации компании.
                        Верни только валидный JSON-объект без markdown-обёртки и без пояснений.
                        Схема: sections — массив объектов {title, body}, sources — массив объектов {title, url, type, usedFor, confidence, note}, warnings — массив строк.
                        type: official_site, map_card, directory, review_platform, social, legal, aggregator, media или other.
                        confidence: high, medium или low. usedFor — массив коротких тем, которые подтверждает источник.
                        Не добавляй новые факты. Если часть ответа была обычным markdown, разложи её на sections.
                        """,
                "Повреждённый JSON-ответ AI-провайдера:\n" + limitForRepair(response.text()),
                0.0,
                true
        ));

        if (repairResponse.text().isBlank()) {
            String detail = repairResponse.errorMessage().isBlank() ? firstException.getMessage() : repairResponse.errorMessage();
            throw new IllegalStateException("Модель вернула повреждённый JSON отчёта, а повторное форматирование не удалось: " + detail, firstException);
        }

        try {
            OpenAiResponseResult repaired = new OpenAiResponseResult(
                    response.responseId(),
                    repairResponse.text(),
                    response.provider(),
                    response.model(),
                    response.inputTokens(),
                    response.outputTokens()
            );
            return parseReport(
                    company,
                    repaired,
                    List.of("JSON отчёта был восстановлен повторным форматированием AI-провайдера.")
            );
        } catch (ReportParseException secondException) {
            throw new IllegalStateException(
                    "Модель вернула повреждённый JSON отчёта, восстановить структуру не удалось. Запустите отчёт повторно или выберите более лёгкий профиль.",
                    secondException
            );
        }
    }

    private JsonNode readReportJson(String text) throws IOException {
        String json = extractJsonObject(text);
        try {
            return objectMapper.readTree(json);
        } catch (IOException firstException) {
            String repairedJson = repairMissingSectionsArray(json);
            if (!repairedJson.equals(json)) {
                return objectMapper.readTree(repairedJson);
            }
            throw firstException;
        }
    }

    private List<DeepCompanyResearchReport.Section> parseSections(JsonNode sectionsNode) {
        List<DeepCompanyResearchReport.Section> sections = new ArrayList<>();
        if (sectionsNode == null || sectionsNode.isMissingNode() || sectionsNode.isNull()) {
            return sections;
        }
        if (sectionsNode.isArray()) {
            for (JsonNode section : sectionsNode) {
                addSection(sections, section, "");
            }
            return sections;
        }
        if (sectionsNode.isObject() && (sectionsNode.has("title") || sectionsNode.has("body"))) {
            addSection(sections, sectionsNode, "");
            return sections;
        }
        if (sectionsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = sectionsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                addSection(sections, field.getValue(), field.getKey());
            }
        }
        return sections;
    }

    private void addSection(List<DeepCompanyResearchReport.Section> sections, JsonNode section, String fallbackTitle) {
        if (section == null || section.isNull() || section.isMissingNode()) {
            return;
        }
        String title = section.path("title").asText(fallbackTitle == null ? "" : fallbackTitle);
        String body = section.path("body").asText("");
        if (body.isBlank()) {
            body = section.path("markdown").asText("");
        }
        if (body.isBlank()) {
            body = section.path("content").asText(section.isTextual() ? section.asText("") : "");
        }
        if (title.isBlank() && body.isBlank()) {
            return;
        }
        sections.add(new DeepCompanyResearchReport.Section(title, body));
    }

    private List<DeepCompanyResearchReport.Source> parseSources(JsonNode sourcesNode) {
        List<DeepCompanyResearchReport.Source> sources = new ArrayList<>();
        if (sourcesNode == null || sourcesNode.isMissingNode() || sourcesNode.isNull()) {
            return sources;
        }
        if (sourcesNode.isArray()) {
            for (JsonNode source : sourcesNode) {
                addSource(sources, source, "");
            }
            return sources;
        }
        if (sourcesNode.isObject() && (sourcesNode.has("title") || sourcesNode.has("url") || sourcesNode.has("note") || sourcesNode.has("type"))) {
            addSource(sources, sourcesNode, "");
            return sources;
        }
        if (sourcesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = sourcesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                addSource(sources, field.getValue(), field.getKey());
            }
        }
        return sources;
    }

    private void addSource(List<DeepCompanyResearchReport.Source> sources, JsonNode source, String fallbackTitle) {
        if (source == null || source.isNull() || source.isMissingNode()) {
            return;
        }
        String title = source.path("title").asText(fallbackTitle == null ? "" : fallbackTitle);
        String url = source.path("url").asText(source.isTextual() ? source.asText("") : "");
        String note = source.path("note").asText("");
        if (note.isBlank()) {
            note = source.path("description").asText("");
        }
        if (note.isBlank()) {
            note = source.path("excerpt").asText("");
        }
        String rawType = source.path("type").asText("");
        String rawConfidence = source.path("confidence").asText("");
        String type = normalizeSourceType(rawType, title, url, note);
        List<String> usedFor = parseUsedFor(source.path("usedFor"));
        if (usedFor.isEmpty()) {
            usedFor = parseUsedFor(source.path("used_for"));
        }
        if (usedFor.isEmpty()) {
            usedFor = inferUsedFor(title + " " + note);
        }
        String confidence = normalizeSourceConfidence(
                rawConfidence,
                type,
                title,
                url,
                note
        );
        if (title.isBlank()
                && url.isBlank()
                && note.isBlank()
                && rawType.isBlank()
                && rawConfidence.isBlank()
                && usedFor.isEmpty()) {
            return;
        }
        sources.add(new DeepCompanyResearchReport.Source(title, url, note, type, usedFor, confidence));
    }

    private String normalizeSourceType(String rawType, String title, String url, String note) {
        String normalizedType = normalizeLookup(rawType).replace('-', '_');
        if (List.of(
                "official_site",
                "map_card",
                "directory",
                "review_platform",
                "social",
                "legal",
                "aggregator",
                "media",
                "other"
        ).contains(normalizedType)) {
            return normalizedType;
        }

        String combined = normalizeLookup(String.join(" ", title, url, note));
        if (containsAny(combined, "инн", "огрн", "егрюл", "rusprofile", "checko", "зачестныйбизнес", "nalog.ru")) {
            return "legal";
        }
        if (containsAny(combined, "2gis", "2гис", "yandex maps", "яндекс карт", "google maps", "google.com/maps", "maps.google")) {
            return "map_card";
        }
        if (containsAny(combined, "vk.com", "t.me", "telegram", "instagram", "facebook", "ok.ru", "youtube", "соцсет")) {
            return "social";
        }
        if (containsAny(combined, "otzovik", "irecommend", "flamp", "yell", "отзыв", "отзовик")) {
            return "review_platform";
        }
        if (containsAny(combined, "zoon", "orgpage", "sprav", "каталог", "справочник", "yellow", "2find")) {
            return "directory";
        }
        if (containsAny(combined, "aggregator", "агрегатор", "avito", "profi.ru", "yandex services", "услуги яндекс")) {
            return "aggregator";
        }
        if (containsAny(combined, "новости", "сми", "статья", "интервью", "media", "vc.ru", "rb.ru")) {
            return "media";
        }
        if (containsAny(combined, "официаль", "официальный сайт", "сайт компании", "сайт", "главная", "прайс", "контакты")) {
            return "official_site";
        }
        return "other";
    }

    private List<String> parseUsedFor(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                addUsedFor(values, item.asText(""));
            }
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isBoolean()) {
                    if (field.getValue().asBoolean(false)) {
                        addUsedFor(values, field.getKey());
                    }
                } else {
                    addUsedFor(values, field.getValue().asText(field.getKey()));
                }
            }
        } else {
            for (String part : node.asText("").split("[,;\\n]")) {
                addUsedFor(values, part);
            }
        }
        return values.stream().limit(8).toList();
    }

    private List<String> inferUsedFor(String note) {
        String normalized = normalizeLookup(note);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (containsAny(normalized, "услуг", "товар", "меню", "пакет", "тариф", "абонемент", "ассортимент")) {
            values.add("услуги");
        }
        if (containsAny(normalized, "цен", "стоим", "прайс", "тариф", "чек")) {
            values.add("цены");
        }
        if (containsAny(normalized, "адрес", "филиал", "город", "контакт", "телефон")) {
            values.add("контакты");
        }
        if (containsAny(normalized, "режим", "время работы", "часы работы", "расписан")) {
            values.add("режим");
        }
        if (containsAny(normalized, "парков", "wi-fi", "wifi", "вай-фай", "туалет", "гардероб", "детск", "вход", "этаж", "доступност", "зона ожид")) {
            values.add("удобства");
        }
        if (containsAny(normalized, "отзыв", "рейтинг", "жалоб", "хвал")) {
            values.add("отзывы");
        }
        if (containsAny(normalized, "сотруд", "специалист", "мастер", "администратор", "врач", "тренер", "ведущ", "аниматор", "преподавател")) {
            values.add("сотрудники");
        }
        if (containsAny(normalized, "инн", "огрн", "лиценз", "сертифик", "юр", "егрюл")) {
            values.add("юридические данные");
        }
        if (containsAny(normalized, "фото", "фасад", "вывеск", "интерьер", "экстерьер")) {
            values.add("фото");
        }
        if (containsAny(normalized, "достав", "самовывоз", "выезд", "логист")) {
            values.add("логистика");
        }
        if (values.isEmpty()) {
            values.add("факты");
        }
        return values.stream().limit(8).toList();
    }

    private void addUsedFor(LinkedHashSet<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String clean = value.trim().replaceAll("\\s+", " ");
        if (!clean.isBlank()) {
            values.add(clean);
        }
    }

    private String normalizeSourceConfidence(String rawConfidence, String type, String title, String url, String note) {
        String normalized = normalizeLookup(rawConfidence);
        if (normalized.equals("high") || normalized.startsWith("выс")) {
            return "high";
        }
        if (normalized.equals("low") || normalized.startsWith("низ")) {
            return "low";
        }
        if (normalized.equals("medium") || normalized.startsWith("сред")) {
            return "medium";
        }

        String combined = normalizeLookup(String.join(" ", title, url, note));
        if (containsAny(combined, "неувер", "слабый сигнал", "сниппет", "стар", "архив", "неподтверж", "единичн")) {
            return "low";
        }
        if (containsAny(combined, "2+ независ", "два независ", "несколько независ", "подтверждено несколькими")) {
            return "high";
        }
        if ("official_site".equals(type) || "legal".equals(type)) {
            return "high";
        }
        if ("map_card".equals(type) || "directory".equals(type) || "aggregator".equals(type) || "social".equals(type)) {
            return "medium";
        }
        return "low";
    }

    private List<String> parseWarnings(JsonNode warningsNode) {
        List<String> warnings = new ArrayList<>();
        if (warningsNode == null || warningsNode.isMissingNode() || warningsNode.isNull()) {
            return warnings;
        }
        if (warningsNode.isArray()) {
            for (JsonNode warning : warningsNode) {
                addWarning(warnings, warning.asText(""));
            }
            return warnings;
        }
        if (warningsNode.isTextual()) {
            addWarning(warnings, warningsNode.asText(""));
        } else if (warningsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = warningsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                addWarning(warnings, field.getValue().asText(""));
            }
        }
        return warnings;
    }

    private List<DeepCompanyResearchReport.QualityCheck> reportQualityChecks(
            Company company,
            List<DeepCompanyResearchReport.Section> sections,
            List<DeepCompanyResearchReport.Source> sources,
            String markdown
    ) {
        List<DeepCompanyResearchReport.QualityCheck> checks = new ArrayList<>();
        String safeMarkdown = markdown == null ? "" : markdown;
        if (sections.size() < 4) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "sections",
                    "Полнота разделов",
                    "warn",
                    "В отчёте мало разделов: проверьте полноту результата или запустите профиль «Баланс/Максимум»."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "sections",
                    "Полнота разделов",
                    "pass",
                    "Разделов достаточно для рабочего отчёта: " + sections.size() + "."
            ));
        }

        if (sources.isEmpty()) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "sources",
                    "Источники",
                    "fail",
                    "AI-провайдер не вернул список источников. Перед использованием отчёта проверьте факты вручную."
            ));
        } else if (sources.size() < 3) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "sources",
                    "Источники",
                    "warn",
                    "Источников мало: " + sources.size() + ". Для публикации лучше перепроверить ключевые факты."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "sources",
                    "Источники",
                    "pass",
                    "Есть несколько источников: " + sources.size() + "."
            ));
        }

        long suspiciousSources = sources.stream()
                .map(DeepCompanyResearchReport.Source::url)
                .filter(url -> !url.isBlank() && !isLikelyPublicUrl(url))
                .count();
        if (suspiciousSources > 0) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "public_sources",
                    "Публичность ссылок",
                    "warn",
                    "В источниках есть непубличные или подозрительные URL. CRM/локальные ссылки лучше не использовать как доказательства."
            ));
        } else if (!sources.isEmpty()) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "public_sources",
                    "Публичность ссылок",
                    "pass",
                    "Ссылки выглядят публичными."
            ));
        }

        String city = company.getCity();
        if (city != null && !city.isBlank() && !safeMarkdown.toLowerCase().contains(city.trim().toLowerCase())) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "city",
                    "Город CRM",
                    "warn",
                    "В тексте отчёта не найден город из CRM: " + city.trim() + ". Проверьте, что модель не перепутала локацию."
            ));
        } else if (city != null && !city.isBlank()) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "city",
                    "Город CRM",
                    "pass",
                    "Город из CRM найден в тексте отчёта: " + city.trim() + "."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "city",
                    "Город CRM",
                    "info",
                    "В карточке компании нет города, проверка локации пропущена."
                ));
        }

        if (hasDuplicateSectionTitles(sections) || hasRepeatedLongParagraphs(safeMarkdown)) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "duplicates",
                    "Повторы в отчёте",
                    "warn",
                    "В отчёте есть повторяющиеся заголовки или длинные повторяющиеся фрагменты. Перед использованием карточки лучше убрать дубли."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "duplicates",
                    "Повторы в отчёте",
                    "pass",
                    "Явных повторов заголовков и длинных блоков не найдено."
            ));
        }

        boolean hasPriorityUrls = companyHasPriorityUrls(company);
        boolean hasMapOrDirectory = hasMapOrDirectorySignal(sources, safeMarkdown);
        if (hasPriorityUrls && !hasMapOrDirectory) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "maps_directories",
                    "Карты и справочники",
                    "warn",
                    "В CRM есть сайт/филиальные URL, но в отчёте не видно явной сверки карт или справочников. Проверьте 2ГИС/Яндекс/Google карточки вручную."
            ));
        } else if (hasMapOrDirectory) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "maps_directories",
                    "Карты и справочники",
                    "pass",
                    "В отчёте или источниках есть признаки карт, карточек или справочников."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "maps_directories",
                    "Карты и справочники",
                    "info",
                    "Явных карт или справочников во входных данных и sources не найдено."
            ));
        }

        boolean hasCardDetails = containsAny(
                safeMarkdown,
                "адрес",
                "филиал",
                "режим",
                "этаж",
                "вход",
                "вывес",
                "ориентир",
                "как добраться",
                "логист"
        );
        if (!hasCardDetails) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "card_details",
                    "Детали карточки",
                    "warn",
                    "В отчёте не видно адресов, филиалов, режима, входа, этажа или ориентиров. Для карточки компании этих деталей может не хватить."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "card_details",
                    "Детали карточки",
                    "pass",
                    "В отчёте есть адресные или операционные детали для будущей карточки."
            ));
        }

        boolean hasOfflineSignals = hasPriorityUrls || containsAny(safeMarkdown, "адрес", "филиал", "офис", "карта", "2гис", "посетител", "клиент");
        boolean hasAmenities = containsAny(
                safeMarkdown,
                "парков",
                "доступност",
                "зона ожид",
                "ожидания",
                "wi-fi",
                "wifi",
                "вай-фай",
                "вход",
                "этаж",
                "туалет",
                "гардероб",
                "детская зон",
                "детск",
                "оплат",
                "достав",
                "самовывоз",
                "выезд",
                "онлайн-зап",
                "запись"
        );
        if (hasOfflineSignals && !hasAmenities) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "amenities",
                    "Парковка и удобства",
                    "warn",
                    "Компания выглядит как офлайн-точка, но не разобраны парковка, вход, этаж, доступность, Wi-Fi, туалет, гардероб, детская зона, зона ожидания, оплата или похожие удобства."
            ));
        } else if (hasAmenities) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "amenities",
                    "Парковка и удобства",
                    "pass",
                    "В отчёте есть детали про парковку, вход, этаж, доступность, Wi-Fi, туалет, гардероб, детскую зону, запись, оплату или другие удобства."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "amenities",
                    "Парковка и удобства",
                    "info",
                    "Офлайн-удобства не выглядят ключевыми по найденным данным."
            ));
        }

        boolean hasReadinessNotes = containsAny(
                safeMarkdown,
                "что ещё собирать",
                "что еще собирать",
                "уточнить",
                "перед публикац",
                "карточк",
                "дозвон",
                "проверить"
        );
        if (!hasReadinessNotes) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "card_readiness",
                    "Готовность карточки",
                    "warn",
                    "В отчёте нет явного списка, что проверить перед публикацией карточки компании."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "card_readiness",
                    "Готовность карточки",
                    "pass",
                    "В отчёте есть уточнения или список проверки перед публикацией карточки."
            ));
        }

        boolean hasRiskSection = hasSectionWith(sections, "риск", "сомнен", "противореч", "уточн");
        if (!hasRiskSection) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "risks",
                    "Риски и сомнения",
                    "warn",
                    "Модель не выделила отдельные риски или сомнения. Проверьте спорные факты перед публикацией."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "risks",
                    "Риски и сомнения",
                    "pass",
                    "В отчёте есть отдельные риски, сомнения или уточнения."
            ));
        }

        boolean hasOfferSection = hasSectionWith(sections, "цен", "прайс", "услуг", "товар", "предлож", "стоимост", "пакет");
        if (!hasOfferSection) {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "offers",
                    "Услуги и цены",
                    "warn",
                    "В отчёте не выделены услуги, товары или цены. Для AI-пакета может не хватить коммерческих деталей."
            ));
        } else {
            checks.add(new DeepCompanyResearchReport.QualityCheck(
                    "offers",
                    "Услуги и цены",
                    "pass",
                    "В отчёте есть блоки про услуги, товары, цены или предложения."
            ));
        }

        return checks;
    }

    private DeepCompanyResearchReport.FactSnapshot factSnapshot(
            Company company,
            List<DeepCompanyResearchReport.Section> sections,
            List<DeepCompanyResearchReport.Source> sources,
            List<DeepCompanyResearchReport.QualityCheck> qualityChecks,
            String markdown
    ) {
        List<DeepCompanyResearchReport.FactItem> confirmed = new ArrayList<>();
        List<DeepCompanyResearchReport.FactItem> uncertain = new ArrayList<>();

        addFact(confirmed, "Компания", company.getTitle(), "CRM-карточка", "high");

        String city = company.getCity();
        if (city != null && !city.isBlank()) {
            boolean cityInReport = markdown.toLowerCase().contains(city.trim().toLowerCase());
            addFact(
                    cityInReport ? confirmed : uncertain,
                    "Город",
                    city,
                    cityInReport ? "CRM и текст отчёта" : "CRM, но город не найден в тексте отчёта",
                    cityInReport ? "high" : "medium"
            );
        }

        if (company.getCategoryCompany() != null) {
            addFact(confirmed, "Категория", company.getCategoryCompany().getCategoryTitle(), "CRM-карточка", "high");
        }
        if (company.getSubCategory() != null) {
            addFact(confirmed, "Подкатегория", company.getSubCategory().getSubCategoryTitle(), "CRM-карточка", "high");
        }
        if (company.getUrlSite() != null && !company.getUrlSite().isBlank()) {
            addFact(
                    isLikelyPublicUrl(company.getUrlSite()) ? confirmed : uncertain,
                    "Сайт из CRM",
                    company.getUrlSite(),
                    isLikelyPublicUrl(company.getUrlSite()) ? "Публичная ссылка из CRM" : "CRM-ссылка требует проверки",
                    isLikelyPublicUrl(company.getUrlSite()) ? "high" : "medium"
            );
        }

        if (!sources.isEmpty()) {
            addFact(confirmed, "Источники отчёта", String.valueOf(sources.size()), "Список источников AI-провайдера", "medium");
        }
        if (hasSectionWith(sections, "цен", "прайс", "услуг", "товар", "предлож", "стоимост", "пакет")) {
            addFact(confirmed, "Коммерческие детали", "упомянуты", "Разделы глубокого отчёта", "medium");
        }

        qualityChecks.stream()
                .filter(this::isActionableQualityCheck)
                .forEach(check -> addFact(
                        uncertain,
                        check.label(),
                        check.detail(),
                        "Автопроверка качества отчёта",
                        "fail".equals(check.status()) ? "low" : "medium"
                ));

        List<DeepCompanyResearchReport.SourceReview> sourceReviews = sources.stream()
                .map(this::sourceReview)
                .toList();

        return new DeepCompanyResearchReport.FactSnapshot(confirmed, uncertain, sourceReviews);
    }

    private DeepCompanyResearchReport.SourceReview sourceReview(DeepCompanyResearchReport.Source source) {
        String meta = sourceMetaText(source);
        if (source.url().isBlank()) {
            return new DeepCompanyResearchReport.SourceReview(
                    source.title(),
                    source.url(),
                    "needs_review",
                    "У источника нет URL. " + meta
            );
        }
        boolean publicUrl = isLikelyPublicUrl(source.url());
        boolean lowConfidence = "low".equals(source.confidence());
        String reason = publicUrl
                ? (lowConfidence
                ? "Публичный URL, но источник отмечен как низкая уверенность. " + meta
                : "Публичный URL. " + meta)
                : "Ссылка похожа на CRM, локальную или служебную. " + meta;
        return new DeepCompanyResearchReport.SourceReview(
                source.title(),
                source.url(),
                publicUrl && !lowConfidence ? "trusted_public" : "needs_review",
                reason
        );
    }

    private int selectedSectionIndex(List<DeepCompanyResearchReport.Section> sections, ReputationResearchRequest request) {
        Integer requestedIndex = request == null ? null : request.sectionIndex();
        if (requestedIndex != null && requestedIndex >= 0 && requestedIndex < sections.size()) {
            return requestedIndex;
        }

        String requestedTitle = request == null ? "" : request.sectionTitle();
        String normalizedTitle = normalizeLookup(requestedTitle);
        if (!normalizedTitle.isBlank()) {
            for (int index = 0; index < sections.size(); index++) {
                if (normalizeLookup(sections.get(index).title()).equals(normalizedTitle)) {
                    return index;
                }
            }
            for (int index = 0; index < sections.size(); index++) {
                String sectionTitle = normalizeLookup(sections.get(index).title());
                if (sectionTitle.contains(normalizedTitle) || normalizedTitle.contains(sectionTitle)) {
                    return index;
                }
            }
        }

        throw new IllegalStateException("Не выбран раздел для пересборки.");
    }

    private List<DeepCompanyResearchReport.Source> mergeSources(
            List<DeepCompanyResearchReport.Source> primary,
            List<DeepCompanyResearchReport.Source> fallback
    ) {
        List<DeepCompanyResearchReport.Source> merged = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        addSources(merged, seen, primary);
        addSources(merged, seen, fallback);
        return merged.stream().limit(40).toList();
    }

    private void addSources(
            List<DeepCompanyResearchReport.Source> result,
            LinkedHashSet<String> seen,
            List<DeepCompanyResearchReport.Source> sources
    ) {
        for (DeepCompanyResearchReport.Source source : sources) {
            String key = source.url().isBlank()
                    ? (source.title() + "|" + source.note()).toLowerCase()
                    : source.url().trim().toLowerCase();
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            result.add(source);
        }
    }

    private void ensureResponseText(OpenAiResponseResult response, String fallback) {
        if (!response.text().isBlank()) {
            return;
        }
        String errorMessage = response.errorMessage();
        throw new IllegalStateException(errorMessage.isBlank() ? fallback : errorMessage);
    }

    private String sourcesToPrompt(List<DeepCompanyResearchReport.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return "-";
        }
        return sources.stream()
                .map(source -> "- %s | %s | %s | %s".formatted(
                        source.title(),
                        source.url(),
                        sourceMetaText(source),
                        source.note()
                ))
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");
    }

    private String sourceMetaText(DeepCompanyResearchReport.Source source) {
        String usedFor = source.usedFor().isEmpty() ? "-" : String.join(", ", source.usedFor());
        return "type=%s; confidence=%s; usedFor=%s".formatted(source.type(), source.confidence(), usedFor);
    }

    private String factsToPrompt(List<DeepCompanyResearchReport.FactItem> facts) {
        if (facts == null || facts.isEmpty()) {
            return "-";
        }
        return facts.stream()
                .map(fact -> "- %s: %s | %s | confidence=%s".formatted(
                        fact.label(),
                        fact.value(),
                        fact.evidence(),
                        fact.confidence()
                ))
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");
    }

    private String sectionsToPrompt(
            List<DeepCompanyResearchReport.Section> sections,
            String reportMarkdown,
            int maxLength
    ) {
        String text = sections == null || sections.isEmpty()
                ? reportMarkdown
                : sectionsToMarkdown(sections);
        return limitForPrompt(text, maxLength);
    }

    private void addFact(
            List<DeepCompanyResearchReport.FactItem> facts,
            String label,
            String value,
            String evidence,
            String confidence
    ) {
        if (value == null || value.isBlank() || value.endsWith("null")) {
            return;
        }
        facts.add(new DeepCompanyResearchReport.FactItem(label, value, evidence, confidence));
    }

    private boolean hasSectionWith(List<DeepCompanyResearchReport.Section> sections, String... needles) {
        return sections.stream()
                .anyMatch(section -> containsAny(section.title() + " " + section.body(), needles));
    }

    private boolean hasDuplicateSectionTitles(List<DeepCompanyResearchReport.Section> sections) {
        Set<String> seen = new LinkedHashSet<>();
        for (DeepCompanyResearchReport.Section section : sections) {
            String title = normalizeLongText(section.title());
            if (!title.isBlank() && !seen.add(title)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRepeatedLongParagraphs(String markdown) {
        Set<String> seen = new LinkedHashSet<>();
        String[] blocks = (markdown == null ? "" : markdown).split("\\R\\s*\\R");
        for (String block : blocks) {
            String normalized = normalizeLongText(block);
            if (normalized.length() < 140 || normalized.startsWith("|")) {
                continue;
            }
            if (!seen.add(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean companyHasPriorityUrls(Company company) {
        if (company.getUrlSite() != null && !company.getUrlSite().isBlank()) {
            return true;
        }
        Set<Filial> filials = company.getFilial();
        if (filials == null || filials.isEmpty()) {
            return false;
        }
        return filials.stream()
                .anyMatch(filial -> filial != null && filial.getUrl() != null && !filial.getUrl().isBlank());
    }

    private boolean hasMapOrDirectorySignal(List<DeepCompanyResearchReport.Source> sources, String markdown) {
        StringBuilder combined = new StringBuilder(markdown == null ? "" : markdown);
        for (DeepCompanyResearchReport.Source source : sources) {
            combined.append(' ')
                    .append(source.title()).append(' ')
                    .append(source.url()).append(' ')
                    .append(source.type()).append(' ')
                    .append(String.join(" ", source.usedFor())).append(' ')
                    .append(source.note());
        }
        return containsAny(
                combined.toString(),
                "2gis",
                "2гис",
                "yandex",
                "яндекс",
                "google maps",
                "google.com/maps",
                "maps.google",
                "карты",
                "справочник",
                "каталог",
                "zoon",
                "flamp"
        );
    }

    private boolean isActionableQualityCheck(DeepCompanyResearchReport.QualityCheck check) {
        return "warn".equals(check.status()) || "fail".equals(check.status());
    }

    private void addWarning(List<String> warnings, String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning.trim());
        }
    }

    private boolean isLikelyPublicUrl(String url) {
        String normalized = url.trim().toLowerCase();
        return (normalized.startsWith("https://") || normalized.startsWith("http://"))
                && !normalized.contains("localhost")
                && !normalized.contains("127.0.0.1")
                && !normalized.contains("://crm.")
                && !normalized.contains("/admin/");
    }

    private boolean containsAny(String value, String... needles) {
        String normalized = value == null ? "" : value.toLowerCase();
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeLookup(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private String normalizeLongText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private String repairMissingSectionsArray(String json) {
        int keyStart = json.indexOf("\"sections\"");
        if (keyStart < 0) {
            return json;
        }
        int colon = json.indexOf(':', keyStart);
        if (colon < 0) {
            return json;
        }
        int valueStart = skipWhitespace(json, colon + 1);
        if (valueStart >= json.length() || json.charAt(valueStart) == '[' || json.charAt(valueStart) != '{') {
            return json;
        }

        int current = valueStart;
        int valueEnd = -1;
        while (current < json.length() && json.charAt(current) == '{') {
            int objectEnd = findMatchingObjectEnd(json, current);
            if (objectEnd < 0) {
                return json;
            }
            int afterObject = skipWhitespace(json, objectEnd + 1);
            if (afterObject >= json.length() || json.charAt(afterObject) == '}') {
                valueEnd = objectEnd + 1;
                break;
            }
            if (json.charAt(afterObject) != ',') {
                return json;
            }
            int afterComma = skipWhitespace(json, afterObject + 1);
            if (afterComma < json.length() && json.charAt(afterComma) == '{') {
                current = afterComma;
                continue;
            }
            valueEnd = objectEnd + 1;
            break;
        }

        if (valueEnd < 0) {
            return json;
        }
        return json.substring(0, valueStart) + "[" + json.substring(valueStart, valueEnd) + "]" + json.substring(valueEnd);
    }

    private int findMatchingObjectEnd(String value, int objectStart) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = objectStart; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private int skipWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private String extractJsonObject(String text) {
        String clean = text == null ? "" : text.trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return clean.substring(start, end + 1);
        }
        return clean;
    }

    private String sectionsToMarkdown(List<DeepCompanyResearchReport.Section> sections) {
        StringBuilder result = new StringBuilder();
        for (DeepCompanyResearchReport.Section section : sections) {
            if (!section.title().isBlank()) {
                result.append("## ").append(section.title()).append("\n\n");
            }
            if (!section.body().isBlank()) {
                result.append(section.body()).append("\n\n");
            }
        }
        return result.toString().trim();
    }

    private void add(List<String> facts, String value) {
        if (value != null && !value.isBlank() && !value.endsWith("null")) {
            facts.add(value.trim());
        }
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String listToText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return String.join("\n", values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList());
    }

    private String limitForRepair(String value) {
        if (value == null || value.length() <= 60_000) {
            return value == null ? "" : value;
        }
        return value.substring(0, 60_000);
    }

    private String limitForPrompt(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n\n[обрезано для компактности]";
    }

    private static class ReportParseException extends RuntimeException {
        ReportParseException(String message) {
            super(message);
        }

        ReportParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
