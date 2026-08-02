package com.hunt.otziv.reputationai.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.reputationai.api.dto.ReputationResearchRequest;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.dto.OpenAiResponseResult;
import com.hunt.otziv.reputationai.infrastructure.ai.dto.AiRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                        "offers",
                        "coverage"
                );
        assertThat(report.qualityChecks())
                .filteredOn(check -> "coverage".equals(check.key()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("warn");
                    assertThat(check.detail()).startsWith("partial:");
                });
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
        assertThat(report.qualityChecks())
                .filteredOn(check -> "coverage".equals(check.key()))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("fail");
                    assertThat(check.detail()).startsWith("insufficient_data:");
                });
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

    @Test
    void generatesReviewIdeasOnlyAfterGapEnrichmentFromFinalReport() {
        com.hunt.otziv.c_companies.repository.CompanyRepository companyRepository = mock(
                com.hunt.otziv.c_companies.repository.CompanyRepository.class);
        com.hunt.otziv.reputationai.infrastructure.ai.openai.service.OpenAiResponsesClient client = mock(
                com.hunt.otziv.reputationai.infrastructure.ai.openai.service.OpenAiResponsesClient.class);
        CompanyResearchService companyResearchService = mock(CompanyResearchService.class);
        ReputationAiPromptService promptService = mock(ReputationAiPromptService.class);
        DeepCompanyResearchService researchService = new DeepCompanyResearchService(
                companyRepository, client, companyResearchService, new ObjectMapper(), promptService);
        Company company = Company.builder().id(7L).title("Тест").city("Иркутск").build();
        when(companyRepository.findByIdForReputationAi(7L)).thenReturn(java.util.Optional.of(company));
        when(client.isAvailable()).thenReturn(true);
        when(client.usesExternalSearchContext()).thenReturn(true);
        when(client.activeProviderDisplayName()).thenReturn("DeepSeek");
        when(companyResearchService.createSnapshot(eq(7L), any(ReputationResearchRequest.class))).thenReturn(
                new ResearchSnapshot(
                        7L, "Тест", "Иркутск", "https://example.org", "Спецтехника", "Продажа", "",
                        List.of("продажа спецтехники"), List.of(), List.of(), List.of(), List.of(),
                        List.of(new CompanySource(
                                "search:company_profile",
                                "Карточка на карте",
                                "https://2gis.ru/irkutsk/firm/example",
                                "Тест, Иркутск, подтверждённая карточка"
                        )),
                        "yandex", true,
                        List.of("Тест Иркутск site:2gis.ru"), 1, 1, List.of(), java.time.LocalDateTime.now()
                )
        );
        when(promptService.content(anyString())).thenReturn("Верни валидный JSON.");
        when(client.createResearchReportResponse(anyString(), anyString(), anyString())).thenReturn(
                new OpenAiResponseResult(
                        "main",
                        """
                                {"sections":[
                                  {"title":"Краткая сводка","body":"Подтверждённая услуга: подбор техники."},
                                  {"title":"Что ещё собирать","body":"1. Проверить условия доставки по официальным страницам."},
                                  {"title":"Идеи для отзывов","body":"1. Старая преждевременная идея про лизинг."},
                                  {"title":"Риски","body":"Не переносить данные конкурентов."}
                                ],"sources":[
                                  {"title":"Сайт","url":"https://example.org","confidence":"high"},
                                  {"title":"Карточка","url":"https://maps.example.org/card","confidence":"medium"}
                                ],"warnings":[],"reviewIdeas":["Старая преждевременная идея про лизинг"]}
                                """,
                        "deepseek", "deepseek-v4-pro", 10, 10, ""
                )
        );
        when(client.createResearchGapEnrichmentResponse(anyString(), anyString(), anyString())).thenReturn(
                new OpenAiResponseResult(
                        "gap",
                        """
                                {"section":{"title":"Автодосбор по рекомендациям","body":"Условия доставки подтверждены официальной страницей."},
                                 "sources":[],"warnings":[]}
                                """,
                        "deepseek", "deepseek-v4-pro", 10, 10, ""
                )
        );
        when(client.createTextResponse(any(AiRequest.class))).thenReturn(
                new OpenAiResponseResult(
                        "ideas",
                        "{\"reviewIdeas\":[\"условиях доставки после согласования комплектации\"],\"warnings\":[]}",
                        "deepseek", "deepseek-v4-pro", 10, 10, ""
                )
        );

        DeepCompanyResearchReport report = researchService.createReport(7L, request("quality", true));

        verify(companyResearchService).createSnapshot(eq(7L), any(ReputationResearchRequest.class));
        InOrder order = inOrder(client);
        ArgumentCaptor<String> researchInput = ArgumentCaptor.forClass(String.class);
        order.verify(client).createResearchReportResponse(anyString(), researchInput.capture(), eq("quality"));
        order.verify(client).createResearchGapEnrichmentResponse(anyString(), anyString(), eq("quality"));
        ArgumentCaptor<AiRequest> finalRequest = ArgumentCaptor.forClass(AiRequest.class);
        order.verify(client).createTextResponse(finalRequest.capture());
        assertThat(finalRequest.getValue().task()).isEqualTo("company-review-ideas-final");
        assertThat(researchInput.getValue())
                .contains("Дополнительный независимый сборщик: yandex")
                .contains("https://2gis.ru/irkutsk/firm/example")
                .contains("подтверждённая карточка");
        assertThat(finalRequest.getValue().userPrompt())
                .contains("Условия доставки подтверждены официальной страницей")
                .doesNotContain("Старая преждевременная идея про лизинг");
        assertThat(report.reviewIdeas())
                .containsExactly("условиях доставки после согласования комплектации");
        assertThat(report.sections()).extracting(DeepCompanyResearchReport.Section::title)
                .containsSubsequence("Автодосбор по рекомендациям", "Идеи для отзывов");
        assertThat(report.reportMarkdown()).doesNotContain("Старая преждевременная идея про лизинг");
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
