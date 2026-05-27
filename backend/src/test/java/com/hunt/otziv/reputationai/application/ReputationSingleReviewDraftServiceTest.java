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
import com.hunt.otziv.reputationai.domain.ReviewSafetyReport;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationContentPackJobRepository;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobEntity;
import com.hunt.otziv.reputationai.persistence.ReputationDeepReportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
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
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localFallbackKeepsIdeaNuanceAfterColon() {
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
                        "Фотоовал или фото на стекле: почему выбрали этот вариант и как оценили качество изображения.",
                        "живой, спокойный",
                        "",
                        "medium",
                        "quality"
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.draft()).contains("выбор варианта");
        assertThat(result.draft()).contains("оценка качества изображения");
        assertThat(result.draft()).doesNotContain("Iquest");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localFallbackAvoidsBrokenPrepositionWithRawThemes() {
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
                        "Работа конкретного мастера/преподавателя: как контролировали практику и исправляли ошибки.",
                        "живой, спокойный",
                        null,
                        "без смайлов",
                        "",
                        "medium",
                        "quality",
                        0L,
                        "",
                        ""
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.draft()).doesNotContain("с работе");
        assertThat(result.draft()).doesNotContain("По работа");
        assertThat(result.draft()).contains("практике");
        assertThat(result.draft()).contains("ошибки");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localFallbackTurnsAutoServiceFocusIntoNaturalSentence() {
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
                        "ремонте стартера или генератора: была ли срочность, как быстро приняли машину, как объяснили результат.",
                        "живой, спокойный",
                        null,
                        "без смайлов",
                        "",
                        "medium",
                        "quality",
                        7L,
                        "",
                        ""
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.draft()).doesNotContain("Отдельно разобрали:");
        assertThat(result.draft()).doesNotContain("Основную тему обозначил");
        assertThat(result.draft()).doesNotContain("срочность обращения, скорость");
        assertThat(result.draft()).doesNotContain("ремонте стартера");
        assertThat(result.draft()).contains("провер");
        assertThat(result.draft()).contains("объяснили");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localFallbackDoesNotPrintExperienceFocusAsChecklist() {
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
                        "клиентской зоне/ожидании: было ли удобно ждать, объясняли ли ход работ, можно ли было задать вопросы мастеру.",
                        "живой, спокойный",
                        null,
                        "без смайлов",
                        "",
                        "medium",
                        "quality",
                        9L,
                        "",
                        ""
                )
        );

        assertThat(result.provider()).isEqualTo("local");
        assertThat(result.draft()).doesNotContain("Отдельно разобрали:");
        assertThat(result.draft()).doesNotContain("удобство ожидания, объяснение хода работ");
        assertThat(result.draft()).doesNotContain("клиентской зоне/ожидании");
        assertThat(result.draft()).contains("ждал");
        assertThat(result.draft()).contains("вопрос");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
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
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
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

        assertThatThrownBy(() -> service.generate(
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
                ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI-провайдер");

        ArgumentCaptor<List<String>> factsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).create(any(), any(), any(), any(), any(), any(), any(), factsCaptor.capture());

        assertThat(factsCaptor.getValue())
                .anyMatch(fact -> fact.contains("Детский день рождения")
                        && fact.contains("квест")
                        && fact.contains("от 12 000 руб"));
    }

    @Test
    void batchBriefKeepsCommercialRowsWithConcretePrices() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithCommercialTable());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        service.generateBatch(
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
                        List.of(new ReputationBatchReviewDraftTarget(21L, "Детский день рождения в одном месте", "", ""))
                )
        );

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), any());

        assertThat(briefCaptor.getValue().prices())
                .anyMatch(price -> price.contains("Детский день рождения") && price.contains("от 12 000 руб"));
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
                .contains(
                        "первичной диагностике: что беспокоило в машине, как объяснили причину и какие варианты ремонта предложили.",
                        "ремонте ходовой после стука/скрипа: какие детали меняли, сколько заняло времени, как изменилась управляемость.",
                        "замене масла или комплексном ТО: как записались, что проверили дополнительно."
                )
                .noneMatch(idea -> idea.contains("добавить"));
        assertThat(briefFacts).anyMatch(fact -> fact.contains("Восточный МЖК"));
        assertThat(briefFacts).anyMatch(fact -> fact.toLowerCase().contains("зон") && fact.contains("ожидания"));
        assertThat(briefFacts)
                .noneMatch(fact -> fact.contains("Несколько отзывов")
                        || fact.contains("Филиалы")
                        || fact.contains("Найден только")
                        || fact.contains("Команда:")
                        || fact.contains("На сайте"));

        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();
        List<String> reportIdeas = List.of(
                "первичной диагностике: что беспокоило в машине, как объяснили причину и какие варианты ремонта предложили.",
                "ремонте ходовой после стука/скрипа: какие детали меняли, сколько заняло времени, как изменилась управляемость.",
                "замене масла или комплексном ТО: как записались, что проверили дополнительно."
        );
        assertThat(slots).hasSize(2);
        assertThat(slots).allSatisfy(slot -> assertThat(reportIdeas).contains(slot.theme()));
        assertThat(slots.getFirst().clientMustConfirm()).contains("какая машина была у клиента");
        assertThat(slots)
                .allSatisfy(slot -> {
                    if (slot.theme().contains("ходовой")) {
                        assertThat(slot.service()).contains("ходовой");
                    }
                    assertThat(slot.mustUse()).noneMatch(fact -> fact.startsWith("Отзыв о "));
                    assertThat(slot.mayUse()).noneMatch(fact -> fact.contains("Несколько отзывов")
                            || fact.contains("Филиалы")
                            || fact.contains("Найден только"));
                });
    }

    @Test
    void batchSanitizesEmployeeNamesThatRequireConfirmationBeforePrompt() {
        DeepCompanyResearchReport report = new DeepCompanyResearchReport(
                7L,
                "Школа-студия идеальных ногтей",
                "Магнитогорск",
                "openai",
                "gpt-5.5",
                "resp-staff",
                "В отчете есть обучение маникюру, наращивание и работа мастеров.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Услуги, товары и цены",
                                """
                                        маникюр, наращивание ногтей, школа курсов маникюра.
                                        Мария, Татьяна, Раиса и другие мастера — после подтверждения состава.
                                        """
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                List.of(
                        "работе конкретного мастера/преподавателя — Марии, Татьяны, Раисы или другого подтверждённого сотрудника: что именно понравилось в подходе и коммуникации."
                ),
                LocalDateTime.now()
        );
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(report);
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        service.generateBatch(
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
                        List.of(new ReputationBatchReviewDraftTarget(171612L, "работа мастера", "", ""))
                )
        );

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        ReviewGenerationBrief brief = briefCaptor.getValue();
        assertThat(brief.reviewIdeas())
                .contains("работе конкретного мастера/преподавателя: что именно понравилось в подходе и коммуникации.")
                .noneMatch(value -> value.contains("Мария")
                        || value.contains("Татьяна")
                        || value.contains("Раиса")
                        || value.contains("подтвержд"));
        assertThat(brief.employees()).noneMatch(value -> value.contains("Мария")
                || value.contains("Татьяна")
                || value.contains("Раиса")
                || value.contains("подтвержд"));

        ReviewGenerationSlot slot = slotsCaptor.getValue().getFirst();
        assertThat(slot.theme()).isEqualTo("работе конкретного мастера/преподавателя: что именно понравилось в подходе и коммуникации.");
        assertThat(slot.mustUse()).noneMatch(value -> value.contains("Мария")
                || value.contains("Татьяна")
                || value.contains("Раиса")
                || value.contains("подтвержд"));
        assertThat(slot.mayUse()).noneMatch(value -> value.contains("Мария")
                || value.contains("Татьяна")
                || value.contains("Раиса")
                || value.contains("подтвержд"));
        assertThat(slot.clientMustConfirm())
                .contains("конкретного мастера или преподавателя, если имя будет упоминаться");
    }

    @Test
    void batchAddsConfirmedEmployeeNameToOneSlot() {
        DeepCompanyResearchReport report = new DeepCompanyResearchReport(
                7L,
                "Школа-студия идеальных ногтей",
                "Магнитогорск",
                "openai",
                "gpt-5.5",
                "resp-staff-confirmed",
                "В отчете есть обучение маникюру и подтвержденные мастера.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Сотрудники и компетенции",
                                "Мария, Татьяна, Раиса — мастера маникюра."
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                DeepCompanyResearchReport.FactSnapshot.empty(),
                List.of("работе конкретного мастера: что понравилось в подходе и коммуникации."),
                LocalDateTime.now()
        );
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(report);
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        service.generateBatch(
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
                        List.of(new ReputationBatchReviewDraftTarget(171612L, "работа мастера", "", ""))
                )
        );

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        assertThat(briefCaptor.getValue().employees())
                .anyMatch(value -> value.contains("Мария") && value.contains("мастер"));
        ReviewGenerationSlot slot = slotsCaptor.getValue().getFirst();
        assertThat(slot.mustUse()).contains("мастер Мария");
        assertThat(slot.clientMustConfirm())
                .contains("конкретного мастера или преподавателя, если имя будет упоминаться");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void mixedBatchFallbackForAutoStarterGeneratorUsesLocalAutoDraft() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReputationBatchReviewDraftResult(
                        7L,
                        "Skr Service",
                        null,
                        null,
                        "openai",
                        "gpt-5.5",
                        List.of(
                                new ReputationBatchReviewDraftItem(172137L, "первичная диагностика", "Заехал из-за стука в машине, хотел понять состояние авто.", List.of("первичная диагностика"), List.of()),
                                new ReputationBatchReviewDraftItem(172138L, "подбор запчастей", "Нужно было не промахнуться с расходниками, поэтому уточнили подбор.", List.of("подбор запчастей"), List.of()),
                                new ReputationBatchReviewDraftItem(172139L, "диагностика подвески", "После дороги по кочкам заехал на диагностику подвески.", List.of("диагностика подвески"), List.of()),
                                new ReputationBatchReviewDraftItem(172140L, "первичная диагностика", "Я сначала сомневался, ехать ли вообще.", List.of("первичная диагностика"), List.of())
                        ),
                        List.of(),
                        LocalDateTime.now()
                )));

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
                                        172137L,
                                        "клиентской зоне/ожидании: было ли удобно ждать, объясняли ли ход работ, можно ли было задать вопросы мастеру.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        172138L,
                                        "подборе запчастей: были ли детали в наличии или под заказ, как согласовали стоимость и сроки.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        172139L,
                                        "прозрачности ремонта: присылали ли фото/видео, показывали ли старую деталь, согласовывали ли дополнительные работы.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        172140L,
                                        "первичной диагностике: что беспокоило в машине, как объяснили причину и какие варианты ремонта предложили.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        172141L,
                                        "ремонте стартера или генератора: была ли срочность, как быстро приняли машину, как объяснили результат.",
                                        "",
                                        ""
                                )
                        )
                )
        );

        assertThat(result.drafts()).hasSize(5);
        assertThat(result.drafts())
                .filteredOn(item -> item.reviewId().equals(172141L))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.draft())
                            .doesNotStartWith("Сначала сомневался")
                            .contains("запуск")
                            .contains("заряд");
                    assertThat(item.safetyNotes())
                            .anyMatch(note -> note.contains("локальный вариант"));
                });
    }

    @Test
    void batchReturnsOnlyOpenAiDraftsWhenSomeCardsAreFiltered() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.isOpenAiAvailable()).thenReturn(false);
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReputationBatchReviewDraftResult(
                        7L,
                        "Skr Service",
                        null,
                        null,
                        "openai",
                        "gpt-5.5",
                        List.of(
                                new ReputationBatchReviewDraftItem(172137L, "первичная диагностика", "Заехал из-за стука в машине, хотел понять состояние авто.", List.of("первичная диагностика"), List.of()),
                                new ReputationBatchReviewDraftItem(172138L, "подбор запчастей", "Нужно было не промахнуться с расходниками, поэтому уточнили подбор.", List.of("подбор запчастей"), List.of())
                        ),
                        List.of(),
                        LocalDateTime.now()
                )));

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
                                new ReputationBatchReviewDraftTarget(172137L, "первичная диагностика", "", ""),
                                new ReputationBatchReviewDraftTarget(172138L, "подбор запчастей", "", ""),
                                new ReputationBatchReviewDraftTarget(172139L, "диагностика подвески", "", "")
                        )
                )
        );

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172137L, 172138L);
    }

    @Test
    void batchUsesShortFallbackForMissingCards() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.isOpenAiAvailable()).thenReturn(true);
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReputationBatchReviewDraftResult(
                        7L,
                        "Skr Service",
                        null,
                        null,
                        "openai",
                        "gpt-5.5",
                        List.of(
                                new ReputationBatchReviewDraftItem(172137L, "первичная диагностика", "Заехал из-за стука в машине, хотел понять состояние авто.", List.of("первичная диагностика"), List.of()),
                                new ReputationBatchReviewDraftItem(172138L, "подбор запчастей", "Нужно было не промахнуться с расходниками, поэтому уточнили подбор.", List.of("подбор запчастей"), List.of())
                        ),
                        List.of(),
                        LocalDateTime.now()
                )));

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
                                new ReputationBatchReviewDraftTarget(172137L, "первичная диагностика", "", ""),
                                new ReputationBatchReviewDraftTarget(172138L, "подбор запчастей", "", ""),
                                new ReputationBatchReviewDraftTarget(172139L, "диагностика подвески", "", "")
                        )
                )
        );

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172137L, 172138L, 172139L);
        assertThat(result.drafts().get(2).safetyNotes())
                .anyMatch(note -> note.contains("Короткий локальный отзыв по регулярному ритму пачки"));
        assertThat(result.drafts().get(2).draft())
                .isNotBlank()
                .contains("Спасибо, заказывали")
                .contains("всё хорошо, спасибо");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(aiSingleReviewDraftFactory, org.mockito.Mockito.times(1))
                .createBatch(any(), any(), any(), any(), any(), any(), slotsCaptor.capture());
        assertThat(slotsCaptor.getValue())
                .extracting(ReviewGenerationSlot::reviewId)
                .containsExactly(172137L, 172138L);
    }

    @Test
    void batchDoesNotRunSingleOpenAiRetryForMissingCards() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.isOpenAiAvailable()).thenReturn(true);
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReputationBatchReviewDraftResult(
                        7L,
                        "Skr Service",
                        null,
                        null,
                        "openai",
                        "gpt-5.5",
                        List.of(new ReputationBatchReviewDraftItem(
                                172137L,
                                "первичная диагностика",
                                "Заехал из-за стука в машине, хотел понять состояние авто.",
                                List.of("первичная диагностика"),
                                List.of()
                        )),
                        List.of(),
                        LocalDateTime.now()
                )));

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
                                new ReputationBatchReviewDraftTarget(172137L, "первичная диагностика", "", ""),
                                new ReputationBatchReviewDraftTarget(172138L, "подбор запчастей", "", ""),
                                new ReputationBatchReviewDraftTarget(172139L, "диагностика подвески", "", "")
                        )
                )
        );

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172137L, 172138L, 172139L);
        assertThat(result.drafts().stream()
                .filter(item -> item.safetyNotes().stream().anyMatch(note -> note.contains("Короткий локальный")))
                .count()).isEqualTo(2);
        assertThat(result.drafts().stream()
                .filter(item -> item.safetyNotes().stream().anyMatch(note -> note.contains("регулярному ритму пачки")))
                .count()).isEqualTo(1);
        verify(aiSingleReviewDraftFactory, org.mockito.Mockito.times(1))
                .createBatch(any(), any(), any(), any(), any(), any(), any());
        verify(aiSingleReviewDraftFactory, org.mockito.Mockito.never())
                .create(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void batchReturnsNoDraftsWhenOpenAiReturnsNothing() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.isOpenAiAvailable()).thenReturn(false);
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
                        List.of(new ReputationBatchReviewDraftTarget(172137L, "первичная диагностика", "", ""))
                )
        );

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.drafts()).isEmpty();
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("AI-провайдер не вернул подходящие тексты"));
    }

    @Test
    void batchDoesNotRetryMissingCardsAfterOpenAiTransportFailure() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithNoisyBatchFacts());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.isOpenAiAvailable()).thenReturn(true);
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new ReputationBatchReviewDraftResult(
                        7L,
                        "Skr Service",
                        null,
                        null,
                        "openai",
                        "gpt-5.5",
                        List.of(),
                        List.of("Запрос OpenAI оборвался на сетевом уровне: HTTP/1.1 header parser received no bytes"),
                        LocalDateTime.now()
                )));

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
                                new ReputationBatchReviewDraftTarget(172137L, "первичная диагностика", "", ""),
                                new ReputationBatchReviewDraftTarget(172138L, "подбор запчастей", "", "")
                        )
                )
        );

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.drafts()).isEmpty();
        assertThat(result.safetyNotes()).anyMatch(note -> note.contains("header parser received no bytes"));
        verify(aiSingleReviewDraftFactory, org.mockito.Mockito.times(1))
                .createBatch(any(), any(), any(), any(), any(), any(), any());
        verify(aiSingleReviewDraftFactory, org.mockito.Mockito.never())
                .create(any(), any(), any(), any(), any(), any(), any(), any());
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
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft())
                .doesNotContain("Практические детали объяснили нормальным языком")
                .doesNotContain("решение не выглядело выбором наугад"));

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
    void concreteQuestProductDoesNotFitDifferentNamedScenarioTheme() {
        Boolean genericQuestFits = ReflectionTestUtils.invokeMethod(
                service,
                "productFitsSlot",
                "Квест Сталкер",
                "Квест с актерами: как атмосфера и участие персонажей повлияли на вовлечение команды.",
                "квесты с актерами"
        );
        Boolean differentScenarioRejected = ReflectionTestUtils.invokeMethod(
                service,
                "productFitsSlot",
                "Квест Сталкер",
                "квесте в стиле Гарри Поттера: костюмы, декорации, задания, участие актеров.",
                "квесты с актерами"
        );

        assertThat(genericQuestFits).isTrue();
        assertThat(differentScenarioRejected).isFalse();
    }

    @Test
    void shortFallbackChoosesSingleConcreteOptionInsteadOfOrPhrase() {
        ReviewGenerationBrief brief = new ReviewGenerationBrief(
                "Небо",
                "Ставрополь",
                "",
                "local_service",
                List.of("фотоовал или портрет"),
                List.of(),
                List.of(),
                List.of(),
                List.of("Фотоовал или фото на стекле: почему выбрали этот вариант и как оценили качество изображения."),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("приемка результата")
        );

        String draft = ReflectionTestUtils.invokeMethod(
                service,
                "shortFallbackDraft",
                brief,
                "Фотоовал или фото на стекле: почему выбрали этот вариант и как оценили качество изображения.",
                List.of("фотоовал или фото на стекле", "качество изображения"),
                172151L
        );

        assertThat(draft).contains("фотоовал");
        assertThat(draft).doesNotContain("фотоовал или фото на стекле");
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localQuestBatchFallbackDoesNotRepeatSameScenarioRulesSentence() {
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
                                new ReputationBatchReviewDraftTarget(
                                        71L,
                                        "День рождения под ключ: как связка квеста, активной игры и чайной зоны помогла провести праздник.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        72L,
                                        "Квест с актерами: как атмосфера и участие персонажей повлияли на вовлечение команды.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        73L,
                                        "Лазертаг для детской компании: как активная игра помогла занять детей.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        74L,
                                        "Чайная зона после квеста: где спокойно посидеть с угощениями после игры.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        75L,
                                        "Хоррор-квест: как заранее объяснили ограничения и формат игры.",
                                        "",
                                        ""
                                )
                        )
                )
        );

        List<String> drafts = result.drafts().stream()
                .map(ReputationBatchReviewDraftItem::draft)
                .toList();

        assertThat(drafts).hasSize(5);
        assertThat(drafts)
                .filteredOn(draft -> draft.contains("По сценарию и правилам заранее объяснили ограничения"))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(drafts.stream()
                .map(draft -> draft.split("\\.")[0])
                .distinct()
                .count()).isGreaterThanOrEqualTo(4);
        assertThat(drafts)
                .anyMatch(draft -> draft.toLowerCase().contains("день рождения") || draft.toLowerCase().contains("праздник"))
                .anyMatch(draft -> draft.toLowerCase().contains("актер") || draft.toLowerCase().contains("актёр") || draft.toLowerCase().contains("атмосфер"))
                .anyMatch(draft -> draft.toLowerCase().contains("лазертаг") || draft.toLowerCase().contains("активная игра"))
                .anyMatch(draft -> draft.toLowerCase().contains("чай")
                        || draft.toLowerCase().contains("угощ"));
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localQuestFallbackHandlesNavigationAndFoodWithoutGenericMoment() {
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
                                new ReputationBatchReviewDraftTarget(
                                        81L,
                                        "Филиал и навигация: как найти место, где начинается игра и не потеряться перед стартом.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        82L,
                                        "Кейтеринг или своя еда: что можно принести на праздник и как не решать это в последний момент.",
                                        "",
                                        ""
                                )
                        )
                )
        );

        assertThat(result.drafts()).hasSize(2);
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft())
                .doesNotContain("Отдельно проговорили момент")
                .doesNotContain("Больше всего волновало: филиал")
                .doesNotContain("Больше всего волновало: кейтеринг"));
        assertThat(result.drafts().stream().map(ReputationBatchReviewDraftItem::draft).toList())
                .anyMatch(draft -> draft.toLowerCase().contains("найти")
                        || draft.toLowerCase().contains("дорог")
                        || draft.toLowerCase().contains("навигац"))
                .anyMatch(draft -> draft.toLowerCase().contains("еда")
                        || draft.toLowerCase().contains("угощ")
                        || draft.toLowerCase().contains("торт"));
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
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
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
                .anyMatch(draft -> draft.toLowerCase().contains("коммуникац") || draft.contains("скорость ответов"))
                .anyMatch(draft -> draft.contains("White Box"))
                .anyMatch(draft -> draft.toLowerCase().contains("работа по договору")
                        || draft.toLowerCase().contains("работе по договору"));
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void batchDropsGenericBusinessDescriptorThemeAndKeepsAcceptanceDomainSpecific() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithMonumentBatchIdeas());
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
                                new ReputationBatchReviewDraftTarget(41L, "\u200BКомпания по изготовлению памятнико.", "", ""),
                                new ReputationBatchReviewDraftTarget(42L, "\u200BКомпания по изготовлению памятнико.", "", ""),
                                new ReputationBatchReviewDraftTarget(43L, "\u200BКомпания по изготовлению памятнико.", "", ""),
                                new ReputationBatchReviewDraftTarget(44L, "\u200BКомпания по изготовлению памятнико.", "", ""),
                                new ReputationBatchReviewDraftTarget(45L, "\u200BКомпания по изготовлению памятнико.", "", "")
                        )
                )
        );
        assertThat(result.drafts()).hasSize(5);
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft()).doesNotContain("Небо"));

        ArgumentCaptor<ReviewGenerationBrief> briefCaptor = ArgumentCaptor.forClass(ReviewGenerationBrief.class);
        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), briefCaptor.capture(), slotsCaptor.capture());

        ReviewGenerationBrief brief = briefCaptor.getValue();
        assertThat(brief.businessType()).isEqualTo("local_service");
        assertThat(brief.services()).contains("изготовление памятников");
        assertThat(brief.services()).doesNotContain("приемка квартиры");
        assertThat(brief.reviewIdeas())
                .noneMatch(idea -> idea.contains("Компания по изготовлению памятнико"))
                .anyMatch(idea -> idea.contains("гранитного памятника"))
                .anyMatch(idea -> idea.contains("финальная приемка после установки"));

        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();
        assertThat(slots).hasSize(5);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.theme()).doesNotContain("Компания по изготовлению");
            assertThat(slot.theme()).doesNotContain("памятнико.");
            assertThat(slot.service()).doesNotContain("приемка квартиры");
            assertThat(slot.mustUse()).noneMatch(value -> value.contains("приемка квартиры"));
            assertThat(slot.mayUse()).noneMatch(value -> value.contains("приемка квартиры"));
        });
        assertThat(slots.stream().map(ReviewGenerationSlot::theme).distinct().count())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @Disabled("Локальный fallback временно отключен: выводим только черновики OpenAI.")
    void localBatchFallbackDoesNotEmitTaskMetaPhraseForMonumentTopics() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithMonumentBatchIdeas());
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
                                        51L,
                                        "Дистанционный заказ из другого города: как согласовывали макет, смету, фотоотчеты и установку.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        52L,
                                        "Работа менеджера сопровождения: как помогли с выбором, бюджетом, вопросами и сроками.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        53L,
                                        "Ремонт старого памятника: что было до обращения и какие работы помогли восстановить вид.",
                                        "",
                                        ""
                                )
                        )
                )
        );

        assertThat(result.drafts()).hasSize(3);
        assertThat(result.drafts()).allSatisfy(item -> assertThat(item.draft())
                .doesNotContain("обсудили задачу")
                .doesNotContain("обсудили тему")
                .doesNotContain("обозначили тему")
                .doesNotContain("Основную тему")
                .doesNotContain("дали понятные ориентиры:"));
        assertThat(result.drafts().stream().map(ReputationBatchReviewDraftItem::draft).toList())
                .anyMatch(draft -> draft.contains("другого города") || draft.contains("макет"))
                .anyMatch(draft -> draft.contains("выбору") || draft.contains("бюджету"))
                .anyMatch(draft -> draft.contains("старому памятнику") || draft.contains("восстанов"));
    }

    @Test
    void batchMonumentSlotsKeepMustUseFocusedOnEachCardTheme() {
        ReputationDeepReportJobEntity reportEntity = deepReportEntity(reportWithMonumentBatchIdeas());
        when(contentPackJobRepository.findByCompanyId(7L)).thenReturn(Optional.empty());
        when(deepReportJobRepository.findByCompanyIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(reportEntity));
        when(aiSingleReviewDraftFactory.createBatch(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        service.generateBatch(
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
                                        61L,
                                        "Опыт выбора гранитного памятника в офисе и сравнение образцов камня.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        62L,
                                        "Дистанционный заказ из другого города: как согласовывали макет, смету, фотоотчеты и установку.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        63L,
                                        "Работа менеджера сопровождения: как помогли с выбором, бюджетом, вопросами и сроками.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        64L,
                                        "Заказ под ключ: договор, предоплата, изготовление, доставка и финальная приемка после установки.",
                                        "",
                                        ""
                                ),
                                new ReputationBatchReviewDraftTarget(
                                        65L,
                                        "Благоустройство участка: цветник, отмостка, плиты, аккуратность работ после монтажа.",
                                        "",
                                        ""
                                )
                        )
                )
        );

        ArgumentCaptor<List<ReviewGenerationSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiSingleReviewDraftFactory).createBatch(any(), any(), any(), any(), any(), any(), slotsCaptor.capture());
        List<ReviewGenerationSlot> slots = slotsCaptor.getValue();

        assertThat(slots.stream().map(ReviewGenerationSlot::theme).toList())
                .contains(
                        "Опыт выбора гранитного памятника в офисе на Кулакова 11/1 и сравнение образцов камня.",
                        "Дистанционный заказ из другого города: как согласовывали макет, смету, фотоотчеты и установку.",
                        "Семейный/двойной памятник: как подбирали форму, надписи и общую композицию.",
                        "Заказ под ключ: договор, предоплата, изготовление, доставка и финальная приемка после установки.",
                        "Благоустройство участка: цветник, отмостка, плиты, аккуратность работ после монтажа."
                );
        assertThat(slots).allSatisfy(slot -> {
            if (slot.theme().contains("Дистанционный заказ")) {
                assertThat(slot.mustUse()).anyMatch(value -> value.contains("дистанционный заказ") || value.contains("макет"));
            }
            if (slot.theme().contains("Семейный/двойной")) {
                assertThat(slot.mustUse()).anyMatch(value -> value.contains("семейный памятник") || value.contains("форма, надписи и композиция"));
            }
            if (slot.theme().contains("Заказ под ключ")) {
                assertThat(slot.mustUse()).anyMatch(value -> value.contains("под ключ") || value.contains("договор"));
            }
        });
        assertThat(slots.stream().filter(slot -> slot.mustUse().stream()
                        .anyMatch(value -> value.toLowerCase().contains("образц")
                                || value.toLowerCase().contains("материал"))))
                .hasSize(1);
        assertThat(slots).allSatisfy(slot -> assertThat(slot.mustUse())
                .noneMatch(value -> value.contains("производство и офис")
                        || value.contains("можно приехать и посмотреть материалы")));
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

    private ReputationSingleReviewDraftResult singleOpenAiResult(String idea, String draft) {
        return new ReputationSingleReviewDraftResult(
                7L,
                "Skr Service",
                null,
                null,
                "openai",
                "gpt-5.5",
                idea,
                "живой",
                draft,
                List.of(idea),
                List.of(),
                new ReviewSafetyReport(true, 0, List.of(), List.of()),
                LocalDateTime.now()
        );
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

    private DeepCompanyResearchReport reportWithMonumentBatchIdeas() {
        return new DeepCompanyResearchReport(
                7L,
                "Небо",
                "Ставрополь",
                "openai",
                "gpt-5.5",
                "resp-7",
                "Компания занимается изготовлением памятников, подбором гранита, договором и установкой.",
                List.of(
                        new DeepCompanyResearchReport.Section(
                                "Ровно 10 идей для будущих честных отзывов",
                                """
                                        1. Опыт выбора гранитного памятника в офисе на Кулакова 11/1 и сравнение образцов камня.
                                        2. Дистанционный заказ из другого города: как согласовывали макет, смету, фотоотчеты и установку.
                                        3. Семейный/двойной памятник: как подбирали форму, надписи и общую композицию.
                                        4. Заказ под ключ: договор, предоплата, изготовление, доставка и финальная приемка после установки.
                                        5. Благоустройство участка: цветник, отмостка, плиты, аккуратность работ после монтажа.
                                        6. \u200BКомпания по изготовлению памятнико.
                                        """
                        ),
                        new DeepCompanyResearchReport.Section(
                                "Услуги и удобства",
                                """
                                        - изготовление памятников, гранитные памятники, благоустройство участка.
                                        - производство и офис в Ставрополе, можно приехать и посмотреть материалы и выставку.
                                        - парковка, доступность для пожилых, зона ожидания, туалет, Wi-Fi, терминал, QR.
                                        - договор, смета, макет, доставка и установка.
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
