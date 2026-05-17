package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftTarget;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftItem;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewGenerationBrief;
import com.hunt.otziv.reputationai.domain.ReviewGenerationSlot;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReputationSingleReviewDraftServiceTest {

    @Mock
    private ReputationContentPackJobRepository contentPackJobRepository;

    @Mock
    private ReputationDeepReportJobRepository deepReportJobRepository;

    @Mock
    private AiSingleReviewDraftFactory aiSingleReviewDraftFactory;

    private ObjectMapper objectMapper;
    private ReputationSingleReviewDraftService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ReviewSafetyService reviewSafetyService = new ReviewSafetyService();
        service = new ReputationSingleReviewDraftService(
                contentPackJobRepository,
                deepReportJobRepository,
                aiSingleReviewDraftFactory,
                reviewSafetyService,
                objectMapper
        );
    }

    @Test
    void generatesLocalSingleDraftFromPackAndReportWhenAiDoesNotReturnDraft() {
        ReputationContentPack pack = pack();
        ReputationContentPackJobEntity packEntity = contentPackEntity(pack);
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(report());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.of(packEntity));
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationSingleReviewDraftResult result = service.generate(
                7L,
                new ReputationSingleReviewDraftRequest(
                        null,
                        null,
                        "Пакет дня рождения с чайной зоной",
                        "живой, спокойный",
                        "без цен",
                        "medium",
                        "quality"
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.companyName()).isEqualTo("Iquest");
        assertThat(result.idea()).isEqualTo("Пакет дня рождения с чайной зоной");
        assertThat(result.draft()).isNotBlank();
        assertThat(result.draft()).doesNotContain("Iquest");
        assertThat(result.sourceFacts()).isNotEmpty();
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("реальный опыт"));
        assertThat(result.safetyReport()).isNotNull();
    }

    @Test
    void generatesLocalSingleDraftFromDeepReportWhenContentPackIsMissing() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithReviewIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationSingleReviewDraftResult result = service.generate(
                7L,
                new ReputationSingleReviewDraftRequest(
                        null,
                        null,
                        "Отзыв о первичной диагностике: что беспокоило в машине и как объяснили ремонт.",
                        "живой, спокойный",
                        "",
                        "medium",
                        "quality"
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.companyName()).isEqualTo("Skr Service");
        assertThat(result.contentPackJobId()).isNull();
        assertThat(result.idea()).contains("первичной диагностике");
        assertThat(result.draft()).doesNotContain("Skr Service");
        assertThat(result.sourceFacts()).anyMatch(fact -> fact.contains("первичной диагностике"));
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("глубокому отчёту"));

        ArgumentCaptor<ReputationContentPack> packCaptor = ArgumentCaptor.forClass(ReputationContentPack.class);
        verify(aiSingleReviewDraftFactory).create(any(), any(), any(), any(), packCaptor.capture(), any(), any(), any());
        ReputationContentPack generatedPack = packCaptor.getValue();
        assertThat(generatedPack.honestReviewTopics())
                .contains("Отзыв о первичной диагностике: что беспокоило в машине и как объяснили ремонт.");
        assertThat(generatedPack.honestReviewTopics())
                .noneMatch(topic -> topic.contains("Оплата: публично указаны"));
    }

    @Test
    void keepsLocalFactsInsideSelectedRoadTripTheme() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithReviewIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationSingleReviewDraftResult result = service.generate(
                7L,
                new ReputationSingleReviewDraftRequest(
                        null,
                        null,
                        "ТО перед дальней дорогой",
                        "живой, спокойный",
                        "",
                        "medium",
                        "quality"
                )
        );

        assertThat(result.idea()).isEqualTo("ТО перед дальней дорогой");
        assertThat(result.draft()).contains("ТО перед дальней дорогой");
        assertThat(result.sourceFacts()).anyMatch(text -> text.contains("ТО перед дальней дорогой"));
        assertThat(result.sourceFacts()).noneMatch(text -> text.contains("Оплата: публично указаны"));
    }

    @Test
    void passesCommercialRowsWithDescriptionsAndPricesToSingleDraftFactory() {
        ReputationContentPack pack = pack();
        ReputationContentPackJobEntity packEntity = contentPackEntity(pack);
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithCommercialTable());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.of(packEntity));
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        service.generate(
                7L,
                new ReputationSingleReviewDraftRequest(
                        null,
                        null,
                        "Детский день рождения в одном месте",
                        "живой, спокойный",
                        "",
                        "medium",
                        "quality"
                )
        );

        ArgumentCaptor<List<String>> factsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).create(any(), any(), any(), any(), any(), any(), any(), factsCaptor.capture());

        assertThat(factsCaptor.getValue())
                .anyMatch(fact -> fact.contains("Детский день рождения")
                        && fact.contains("квест")
                        && fact.contains("от 12 000 руб"));
    }

    @Test
    void buildsCleanBatchBriefAndAlignsSlotsWithTheirThemes() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationBatchReviewDraftResult result = service.generateBatch(
                7L,
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of(
                                new ReputationBatchReviewDraftTarget(
                                        1L,
                                        "Отзыв о первичной диагностике: что беспокоило в машине, как объяснили причину.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        2L,
                                        "Отзыв о ремонте ходовой после стука/скрипа: какие детали меняли, сколько заняло времени.",
                                        "",
                                        ""
                                )
                        )
                )
        );
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft()).doesNotContain("Skr Service"));

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        ReviewGenerationBrief brief = briefCaptor.getValue();
        List<String> briefFacts = new java.util.ArrayList<>();
        briefFacts.addAll(brief.travelFromCenter());
        briefFacts.addAll(brief.employees());
        briefFacts.addAll(brief.amenities());
        briefFacts.addAll(brief.parking());
        briefFacts.addAll(brief.interestingFacts());

        assertThat(brief.businessType()).isEqualTo("auto_service");
        assertThat(brief.allowedScenarioTypes()).contains("диагностика", "ремонт");
        assertThat(brief.services()).contains("первичная диагностика", "ремонт ходовой");
        assertThat(brief.reviewIdeas())
                .noneMatch(idea -> idea.startsWith("Отзыв о ") || idea.contains("добавить"));
        assertThat(briefFacts).anyMatch(fact -> fact.contains("Восточный МЖК"));
        assertThat(briefFacts).anyMatch(fact -> fact.toLowerCase().contains("зон") && fact.contains("ожидания"));
        assertThat(briefFacts)
                .noneMatch(fact -> fact.contains("Несколько отзывов")
                        || fact.contains("Филиалы")
                        || fact.contains("Найден только")
                        || fact.contains("Команда:")
                        || fact.contains("На сайте"));

        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();
        assertThat(slots).hasSize(2);
        assertThat(slots.get(1).theme()).contains("ремонте ходовой");
        assertThat(slots.get(1).service()).contains("ходовой");
        assertThat(slots.get(1).service()).doesNotContain("масла");
        assertThat(slots.getFirst().clientMustConfirm()).contains("какая машина была у клиента");
        assertThat(slots)
                .allSatisfy(slot -> {
                    assertThat(slot.mustUse()).noneMatch(fact -> fact.startsWith("Отзыв о "));
                    assertThat(slot.mayUse()).noneMatch(fact -> fact.contains("Несколько отзывов")
                            || fact.contains("Филиалы")
                            || fact.contains("Найден только"));
                });
    }

    @Test
    void doesNotAddAutoServiceHintsToQuestBatch() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithQuestBatchIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationBatchReviewDraftResult result = service.generateBatch(
                7L,
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of(
                                new ReputationBatchReviewDraftTarget(11L, "Квест.", "", ""),
                                new ReputationBatchReviewDraftTarget(12L, "Квест.", "", ""),
                                new ReputationBatchReviewDraftTarget(13L, "Лазертаг для детской компании 7+.", "", "")
                        )
                )
        );
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft()).doesNotContain("Iquest"));

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        ReviewGenerationBrief brief = briefCaptor.getValue();
        assertThat(brief.businessType()).isEqualTo("entertainment");
        assertThat(brief.allowedScenarioTypes()).contains("день рождения", "квест");
        assertThat(brief.services()).contains("дни рождения под ключ", "квесты с актерами");
        assertThat(brief.services()).doesNotContain("замена масла", "комплексное ТО");
        assertThat(brief.reviewIdeas())
                .noneMatch(idea -> idea.startsWith("Отзыв о ")
                        || idea.contains("добавить")
                        || idea.startsWith("Выбирали "));

        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();
        assertThat(slots).allSatisfy(slot -> assertThat(slot.theme()).doesNotContain("Квест."));
        List<String> slotServices = slots.stream()
                .map(ReviewGenerationSlot::service)
                .toList();
        assertThat(slotServices)
                .contains("лазертаг")
                .doesNotContain("замена масла", "комплексное ТО", "ремонт двигателя", "ремонт стартера");
        assertThat(slotServices.stream().filter(service -> service != null && !service.isBlank()).distinct().count())
                .isGreaterThanOrEqualTo(2);
        slots.stream()
                .filter(slot -> "дни рождения под ключ".equals(slot.service()))
                .findFirst()
                .ifPresent(slot -> assertThat(slot.mustUse())
                        .anyMatch(value -> value.contains("Пакет 1") || value.contains("2 часа 40 минут")));
        slots.stream()
                .filter(slot -> "квесты с актерами".equals(slot.service()))
                .findFirst()
                .ifPresent(slot -> assertThat(slot.mustUse()).anyMatch(value -> value.contains("Квест Сталкер")));
        assertThat(slots.stream()
                .filter(slot -> "лазертаг".equals(slot.service()))
                .findFirst()
                .orElseThrow()
                .mustUse()).noneMatch(value -> value.contains("Экзорцизм"));
        slots.stream()
                .filter(slot -> "дни рождения под ключ".equals(slot.service()))
                .findFirst()
                .ifPresent(slot -> assertThat(slot.clientMustConfirm())
                        .anyMatch(value -> value.contains("сценарий") || value.contains("формат")));
        assertThat(slots).allSatisfy(slot -> assertThat(slot.clientMustConfirm()).noneMatch(value -> value.contains("машина")));
        assertThat(slots)
                .allSatisfy(slot -> assertThat(slot.mustUse()).noneMatch(fact -> fact.startsWith("Отзыв о ")));
    }

    @Test
    void treatsConstructionBatchAsLocalServiceAndFiltersReportNotes() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithConstructionBatchIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationBatchReviewDraftResult result = service.generateBatch(
                7L,
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of(
                                new ReputationBatchReviewDraftTarget(21L, "Штукатурные работ.", "", ""),
                                new ReputationBatchReviewDraftTarget(22L, "Штукатурные работ.", "", ""),
                                new ReputationBatchReviewDraftTarget(23L, "Штукатурные работ.", "", "")
                        )
                )
        );
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft()).doesNotContain("Штукатур Вл"));

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        ReviewGenerationBrief brief = briefCaptor.getValue();
        assertThat(brief.businessType()).isEqualTo("local_service");
        assertThat(brief.allowedScenarioTypes()).contains("замер или консультация", "смета", "выполнение работ");
        assertThat(brief.allowedScenarioTypes()).doesNotContain("запись на прием");
        assertThat(brief.services()).contains("механизированная штукатурка");

        List<String> allBriefValues = new java.util.ArrayList<>();
        allBriefValues.addAll(brief.services());
        allBriefValues.addAll(brief.products());
        allBriefValues.addAll(brief.prices());
        allBriefValues.addAll(brief.advantages());
        allBriefValues.addAll(brief.reviewIdeas());
        allBriefValues.addAll(brief.travelFromCenter());
        allBriefValues.addAll(brief.employees());
        allBriefValues.addAll(brief.amenities());
        allBriefValues.addAll(brief.parking());
        allBriefValues.addAll(brief.interestingFacts());
        assertThat(allBriefValues).noneMatch(value -> value.contains("Сильная основа")
                || value.contains("возможно")
                || value.contains("Уточнить")
                || value.contains("Наличие фото подтверждено")
                || value.contains("Собрать фото")
                || value.contains("Интерьер/экстерьер"));

        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();
        assertThat(slots).hasSize(3);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.theme()).doesNotContain("Штукатурные работ");
            assertThat(slot.clientMustConfirm()).noneMatch(value -> value.contains("медицин"));
            assertThat(slot.mustUse()).noneMatch(value -> value.contains("Сильная основа")
                    || value.contains("возможно")
                    || value.contains("Уточнить")
                    || value.contains("Наличие фото подтверждено"));
        });
    }

    @Test
    void localBatchFallbackUsesNaturalTopicPhrasesInsteadOfLongThemeLabels() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithConstructionBatchIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ReputationBatchReviewDraftResult result = service.generateBatch(
                7L,
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of(
                                new ReputationBatchReviewDraftTarget(
                                        31L,
                                        "Коммуникация с менеджером/прорабом: как быстро отвечали, согласовывали график и изменения.",
                                        "Понравилось, что в Штукатур Вл разговор был предметный.",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        32L,
                                        "White Box в новостройке: как компания объединила перегородки, стяжку и подготовку стен.",
                                        "В Штукатур Вл не стали уходить в общие обещания.",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        33L,
                                        "Работа по договору и безналичной оплате для коммерческого объекта или склада.",
                                        "Искал детали у Штукатур Вл.",
                                        ""
                                )
                        )
                )
        );

        assertThat(result.drafts()).hasSize(3);
        assertThat(result.drafts()).allSatisfy(item -> {
            assertThat(item.draft()).doesNotContain("Штукатур Вл");
            assertThat(item.draft()).doesNotContain("Коммуникация с менеджером/прорабом:");
            assertThat(item.draft()).doesNotContain("White Box в новостройке:");
            assertThat(item.draft()).doesNotContain("как компания");
        });
        assertThat(result.drafts().stream().map(ReputationBatchReviewDraftItem::draft).toList())
                .anyMatch(draft -> draft.contains("коммуникации с менеджером/прорабом"))
                .anyMatch(draft -> draft.contains("White Box"))
                .anyMatch(draft -> draft.contains("работе по договору"));
    }

    private ReputationContentPackJobEntity contentPackEntity(ReputationContentPack pack) {
        ReputationContentPackJobEntity entity = new ReputationContentPackJobEntity();
        entity.setCompanyId(7L);
        entity.setCompanyTitle("Iquest");
        entity.setProvider("openai");
        entity.setModel("gpt-5.5");
        entity.setPackJson(write(pack));
        return entity;
    }

    private ReputationDeepReportJobEntity deepReportEntity(DeepCompanyResearchReport report) {
        ReputationDeepReportJobEntity entity = new ReputationDeepReportJobEntity();
        entity.setCompanyId(7L);
        entity.setCompanyTitle("Iquest");
        entity.setProvider("openai");
        entity.setModel("gpt-5.5");
        entity.setReportJson(write(report));
        return entity;
    }

    private ReputationContentPack pack() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                7L,
                "Iquest",
                "Ангарск",
                "https://iquest.example",
                "Детские праздники",
                "Квесты",
                "Есть филиалы и чайная зона",
                List.of("пакет дня рождения", "лазертаг"),
                List.of("чайная зона", "активные игры"),
                List.of("эмоции детей"),
                List.of("уточнение цены"),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://iquest.example", "Пакеты и программы")),
                "local",
                false,
                List.of(),
                0,
                1,
                List.of(),
                LocalDateTime.now()
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                "Центр детских праздников",
                "Развлечения",
                List.of("пакет дня рождения", "лазертаг"),
                List.of("чайная зона", "фото"),
                List.of("детям понравились активности"),
                List.of("нужно уточнять цены"),
                List.of("условия могут меняться")
        );
        return new ReputationContentPack(
                snapshot,
                profile,
                List.of("Пакет дня рождения объединяет квест, чайную зону и активные игры в одном сценарии."),
                List.of("День рождения без отдельного кафе: программа, чайная зона и фото в одном месте."),
                List.of("Как выбрать программу для детского дня рождения"),
                List.of("Перед бронированием важно уточнить возраст, уровень страха и что входит в пакет."),
                List.of("Пакет дня рождения: что помогло выбрать и какой личный штрих добавить."),
                List.of("Выбрали Iquest для дня рождения, потому что [личная причина]."),
                List.of("Спасибо за отзыв!"),
                List.of("Жаль, что возникли вопросы."),
                List.of("https://iquest.example"),
                List.of("Старые цены нужно перепроверить.")
        );
    }

    private DeepCompanyResearchReport report() {
        return new DeepCompanyResearchReport(
                7L,
                "Iquest",
                "Ангарск",
                "openai",
                "gpt-5.5",
                "resp-1",
                "В отчете есть пакеты дня рождения, чайная зона, адреса и ограничения.",
                List.of(new DeepCompanyResearchReport.Section(
                        "Сценарии и УТП",
                        "Пакеты дня рождения объединяют квест, активную игру и чайную зону."
                )),
                List.of(new DeepCompanyResearchReport.Source("Сайт", "https://iquest.example", "Пакеты")),
                List.of("Парковка не подтверждена."),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private DeepCompanyResearchReport reportWithReviewIdeas() {
        return new DeepCompanyResearchReport(
                7L,
                "Skr Service",
                "Иркутск",
                "openai",
                "gpt-5.5",
                "resp-2",
                "В отчете есть диагностика, ремонт ходовой и подбор запчастей.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Сводка по компании",
                                "Skr Service занимается диагностикой, ремонтом ходовой, заменой масла и подбором запчастей."
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Ровно 10 идей для будущих честных отзывов",
                                """
                                        1. Отзыв о первичной диагностике: что беспокоило в машине и как объяснили ремонт.
                                        2. Отзыв о ремонте ходовой после стука: какие детали меняли и сколько заняло времени.
                                        3. Отзыв о подборе запчастей: были ли детали в наличии или под заказ.
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Дополнительный публичный дозбор: Skr Service, Новосибирск",
                                """
                                        Что спросить у владельца

                                        - Оплата: публично указаны карта и QR-код.
                                        - Запись и контакты: подтверждены телефоны и предварительная запись.
                                        - Команда: часть данных взята из вакансий, имена мастеров не раскрыты.
                                        """
                        )
                ),
                List.of(new DeepCompanyResearchReport.Source("Сайт", "https://skr.example", "Услуги")),
                List.of("Цены нужно перепроверить перед публикацией."),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private DeepCompanyResearchReport reportWithCommercialTable() {
        return new DeepCompanyResearchReport(
                7L,
                "Iquest",
                "Ангарск",
                "openai",
                "gpt-5.5",
                "resp-3",
                "В отчете есть пакеты праздников, форматы программ и цены.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Услуги, товары и цены",
                                """
                                        | Позиция | Описание | Условия/сроки | Цена | Источник/уверенность |
                                        | --- | --- | --- | --- | --- |
                                        | Детский день рождения под ключ | квест с актёром, чайная зона и активная часть | около 2 часов | от 12 000 руб. | сайт, средняя |
                                        | Among Us | командная игра для детей | по записи | от 5 000 руб. | сайт, средняя |
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Идеи для отзывов",
                                "1. Детский день рождения в одном месте: как выбрали формат и что помогло не искать отдельное кафе."
                        )
                ),
                List.of(new DeepCompanyResearchReport.Source("Сайт", "https://iquest.example/prices", "Пакеты и цены")),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private DeepCompanyResearchReport reportWithNoisyBatchFacts() {
        return new DeepCompanyResearchReport(
                7L,
                "Skr Service",
                "Новосибирск",
                "openai",
                "gpt-5.5",
                "resp-4",
                "В отчете есть диагностика, ремонт ходовой, ожидание и подбор запчастей.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Ровно 10 идей для будущих честных отзывов",
                                """
                                        1. Отзыв о первичной диагностике: что беспокоило в машине, как объяснили причину и какие варианты ремонта предложили.
                                        2. Отзыв о ремонте ходовой после стука/скрипа: какие детали меняли, сколько заняло времени, как изменилась управляемость.
                                        3. Отзыв о замене масла или комплексном ТО: как записались, что проверили дополнительно.
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Логистика и удобства",
                                """
                                        - Восточный МЖК, Октябрьский район.
                                        - наличие зоны ожидания, Wi-Fi, туалета.
                                        - Запчасти в наличии или под заказ. Несколько отзывов говорят, что запчасти находят сами.
                                        - Филиалы. Найден только один подтвержденный адрес.
                                        - «Запчасти в наличии и под заказ: как согласуем аналоги и сроки».
                                        - На сайте/в карточках нет списка мастеров, руководителя, диагностов, мотористов.
                                        - Команда: имена мастеров, специализация, стаж, фото, кто отвечает за приемку.
                                        """
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private DeepCompanyResearchReport reportWithQuestBatchIdeas() {
        return new DeepCompanyResearchReport(
                7L,
                "Iquest",
                "Ангарск",
                "openai",
                "gpt-5.5",
                "resp-5",
                "В отчете есть квесты, дни рождения, лазертаг и чайная зона.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Ровно 10 идей для будущих честных отзывов",
                                """
                                        1. День рождения под ключ: как связка «квест с актером + вторая игра + чайная зона» помогла провести праздник без отдельного кафе.
                                        2. Квест с актерами: как атмосфера, декорации, костюмы и участие персонажей повлияли на вовлечение команды.
                                        3. Лазертаг для детской компании 7+: как активная игра с аниматором помогла занять детей.
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Услуги и удобства",
                                """
                                        квесты с актерами, детские квесты, хоррор-квесты, лазертаг, дни рождения под ключ.
                                        Квест Сталкер.
                                        квест «Экзорцизм».
                                        Пакет 1: 2 часа 40 минут, 6500 руб.
                                        чайная зона для гостей, можно добавить фотографа, можно заказать питание.
                                        После входа игроков в локацию стоимость аренды не возвращается.
                                        - «Неясно, что входит в пакет» — публиковать состав каждого пакета по минутам и блокам.
                                        Фото экстерьера: фасад, вход, табличка, путь от парковки/остановки.
                                        """
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private DeepCompanyResearchReport reportWithConstructionBatchIdeas() {
        return new DeepCompanyResearchReport(
                7L,
                "Штукатур Вл",
                "Владивосток",
                "openai",
                "gpt-5.5",
                "resp-6",
                "Компания занимается механизированной штукатуркой, стяжкой пола и приемкой квартир в новостройках.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Ровно 10 идей для будущих честных отзывов",
                                """
                                        1. Опыт заказа механизированной штукатурки в новостройке: как прошли замер, смета и приемка стен.
                                        2. Полусухая стяжка пола в квартире: сроки выполнения, чистота объекта и когда получилось продолжить ремонт.
                                        3. White Box в новостройке: как компания объединила перегородки, стяжку и подготовку стен.
                                        4. Приемка квартиры от застройщика: какие недостатки нашли и как помог акт замечаний.
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Услуги и логистика",
                                """
                                        - механизированная штукатурка, полусухая стяжка пола, White Box.
                                        - Владивосток, центр/район центральной площади → Завойко 1Б к2.
                                        - ориентировочно 15–30 минут на автомобиле в обычной городской ситуации.
                                        - возможно, это менеджер/прораб/куратор объекта.
                                        - Сильная основа для текстов — механизация, тарифы и приемка.
                                        - Уточнить, входит ли материал в цену и какие материалы используются по тарифам.
                                        - Наличие фото подтверждено, но их содержание текстом не разобрано.
                                        - Собрать фото входа, фасада, вывески, офиса, склада/оборудования.
                                        - Интерьер/экстерьер филиалов.
                                        """
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                LocalDateTime.now()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
