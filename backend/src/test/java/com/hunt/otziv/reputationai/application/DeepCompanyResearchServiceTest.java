package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiResponseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeepCompanyResearchServiceTest {

    private final DeepCompanyResearchService service = new DeepCompanyResearchService(null, null, null, new ObjectMapper(), null);

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
        assertThat(report.sources().get(0).type()).isEqualTo("official_site");
        assertThat(report.sources().get(0).usedFor()).contains("факты");
        assertThat(report.sources().get(0).confidence()).isEqualTo("high");
        assertThat(report.qualityChecks()).extracting(DeepCompanyResearchReport.QualityCheck::key)
                .contains(
                        "sections",
                        "sources",
                        "city",
                        "duplicates",
                        "maps_directories",
                        "card_details",
                        "amenities",
                        "card_readiness",
                        "risks",
                        "offers"
                );
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
    void extractsThirtyReviewIdeasFromReportSection() {
        Company company = Company.builder()
                .id(1L)
                .title("IQuest")
                .city("Ангарск")
                .build();
        StringBuilder ideasBody = new StringBuilder();
        for (int index = 1; index <= 30; index++) {
            ideasBody
                    .append(index)
                    .append(". Идея ")
                    .append(index)
                    .append(" для честного отзыва по конкретной позиции")
                    .append("\\n");
        }
        String text = """
                {
                  "sources": [],
                  "warnings": [],
                  "sections": [
                    {"title": "Идеи для отзывов", "body": "%s"}
                  ]
                }
                """.formatted(ideasBody.toString());

        DeepCompanyResearchReport report = service.parseReport(
                company,
                new OpenAiResponseResult("resp_1", text, "gpt-5.5", 10, 20)
        );

        assertThat(report.reviewIdeas()).hasSize(30);
        assertThat(report.reviewIdeas().get(0)).isEqualTo("Идея 1 для честного отзыва по конкретной позиции");
        assertThat(report.reviewIdeas().get(29)).isEqualTo("Идея 30 для честного отзыва по конкретной позиции");
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
                  "sources": [{"title": "Сайт", "url": "https://example.ru", "note": "Факты"}],
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
        assertThat(report.sources()).hasSize(1);
        assertThat(report.sources().get(0).type()).isEqualTo("other");
        assertThat(report.sources().get(0).usedFor()).isEmpty();
        assertThat(report.sources().get(0).confidence()).isEqualTo("medium");
    }

    @Test
    void extractsCollectionGapItemsFromReportSection() {
        DeepCompanyResearchReport report = new DeepCompanyResearchReport(
                1L,
                "IQuest",
                "Ангарск",
                "openai",
                "gpt-5.5",
                "resp_1",
                "",
                java.util.List.of(
                        new DeepCompanyResearchReport.Section("Краткая сводка", "Факты."),
                        new DeepCompanyResearchReport.Section(
                                "Что ещё собирать",
                                """
                                        Для полноценного AI-профиля стоит собрать у менеджера:
                                        1. Подтверждённый список действующих филиалов: адрес, этаж, вход, режим.
                                        2. Парковка, доступность, гардероб, туалет, зона ожидания.
                                        - Актуальный прайс по всем пакетам и доплатам.
                                        """
                        )
                ),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                null
        );

        assertThat(service.collectionGapItems(report))
                .containsExactly(
                        "Подтверждённый список действующих филиалов: адрес, этаж, вход, режим.",
                        "Парковка, доступность, гардероб, туалет, зона ожидания.",
                        "Актуальный прайс по всем пакетам и доплатам."
                );
    }

    @Test
    void enablesCollectionGapEnrichmentByDefault() {
        ReputationResearchRequest economy = request("economy", null);
        ReputationResearchRequest quality = request("quality", null);
        ReputationResearchRequest maximumDisabled = request("maximum", false);

        assertThat(economy.shouldEnrichCollectionGaps()).isTrue();
        assertThat(quality.shouldEnrichCollectionGaps()).isTrue();
        assertThat(maximumDisabled.shouldEnrichCollectionGaps()).isFalse();
    }

    private ReputationResearchRequest request(String profile, Boolean enrichCollectionGaps) {
        return new ReputationResearchRequest(
                null,
                null,
                List.of(),
                List.of(),
                true,
                profile,
                null,
                null,
                null,
                null,
                enrichCollectionGaps
        );
    }
}
