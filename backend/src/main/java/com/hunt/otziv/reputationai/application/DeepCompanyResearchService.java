package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponseResult;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponsesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeepCompanyResearchService {

    private final CompanyRepository companyRepository;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final ObjectMapper objectMapper;

    @Value("classpath:reputation-ai/deep-company-research-instructions.md")
    private Resource instructionsPrompt;

    @Value("classpath:reputation-ai/deep-company-research-input.md")
    private Resource inputPrompt;

    public DeepCompanyResearchReport createReport(Long companyId, ReputationResearchRequest request) {
        if (!openAiResponsesClient.isAvailable()) {
            throw new IllegalStateException("OpenAI не настроен: укажите OPENAI_API_KEY и REPUTATION_AI_PROVIDER=openai.");
        }

        ReputationResearchRequest safeRequest = request == null
                ? new ReputationResearchRequest(null, null, List.of(), List.of(), true, null)
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

        return parseReport(company, response);
    }

    private String instructions() {
        return readPrompt(instructionsPrompt);
    }

    private String researchInput(Company company, ReputationResearchRequest request) {
        return readPrompt(inputPrompt)
                .replace("{{companyFacts}}", companyFacts(company))
                .replace("{{manualDescription}}", blankToDash(request.manualDescription()))
                .replace("{{productsOrServices}}", listToText(request.productsOrServices()))
                .replace("{{publicUrls}}", listToText(request.publicUrls()));
    }

    private String readPrompt(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать prompt глубокого отчета", exception);
        }
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
                                blankToDash(filial.getUrl())
                        )));
            }
        }

        return facts.isEmpty() ? "-" : String.join("\n", facts);
    }

    private DeepCompanyResearchReport parseReport(Company company, OpenAiResponseResult response) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(response.text()));
            List<DeepCompanyResearchReport.Section> sections = new ArrayList<>();
            for (JsonNode section : root.path("sections")) {
                sections.add(new DeepCompanyResearchReport.Section(
                        section.path("title").asText(""),
                        section.path("body").asText("")
                ));
            }

            List<DeepCompanyResearchReport.Source> sources = new ArrayList<>();
            for (JsonNode source : root.path("sources")) {
                sources.add(new DeepCompanyResearchReport.Source(
                        source.path("title").asText(""),
                        source.path("url").asText(""),
                        source.path("note").asText("")
                ));
            }

            List<String> warnings = new ArrayList<>();
            for (JsonNode warning : root.path("warnings")) {
                if (!warning.asText("").isBlank()) {
                    warnings.add(warning.asText());
                }
            }

            String markdown = root.path("reportMarkdown").asText("");
            if (markdown.isBlank()) {
                markdown = sectionsToMarkdown(sections);
            }

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
                    LocalDateTime.now()
            );
        } catch (Exception exception) {
            return new DeepCompanyResearchReport(
                    company.getId(),
                    company.getTitle(),
                    company.getCity(),
                    "openai",
                    response.model(),
                    response.responseId(),
                    response.text(),
                    List.of(),
                    List.of(),
                    List.of("OpenAI вернул отчет не в ожидаемом JSON-формате, показан сырой текст."),
                    LocalDateTime.now()
            );
        }
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
}
