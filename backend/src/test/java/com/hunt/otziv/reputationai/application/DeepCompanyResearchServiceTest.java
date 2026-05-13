package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeepCompanyResearchServiceTest {

    private final DeepCompanyResearchService service = new DeepCompanyResearchService(null, null, new ObjectMapper(), null);

    @Test
    void parsesReportWhenModelOmitsSectionsArrayBrackets() {
        Company company = Company.builder()
                .id(1L)
                .title("IQuest")
                .city("Ангарск")
                .build();
        String text = """
                {
                  "sources": [
                    {"title": "Сайт", "url": "https://example.ru", "note": "Факты"}
                  ],
                  "warnings": [],
                  "sections": {"title": "Краткая сводка", "body": "Семейно-детский квестовый формат."},
                  {"title": "Цены", "body": "Пакеты и доплаты."}
                }
                """;

        DeepCompanyResearchReport report = service.parseReport(
                company,
                new OpenAiResponseResult("resp_1", text, "gpt-5.4-mini", 10, 20)
        );

        assertThat(report.warnings()).doesNotContain("OpenAI вернул отчет не в ожидаемом JSON-формате, показан сырой текст.");
        assertThat(report.sections()).extracting(DeepCompanyResearchReport.Section::title)
                .containsExactly("Краткая сводка", "Цены");
        assertThat(report.reportMarkdown()).contains("## Краткая сводка", "Семейно-детский квестовый формат.");
        assertThat(report.sources()).hasSize(1);
        assertThat(report.qualityChecks()).extracting(DeepCompanyResearchReport.QualityCheck::key)
                .contains("sections", "sources", "city", "risks", "offers");
        assertThat(report.factSnapshot().confirmedFacts()).extracting(DeepCompanyResearchReport.FactItem::label)
                .contains("Компания", "Источники отчёта");
        assertThat(report.factSnapshot().uncertainFacts()).extracting(DeepCompanyResearchReport.FactItem::label)
                .contains("Город");
        assertThat(report.factSnapshot().sourceReviews()).hasSize(1);
    }

    @Test
    void parsesReportWhenSectionsComeAsNamedObject() {
        Company company = Company.builder()
                .id(1L)
                .title("IQuest")
                .city("Ангарск")
                .build();
        String text = """
                {
                  "sources": [],
                  "warnings": [],
                  "sections": {
                    "summary": {"title": "Краткая сводка", "body": "Факты о бизнесе."},
                    "prices": {"title": "Цены", "body": "Прайс."}
                  }
                }
                """;

        DeepCompanyResearchReport report = service.parseReport(
                company,
                new OpenAiResponseResult("resp_1", text, "gpt-5.4-mini", 10, 20)
        );

        assertThat(report.sections()).extracting(DeepCompanyResearchReport.Section::title)
                .containsExactly("Краткая сводка", "Цены");
        assertThat(report.reportMarkdown()).contains("## Цены", "Прайс.");
        assertThat(report.qualityChecks()).isNotEmpty();
        assertThat(report.factSnapshot().uncertainFacts()).isNotEmpty();
    }

    @Test
    void deserializesLegacyReportWithoutQualityFields() throws Exception {
        String json = """
                {
                  "companyId": 1,
                  "companyName": "IQuest",
                  "city": "Ангарск",
                  "provider": "openai",
                  "model": "gpt-5.4-mini",
                  "responseId": "resp_1",
                  "reportMarkdown": "## Сводка",
                  "sections": [{"title": "Сводка", "body": "Текст"}],
                  "sources": [],
                  "warnings": [],
                  "createdAt": "2026-05-13T10:00:00"
                }
                """;

        DeepCompanyResearchReport report = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readValue(json, DeepCompanyResearchReport.class);

        assertThat(report.qualityChecks()).isEmpty();
        assertThat(report.factSnapshot().confirmedFacts()).isEmpty();
        assertThat(report.factSnapshot().uncertainFacts()).isEmpty();
    }
}
