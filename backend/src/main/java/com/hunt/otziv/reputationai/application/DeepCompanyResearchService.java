package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
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
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeepCompanyResearchService {

    private final CompanyRepository companyRepository;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final ObjectMapper objectMapper;
    private final ReputationAiPromptService promptService;

    public DeepCompanyResearchReport createReport(Long companyId, ReputationResearchRequest request) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException("OpenAI не настроен: укажите OPENAI_API_KEY и REPUTATION_AI_PROVIDER=openai.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));

        OpenAiResponseResult response = openAiResponsesClient.createResearchReportResponse(
                instructions(),
                researchInput(company, safeRequest),
                safeRequest.deepResearchProfile()
        );
        if (response.text().isBlank()) {
            String errorMessage = response.errorMessage();
            throw new IllegalStateException(errorMessage.isBlank()
                    ? "OpenAI не вернул текст глубокого исследования."
                    : errorMessage);
        }

        try {
            return parseReport(company, response);
        } catch (ReportParseException exception) {
            return repairAndParseReport(company, response, exception);
        }
    }

    public DeepCompanyResearchReport refreshSources(
            Long companyId,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException("OpenAI не настроен: укажите OPENAI_API_KEY и REPUTATION_AI_PROVIDER=openai.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для обновления источников.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null)
                : request;
        Company company = companyRepository.findByIdForReputationAi(companyId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        String.format("Компания '%d' не найден", companyId)
                ));

        OpenAiResponseResult response = openAiResponsesClient.createSourceRefreshResponse(
                sourceRefreshInstructions(),
                sourceRefreshInput(company, safeRequest, baseReport),
                safeRequest.deepResearchProfile()
        );
        ensureResponseText(response, "OpenAI не вернул список обновлённых источников.");

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
                    "openai",
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
            throw new IllegalStateException("OpenAI вернул источники не в ожидаемом JSON-формате.", exception);
        }
    }

    public DeepCompanyResearchReport rebuildText(
            Long companyId,
            ReputationResearchRequest request,
            DeepCompanyResearchReport baseReport
    ) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException("OpenAI не настроен: укажите OPENAI_API_KEY и REPUTATION_AI_PROVIDER=openai.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для пересборки текста.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null)
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
        ensureResponseText(response, "OpenAI не вернул пересобранный текст отчёта.");

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
            throw new IllegalStateException("OpenAI не настроен: укажите OPENAI_API_KEY и REPUTATION_AI_PROVIDER=openai.");
        }
        if (baseReport == null) {
            throw new IllegalStateException("Нет базового отчёта для пересборки раздела.");
        }
        if (baseReport.sections().isEmpty()) {
            throw new IllegalStateException("В базовом отчёте нет разделов для точечной пересборки.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null, null, null, null, null)
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
        ensureResponseText(response, "OpenAI не вернул пересобранный раздел отчёта.");

        try {
            JsonNode root = readReportJson(response.text());
            List<DeepCompanyResearchReport.Section> parsedSections = parseSections(root.path("section"));
            if (parsedSections.isEmpty()) {
                parsedSections = parseSections(root.path("sections"));
            }
            if (parsedSections.isEmpty()) {
                throw new IllegalStateException("OpenAI не вернул section с title/body.");
            }

            DeepCompanyResearchReport.Section parsedSection = parsedSections.get(0);
            DeepCompanyResearchReport.Section rewrittenSection = new DeepCompanyResearchReport.Section(
                    parsedSection.title().isBlank() ? originalSection.title() : parsedSection.title(),
                    parsedSection.body()
            );
            if (rewrittenSection.body().isBlank()) {
                throw new IllegalStateException("OpenAI вернул пустой текст раздела.");
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
                    "openai",
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
            throw new IllegalStateException("OpenAI вернул раздел не в ожидаемом JSON-формате.", exception);
        }
    }

    private String instructions() {
        return promptService.content(ReputationAiPromptKeys.DEEP_REPORT_INSTRUCTIONS);
    }

    private String researchInput(Company company, ReputationResearchRequest request) {
        return promptService.content(ReputationAiPromptKeys.DEEP_REPORT_INPUT)
                .replace("{{companyFacts}}", companyFacts(company))
                .replace("{{manualDescription}}", blankToDash(request.manualDescription()))
                .replace("{{productsOrServices}}", listToText(request.productsOrServices()))
                .replace("{{publicUrls}}", listToText(request.publicUrls()))
                .replace("{{crmPriorityUrls}}", crmPriorityUrls(company, request));
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
            DeepCompanyResearchReport baseReport
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
                """.formatted(
                companyFacts(company),
                blankToDash(request.manualDescription()),
                listToText(request.publicUrls()),
                crmPriorityUrls(company, request),
                sourcesToPrompt(baseReport.sources()),
                sectionsToPrompt(baseReport.sections(), baseReport.reportMarkdown(), 14000)
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
        if (request.publicUrls() != null) {
            request.publicUrls().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(urls::add);
        }
        addUrl(urls, company.getUrlSite(), "Официальный сайт из CRM");

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
                    "openai",
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
            throw new ReportParseException("OpenAI вернул отчёт не в ожидаемом JSON-формате.", exception);
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
                        Схема: sections — массив объектов {title, body}, sources — массив {title, url, note}, warnings — массив строк.
                        Не добавляй новые факты. Если часть ответа была обычным markdown, разложи её на sections.
                        """,
                "Повреждённый ответ OpenAI:\n" + limitForRepair(response.text()),
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
                    response.model(),
                    response.inputTokens(),
                    response.outputTokens()
            );
            return parseReport(
                    company,
                    repaired,
                    List.of("JSON отчёта был восстановлен повторным форматированием OpenAI.")
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
        if (sourcesNode.isObject() && (sourcesNode.has("title") || sourcesNode.has("url") || sourcesNode.has("note"))) {
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
        if (title.isBlank() && url.isBlank() && note.isBlank()) {
            return;
        }
        sources.add(new DeepCompanyResearchReport.Source(title, url, note));
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
                    "OpenAI не вернул список источников. Перед использованием отчёта проверьте факты вручную."
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
        if (city != null && !city.isBlank() && !markdown.toLowerCase().contains(city.trim().toLowerCase())) {
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
            addFact(confirmed, "Источники отчёта", String.valueOf(sources.size()), "Список источников OpenAI", "medium");
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
        if (source.url().isBlank()) {
            return new DeepCompanyResearchReport.SourceReview(
                    source.title(),
                    source.url(),
                    "needs_review",
                    "У источника нет URL."
            );
        }
        boolean publicUrl = isLikelyPublicUrl(source.url());
        return new DeepCompanyResearchReport.SourceReview(
                source.title(),
                source.url(),
                publicUrl ? "trusted_public" : "needs_review",
                publicUrl ? "Публичный URL, можно открыть вручную." : "Ссылка похожа на CRM, локальную или служебную."
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
                .map(source -> "- %s | %s | %s".formatted(source.title(), source.url(), source.note()))
                .toList()
                .stream()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("-");
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
