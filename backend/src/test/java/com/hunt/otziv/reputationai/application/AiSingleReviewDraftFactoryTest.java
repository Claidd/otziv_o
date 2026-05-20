package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftItem;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReviewGenerationBrief;
import com.hunt.otziv.reputationai.domain.ReviewGenerationSlot;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiProvider;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSingleReviewDraftFactoryTest {

    private static final List<String> AUTO_MODELS = List.of(
            "Mazda MPV", "Toyota Corolla", "Toyota Camry", "Toyota RAV4", "Nissan X-Trail",
            "Nissan Qashqai", "Mitsubishi Outlander", "Honda CR-V", "Honda Stepwgn",
            "Subaru Forester", "Kia Rio", "Hyundai Solaris", "Lada Vesta", "Volkswagen Polo"
    );

    @Mock
    private OpenAiProvider openAiProvider;

    private ObjectMapper objectMapper;
    private AiSingleReviewDraftFactory factory;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        factory = new AiSingleReviewDraftFactory(
                openAiProvider,
                objectMapper,
                new ReviewSafetyService()
        );
    }

    @Test
    void batchPromptDoesNotInventAutoModelsFromPreviousDrafts() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse(batchResponse(), "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172137L, "подготовке авто к дальней поездке", "подготовка авто к дальней поездке", "Honda CR-V"),
                autoSlot(172138L, "повторном обращении после ремонта ходовой", "ремонт ходовой", "Honda Stepwgn"),
                autoSlot(172139L, "подборе запчастей", "подбор запчастей", "Subaru Forester"),
                autoSlot(172140L, "первичной диагностике", "первичная диагностика", "Kia Rio"),
                autoSlot(172141L, "ремонте стартера", "ремонт стартера", "Hyundai Solaris")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of()
                ),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        JsonNode promptSlots = payload.path("reviewSlots");
        assertThat(promptSlots).hasSize(5);
        assertThat(payload.path("batchSelectors").path("variationNonce").asText()).isNotBlank();
        assertThat(payload.path("batchSelectors").path("shortReviewCadence").asText())
                .contains("каждая 2-3 карточка");

        for (int index = 0; index < promptSlots.size(); index++) {
            JsonNode promptSlot = promptSlots.get(index);
            JsonNode exampleDetails = promptSlot.path("exampleDetails");

            assertThat(promptSlot.path("narrativeMode").asText()).isNotBlank();
            assertThat(promptSlot.path("narrativeInstruction").asText()).isNotBlank();
            assertThat(promptSlot.path("detailBudget").asText()).isNotBlank();
            assertThat(promptSlot.path("processVerbPolicy").asText()).isNotBlank();
            assertThat(exampleDetails.isArray()).isTrue();
            assertThat(exampleDetails).isNotEmpty();
            assertThat(firstAutoModel(exampleDetails)).isBlank();
            assertThat(exampleDetailTexts(exampleDetails))
                    .anyMatch(value -> normalized(value).contains("запчаст")
                            || normalized(value).contains("диагност")
                            || normalized(value).contains("ходов")
                            || normalized(value).contains("стартер")
                            || normalized(value).contains("масл")
                            || normalized(value).contains("тормоз")
                            || normalized(value).contains("стойк")
                            || normalized(value).contains("шаров")
                            || normalized(value).contains("рулев")
                            || normalized(value).contains("ступич")
                            || normalized(value).contains("генератор")
                            || normalized(value).contains("светфар")
                            || normalized(value).contains("давление"));
        }
        assertThat(promptSlots.get(1).path("requiredDepth").asText()).startsWith("light:");
        assertThat(promptSlots.get(1).path("lengthInstruction").asText()).contains("1 предложение");
        assertThat(promptSlots.get(4).path("requiredDepth").asText()).startsWith("light:");
        assertThat(promptSlots.get(4).path("lengthInstruction").asText()).contains("1 предложение");
        assertThat(promptSlots.get(0).path("requiredDepth").asText()).doesNotStartWith("light:");
        assertThat(promptSlots.get(2).path("requiredDepth").asText()).doesNotStartWith("light:");
        assertThat(promptSlots.get(3).path("requiredDepth").asText()).doesNotStartWith("light:");
        assertThat(exampleDetailTexts(payload.path("batchRules")))
                .anyMatch(rule -> rule.contains("narrativeMode") && rule.contains("разные жанры"));
        assertThat(exampleDetailTexts(payload.path("batchRules")))
                .anyMatch(rule -> rule.contains("показали") && rule.contains("максимум для одной карточки"));
    }

    @Test
    void batchPromptUsesWideNarrativeModePool() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);

        List<ReviewGenerationSlot> slots = new java.util.ArrayList<>();
        for (long id = 172200L; id < 172230L; id++) {
            slots.add(autoSlot(id, "первичной диагностике", "первичная диагностика", "Honda CR-V"));
        }

        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse(batchResponseForSlots(slots), "openai", 10, 20));

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        java.util.Set<String> narrativeModes = new java.util.LinkedHashSet<>();
        payload.path("reviewSlots").forEach(slot -> narrativeModes.add(slot.path("narrativeMode").asText()));

        assertThat(narrativeModes).hasSizeGreaterThanOrEqualTo(20);
        assertThat(narrativeModes)
                .contains("micro_reaction", "blunt_positive", "routine_customer", "story_with_details",
                        "small_minus_ok", "before_after", "voice_note_style");
    }

    @Test
    void batchDoesNotCallSeparateWebWritingGuideToKeepDraftGenerationCheap() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse(batchResponse(), "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172137L, "подготовке авто к дальней поездке", "подготовка авто к дальней поездке", "Honda CR-V"),
                autoSlot(172138L, "повторном обращении после ремонта ходовой", "ремонт ходовой", "Honda Stepwgn")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                slots
        );

        assertThat(result).isPresent();

        verify(openAiProvider, org.mockito.Mockito.never()).generateBatchReviewWritingGuide(any(), any());

        ArgumentCaptor<AiRequest> draftCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(draftCaptor.capture(), eq("quality"));
        JsonNode payload = objectMapper.readTree(jsonPayload(draftCaptor.getValue().userPrompt()));

        assertThat(payload.has("writingGuide")).isFalse();
        assertThat(exampleDetailTexts(payload.path("batchRules")))
                .anyMatch(rule -> rule.contains("writingGuide") && rule.contains("подтверждёнными фактами"));
    }

    @Test
    void batchDropsEntertainmentDraftWithPromptMetaPhrases() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172144,"draft":"Понравилось, что разговор был предметный. Сначала обсудили задачу: конкретном филиале. Потом отдельно уточнили хоррор-квесты.","sourceFacts":["хоррор-квесты"],"clientMustConfirm":["какой сценарий выбрали"],"safetyNotes":["проверить сценарий"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                entertainmentPack(),
                batchRequest(),
                entertainmentBrief(),
                List.of(entertainmentSlot())
        );

        assertThat(result).isEmpty();
    }

    @Test
    void batchKeepsEntertainmentDraftWithConcreteQuestNameAndNaturalFocus() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172144,"draft":"После записи на «Экзорцизм» заранее уточнили, где вход и когда лучше подъехать. На месте успели спокойно пройти инструктаж по хоррор-квесту с актерами, поэтому команда не начинала игру в спешке.","sourceFacts":["Экзорцизм","квесты с актерами"],"clientMustConfirm":["какой сценарий выбрали"],"safetyNotes":["проверить сценарий"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                entertainmentPack(),
                batchRequest(),
                entertainmentBrief(),
                List.of(entertainmentSlot())
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172144L);
    }

    @Test
    void batchEmptyTransportResponseReturnsOpenAiResultWithSafetyNote() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse(
                        "",
                        "openai",
                        0,
                        0,
                        "Запрос OpenAI оборвался на сетевом уровне: chunked transfer encoding, state: READING_LENGTH"
                ));

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                List.of(autoSlot(172137L, "первичная диагностика", "первичная диагностика", "Honda CR-V"))
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().provider()).isEqualTo("openai");
        assertThat(result.orElseThrow().drafts()).isEmpty();
        assertThat(result.orElseThrow().safetyNotes())
                .anyMatch(note -> note.contains("оборвался") && note.contains("chunked transfer encoding"));
    }

    @Test
    void batchDropsAiDraftWithAutoModelAbsentFromSlotFacts() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse(batchResponseWithUnprovidedModel(), "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172137L, "подборе запчастей", "подбор запчастей", "Honda CR-V"),
                autoSlot(172138L, "первичной диагностике", "первичная диагностика", "Honda Stepwgn"),
                autoSlot(172139L, "подготовке авто к дальней поездке", "подготовка авто к дальней поездке", "Subaru Forester"),
                autoSlot(172140L, "ремонте стартера", "ремонт стартера", "Kia Rio"),
                autoSlot(172141L, "ремонте ходовой", "ремонт ходовой", "Hyundai Solaris")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of()
                ),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172138L, 172139L, 172140L, 172141L);
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::draft)
                .noneMatch(draft -> normalized(draft).contains(normalized("Toyota Corolla")));
    }

    @Test
    void batchDoesNotRunAutoModelGuardForMonumentSlot() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172151,"draft":"Попросил объяснить простыми словами, как семейный памятник не сделать похожим на две отдельные плиты. Пока ждали в зоне, я поправлял воротник поло и заново сверял, как форма, надписи и композиция смотрятся вместе.","sourceFacts":["семейный памятник","форма, надписи и композиция"],"clientMustConfirm":["какая услуга была у клиента"],"safetyNotes":["проверить бытовую деталь про ожидание"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        ReviewGenerationSlot slot = new ReviewGenerationSlot(
                172151L,
                "Семейный/двойной памятник: как подбирали форму, надписи и общую композицию.",
                "семейный памятник",
                "",
                "",
                "",
                "",
                "деловой без эмоций",
                "3 предложения без рекламных оценок",
                "начать с просьбы объяснить простыми словами",
                List.of("семейный памятник", "форма, надписи и композиция"),
                List.of("семейный памятник", "Удобства"),
                List.of("какая услуга и объект были у клиента"),
                ""
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                monumentBrief(),
                List.of(slot)
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172151L);
    }

    @Test
    void batchPromptKeepsIdeaNuanceAfterColonAsExperienceFocus() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172149,"draft":"Фотоовал выбирали после сравнения вариантов и качества изображения.","sourceFacts":["фотоовал"],"clientMustConfirm":["какой вариант выбрали"],"safetyNotes":["проверить вариант"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        ReviewGenerationSlot slot = new ReviewGenerationSlot(
                172149L,
                "Фотоовал или фото на стекле: почему выбрали этот вариант и как оценили качество изображения.",
                "изготовление памятников",
                "фотоовал",
                "",
                "",
                "",
                "спокойный практичный",
                "3 предложения",
                "начать с личной причины",
                List.of("фотоовал"),
                List.of("фотоовал", "изготовление памятников"),
                List.of("какой вариант фото был у клиента"),
                ""
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                new ReputationBatchReviewDraftRequest(
                        null,
                        null,
                        "живой",
                        "разные клиенты",
                        "без смайлов",
                        "",
                        "mixed",
                        "quality",
                        List.of()
                ),
                monumentBrief(),
                List.of(slot)
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        JsonNode promptSlot = payload.path("reviewSlots").get(0);
        assertThat(promptSlot.path("theme").asText()).contains("Фотоовал или фото на стекле");
        assertThat(exampleDetailTexts(promptSlot.path("experienceFocus")))
                .containsExactly("выбор варианта и оценка качества изображения");
    }

    @Test
    void batchDropsRepeatedGenericMonumentSampleDetailWhenSlotDoesNotRequireIt() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172149,"draft":"Гранитный памятник выбирали по образцам камня, так было проще понять общий вид. После этого спокойно согласовали оформление.","sourceFacts":["выбор гранита и образцов"],"clientMustConfirm":["какой камень выбрали"],"safetyNotes":["проверить материал"]},
                          {"reviewId":172150,"draft":"Дистанционный заказ памятника начали с макета и сметы, но снова поехали посмотреть образцы камня. После этого отдельно согласовали установку.","sourceFacts":["дистанционный заказ памятника","макет и смета"],"clientMustConfirm":["как согласовывали макет"],"safetyNotes":["проверить дистанционный заказ"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                new ReviewGenerationSlot(
                        172149L,
                        "Опыт выбора гранитного памятника в офисе и сравнение образцов камня.",
                        "выбор гранита и образцов",
                        "",
                        "",
                        "",
                        "",
                        "спокойный практичный",
                        "3 предложения",
                        "начать с услуги",
                        List.of("выбор гранита и образцов"),
                        List.of("изготовление памятников"),
                        List.of("какой камень был у клиента"),
                        ""
                ),
                new ReviewGenerationSlot(
                        172150L,
                        "Дистанционный заказ из другого города: как согласовывали макет, смету, фотоотчеты и установку.",
                        "дистанционный заказ памятника",
                        "",
                        "",
                        "",
                        "",
                        "спокойный практичный",
                        "3 предложения",
                        "начать с услуги",
                        List.of("дистанционный заказ памятника", "макет и смета"),
                        List.of("изготовление памятников"),
                        List.of("как согласовывали макет"),
                        ""
                )
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                monumentBrief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172149L);
    }

    @Test
    void batchPromptTurnsQuestionChecklistIntoNaturalExperienceFocus() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172139,"draft":"По диагностике подвески прислали фото, показали старую деталь и согласовали дополнительные работы.","sourceFacts":["диагностика подвески"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        ReviewGenerationSlot slot = new ReviewGenerationSlot(
                172139L,
                "прозрачности ремонта: присылали ли фото/видео, показывали ли старую деталь, согласовывали ли дополнительные работы.",
                "диагностика подвески",
                "",
                "",
                "",
                "",
                "деловой",
                "3 предложения",
                "начать с причины",
                List.of("диагностика подвески"),
                List.of("диагностика подвески", "Запчасти в наличии и под заказ"),
                List.of("какая машина была у клиента"),
                ""
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                List.of(slot)
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        JsonNode promptSlot = payload.path("reviewSlots").get(0);
        assertThat(exampleDetailTexts(promptSlot.path("experienceFocus")))
                .containsExactly("фото или видео по ремонту", "показ старой детали", "согласование дополнительных работ");
    }

    @Test
    void batchPromptDoesNotTurnOptionListIntoConcreteObjectFocus() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172156,"draft":"На большой площади заранее обсудили смету и порядок работ.","sourceFacts":["замер и смета"],"clientMustConfirm":["какой объект был у клиента"],"safetyNotes":["проверить объект"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        ReviewGenerationSlot slot = new ReviewGenerationSlot(
                172156L,
                "Работа на крупной площади: офис, коттедж, склад или несколько квартир — как соблюдались сроки и смета.",
                "замер и смета",
                "",
                "",
                "",
                "",
                "спокойный",
                "2 предложения",
                "начать сразу с услуги",
                List.of("замер и смета"),
                List.of("замер и смета", "Коммуникация с менеджером/прорабом"),
                List.of("какой объект был у клиента"),
                ""
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                constructionBrief(),
                List.of(slot)
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        JsonNode promptSlot = payload.path("reviewSlots").get(0);
        assertThat(exampleDetailTexts(promptSlot.path("experienceFocus")))
                .containsExactly("соблюдение сроков и сметы на крупной площади");
    }

    @Test
    void batchDropsAiDraftThatCopiesExperienceFocusQuestions() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172139,"draft":"Отдельно спросил: присылали ли фото/видео, показывали ли старую деталь и согласовывали ли дополнительные работы.","sourceFacts":["диагностика подвески"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172140,"draft":"По ремонту стартера приняли быстро, проверили реле и спокойно объяснили результат.","sourceFacts":["ремонт стартера"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172139L, "прозрачности ремонта: присылали ли фото/видео, показывали ли старую деталь, согласовывали ли дополнительные работы.", "диагностика подвески", "Honda CR-V"),
                autoSlot(172140L, "ремонте стартера: была ли срочность, как быстро приняли машину, как объяснили результат.", "ремонт стартера", "Honda Stepwgn")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172140L);
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::draft)
                .noneMatch(draft -> draft.contains("присылали ли") || draft.contains("показывали ли"));
    }

    @Test
    void batchPromptDropsBadPreviousDraftInsteadOfFeedingItBack() throws Exception {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172139,"draft":"Диагностику подвески объяснили по фото и согласовали работы заранее.","sourceFacts":["диагностика подвески"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        ReviewGenerationSlot slot = new ReviewGenerationSlot(
                172139L,
                "прозрачности ремонта: присылали ли фото/видео, показывали ли старую деталь, согласовывали ли дополнительные работы.",
                "диагностика подвески",
                "",
                "",
                "",
                "",
                "деловой",
                "3 предложения",
                "начать с причины",
                List.of("диагностика подвески"),
                List.of("диагностика подвески", "Запчасти в наличии и под заказ"),
                List.of("какая машина была у клиента"),
                "Отдельно просил объяснить детали: присылали ли фото/видео, показывали ли старую деталь, согласовывали ли дополнительные работы."
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                List.of(slot)
        );

        assertThat(result).isPresent();
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(openAiProvider).generateBatchReviewDraft(requestCaptor.capture(), eq("quality"));

        JsonNode payload = objectMapper.readTree(jsonPayload(requestCaptor.getValue().userPrompt()));
        assertThat(payload.path("reviewSlots").get(0).path("previousDraftToAvoid").asText()).isBlank();
    }

    @Test
    void batchDropsAiDraftWithBrokenCaseFromRawTheme() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172149,"draft":"По семейный/двойной памятник дали понятные ориентиры.","sourceFacts":["семейный памятник"],"clientMustConfirm":["какой памятник был у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172150,"draft":"По семейному памятнику спокойно объяснили форму и общий вид.","sourceFacts":["семейный памятник"],"clientMustConfirm":["какой памятник был у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                new ReviewGenerationSlot(
                        172149L,
                        "Семейный/двойной памятник: как подбирали форму, надписи и общую композицию.",
                        "изготовление памятников",
                        "",
                        "",
                        "",
                        "",
                        "спокойный",
                        "2 предложения",
                        "начать с результата",
                        List.of("изготовление памятников"),
                        List.of("изготовление памятников"),
                        List.of("какой памятник был у клиента"),
                        ""
                ),
                new ReviewGenerationSlot(
                        172150L,
                        "Семейный/двойной памятник: как подбирали форму, надписи и общую композицию.",
                        "изготовление памятников",
                        "",
                        "",
                        "",
                        "",
                        "спокойный",
                        "2 предложения",
                        "начать с результата",
                        List.of("изготовление памятников"),
                        List.of("изготовление памятников"),
                        List.of("какой памятник был у клиента"),
                        ""
                )
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                monumentBrief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172150L);
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::draft)
                .noneMatch(draft -> draft.contains("По семейный/двойной"));
    }

    @Test
    void batchDropsAiDraftThatMissesRequiredSlotIntent() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172155,"draft":"По механизированной штукатурке заранее уточнил график и общался с менеджером.","sourceFacts":["механизированная штукатурка"],"clientMustConfirm":["какая услуга была у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172156,"draft":"Полусухую стяжку сделали аккуратно в рамках White Box, после работ объект оставили без лишней грязи.","sourceFacts":["полусухая стяжка пола","White Box"],"clientMustConfirm":["какая услуга была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                new ReviewGenerationSlot(
                        172155L,
                        "Полусухая стяжка пола в квартире: сроки выполнения, чистота объекта и когда получилось продолжить ремонт.",
                        "полусухая стяжка пола",
                        "",
                        "",
                        "",
                        "",
                        "разговорный",
                        "3 предложения",
                        "мини-история",
                        List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                        List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                        List.of("какая услуга была у клиента"),
                        ""
                ),
                new ReviewGenerationSlot(
                        172156L,
                        "Полусухая стяжка пола в квартире: сроки выполнения, чистота объекта и когда получилось продолжить ремонт.",
                        "полусухая стяжка пола",
                        "",
                        "",
                        "",
                        "",
                        "разговорный",
                        "3 предложения",
                        "мини-история",
                        List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                        List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                        List.of("какая услуга была у клиента"),
                        ""
                )
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                constructionBrief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172156L);
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::draft)
                .noneMatch(draft -> draft.contains("механизированной штукатурке"));
    }

    @Test
    void batchDropsAiDraftMissingOneOfRequiredMustCoverItems() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172155,"draft":"White Box брали, чтобы не разносить перегородки и подготовку стен по разным мастерам.","sourceFacts":["White Box"],"clientMustConfirm":["какая услуга была у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172156,"draft":"Полусухую стяжку пола делали в рамках White Box, по этапам заранее объяснили порядок работ.","sourceFacts":["полусухая стяжка пола","White Box"],"clientMustConfirm":["какая услуга была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                constructionSlot(172155L),
                constructionSlot(172156L)
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                constructionBrief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172156L);
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::draft)
                .noneMatch(draft -> draft.startsWith("White Box брали"));
    }

    @Test
    void batchDropsAiDraftWithAwkwardPhotoPartPhrase() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172139,"draft":"Фото стойкам стабилизатора сразу сняли часть вопросов.","sourceFacts":["диагностика подвески"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172140,"draft":"По стойкам стабилизатора показали фото и заранее согласовали дополнительные работы.","sourceFacts":["диагностика подвески"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172139L, "прозрачности ремонта: фото или видео по ремонту, показ старой детали.", "диагностика подвески", "Honda CR-V"),
                autoSlot(172140L, "прозрачности ремонта: фото или видео по ремонту, показ старой детали.", "диагностика подвески", "Honda Stepwgn")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172140L);
    }

    @Test
    void batchDropsAiDraftWithConflictingAutoSubsystem() {
        when(openAiProvider.isAvailable()).thenReturn(true);
        when(openAiProvider.generateBatchReviewDraft(any(), eq("quality")))
                .thenReturn(new AiResponse("""
                        {"drafts":[
                          {"reviewId":172138,"draft":"Ремонт ходовой начали с диагностики, потому что был стук на неровностях. Не просто сказали менять, а показали стартер и отдельно согласовали запчасти.","sourceFacts":["ремонт ходовой"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                          {"reviewId":172139,"draft":"Ремонт ходовой начали с диагностики, потому что был стук на неровностях. Показали стойки стабилизатора и отдельно согласовали запчасти до работ.","sourceFacts":["ремонт ходовой"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                        ],"safetyNotes":[]}
                        """, "openai", 10, 20));

        List<ReviewGenerationSlot> slots = List.of(
                autoSlot(172138L, "прозрачности ремонта ходовой: показ старой детали и согласование дополнительных работ.", "ремонт ходовой", "Honda CR-V"),
                autoSlot(172139L, "прозрачности ремонта ходовой: показ старой детали и согласование дополнительных работ.", "ремонт ходовой", "Honda Stepwgn")
        );

        Optional<ReputationBatchReviewDraftResult> result = factory.createBatch(
                7L,
                11L,
                22L,
                pack(),
                batchRequest(),
                brief(),
                slots
        );

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().drafts())
                .extracting(ReputationBatchReviewDraftItem::reviewId)
                .containsExactly(172139L);
    }

    private ReviewGenerationBrief brief() {
        return new ReviewGenerationBrief(
                "Skr Service",
                "Новосибирск",
                "",
                "auto_service",
                List.of("первичная диагностика", "ремонт ходовой", "подбор запчастей", "ремонт стартера", "подготовка авто к дальней поездке"),
                List.of(),
                List.of(),
                List.of(),
                List.of("подготовке авто к дальней поездке", "повторном обращении", "подборе запчастей", "первичной диагностике", "ремонте стартера"),
                List.of("Восточный МЖК, Октябрьский район"),
                List.of(),
                List.of(),
                List.of(),
                List.of("Запчасти в наличии и под заказ"),
                List.of("диагностика", "ремонт", "плановое ТО", "подбор запчастей", "подготовка к поездке")
        );
    }

    private ReputationBatchReviewDraftRequest batchRequest() {
        return new ReputationBatchReviewDraftRequest(
                null,
                null,
                "живой",
                "разные клиенты",
                "без смайлов",
                "",
                "mixed",
                "quality",
                List.of()
        );
    }

    private ReviewGenerationBrief monumentBrief() {
        return new ReviewGenerationBrief(
                "Небо",
                "Ставрополь",
                "",
                "local_service",
                List.of("изготовление памятников"),
                List.of("фотоовал", "фото на стекле"),
                List.of(),
                List.of(),
                List.of("Фотоовал или фото на стекле: почему выбрали этот вариант и как оценили качество изображения."),
                List.of("офис в Ставрополе"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("первичное обращение", "смета", "выполнение работ")
        );
    }

    private ReviewGenerationBrief constructionBrief() {
        return new ReviewGenerationBrief(
                "Штукатур Вл",
                "Владивосток",
                "",
                "local_service",
                List.of("механизированная штукатурка", "замер и смета", "White Box"),
                List.of(),
                List.of(),
                List.of(),
                List.of("Работа на крупной площади: офис, коттедж, склад или несколько квартир — как соблюдались сроки и смета."),
                List.of(),
                List.of("Коммуникация с менеджером/прорабом"),
                List.of(),
                List.of(),
                List.of(),
                List.of("замер или консультация", "смета", "выполнение работ")
        );
    }

    private ReviewGenerationBrief entertainmentBrief() {
        return new ReviewGenerationBrief(
                "Iquest",
                "Ангарск",
                "Квест",
                "entertainment",
                List.of("квесты с актерами", "хоррор-квесты", "квест «Экзорцизм»", "детские квесты"),
                List.of("Экзорцизм", "Оно", "Тюрьма"),
                List.of(),
                List.of("чайная зона для гостей"),
                List.of("конкретном филиале: как нашли вход, было ли понятно по вывеске, удобно ли с парковкой и ожиданием."),
                List.of("2 часа 40 минут"),
                List.of(),
                List.of("чайная зона, настольные игры, ожидание"),
                List.of(),
                List.of("возраст, актеры, режим Хардкор 18+"),
                List.of("день рождения", "квест", "подбор сценария")
        );
    }

    private ReputationContentPack entertainmentPack() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                7L,
                "Iquest",
                "Ангарск",
                "Квест",
                "Развлечения",
                "",
                "",
                List.of("Экзорцизм", "Оно", "Тюрьма"),
                List.of("чайная зона для гостей"),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://iquest.example", "Квесты")),
                "local",
                false,
                List.of(),
                0,
                0,
                List.of(),
                LocalDateTime.now()
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                "Квесты",
                "Квест",
                List.of("квесты с актерами", "хоррор-квесты"),
                List.of("чайная зона"),
                List.of("Экзорцизм", "Оно", "Тюрьма"),
                List.of(),
                List.of()
        );
        return new ReputationContentPack(
                snapshot,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ReviewGenerationSlot entertainmentSlot() {
        return new ReviewGenerationSlot(
                172144L,
                "конкретном филиале: как нашли вход, было ли понятно по вывеске, удобно ли с парковкой и ожиданием.",
                "хоррор-квесты",
                "Экзорцизм",
                "",
                "",
                "",
                "разговорный, немного неровный",
                "5-6 предложений, мини-история",
                "начать с результата после обращения",
                List.of("хоррор-квесты", "Экзорцизм", "квесты с актерами"),
                List.of("хоррор-квесты", "Экзорцизм", "квесты с актерами",
                        "квесты, лазертаг, нерф, прятки, картинг, пакеты ДР, доплаты, сопровождающие, чайная зона, питание, фотогр"),
                List.of("какой сценарий или формат выбрали"),
                ""
        );
    }

    private ReviewGenerationSlot constructionSlot(Long reviewId) {
        return new ReviewGenerationSlot(
                reviewId,
                "Полусухая стяжка пола в квартире: сроки выполнения, чистота объекта и когда получилось продолжить ремонт.",
                "полусухая стяжка пола",
                "",
                "",
                "",
                "",
                "разговорный",
                "3 предложения",
                "мини-история",
                List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                List.of("полусухая стяжка пола", "White Box с привлечением профильных мастеров"),
                List.of("какая услуга была у клиента"),
                ""
        );
    }

    private ReviewGenerationSlot autoSlot(Long reviewId, String theme, String service, String previousModel) {
        return new ReviewGenerationSlot(
                reviewId,
                theme,
                service,
                "",
                "",
                "",
                "Запчасти в наличии и под заказ",
                "спокойный практичный",
                "3-4 предложения",
                "начать не так, как previousDraft",
                List.of(service),
                List.of(service, "Запчасти в наличии и под заказ", "Восточный МЖК, Октябрьский район"),
                List.of("какая машина была у клиента", "какие детали или запчасти реально меняли"),
                "Предыдущий вариант уже использовал " + previousModel + " и похожие детали."
        );
    }

    private ReputationContentPack pack() {
        ResearchSnapshot snapshot = new ResearchSnapshot(
                7L,
                "Skr Service",
                "Новосибирск",
                "",
                "Автосервис",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CompanySource("website", "Сайт", "https://skr.example", "Услуги")),
                "local",
                false,
                List.of(),
                0,
                0,
                List.of(),
                LocalDateTime.now()
        );
        CompanyAiProfile profile = new CompanyAiProfile(
                "Автосервис",
                "Автосервис",
                List.of("первичная диагностика", "ремонт ходовой", "подбор запчастей"),
                List.of("запчасти в наличии и под заказ"),
                List.of(),
                List.of(),
                List.of()
        );
        return new ReputationContentPack(
                snapshot,
                profile,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private String batchResponse() {
        return """
                {"drafts":[
                  {"reviewId":172137,"draft":"Перед поездкой попросил проверить основные узлы и расходники.","sourceFacts":["подготовка авто"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172138,"draft":"После ремонта ходовой машина стала ехать тише, поэтому вернулся повторно.","sourceFacts":["ремонт ходовой"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172139,"draft":"С запчастями стало понятно, что есть сразу, а что надо заказывать.","sourceFacts":["подбор запчастей"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172140,"draft":"На диагностике объяснили причину звука без лишних обещаний.","sourceFacts":["первичная диагностика"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172141,"draft":"С ремонтом стартера разобрались по делу и объяснили результат.","sourceFacts":["ремонт стартера"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                ],"safetyNotes":["проверить перед публикацией"]}
                """;
    }

    private String batchResponseWithUnprovidedModel() {
        return """
                {"drafts":[
                  {"reviewId":172137,"draft":"На Toyota Corolla подбор запчастей объяснили по наличию и заказу.","sourceFacts":["подбор запчастей"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172138,"draft":"На первичной диагностике объяснили причину звука без лишних обещаний.","sourceFacts":["первичная диагностика"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172139,"draft":"Перед поездкой попросил проверить основные узлы и расходники.","sourceFacts":["подготовка авто"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172140,"draft":"С ремонтом стартера разобрались по делу и объяснили результат.","sourceFacts":["ремонт стартера"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]},
                  {"reviewId":172141,"draft":"После ремонта ходовой машина стала ехать тише.","sourceFacts":["ремонт ходовой"],"clientMustConfirm":["какая машина была у клиента"],"safetyNotes":["проверить детали"]}
                ],"safetyNotes":["проверить перед публикацией"]}
                """;
    }

    private String batchResponseForSlots(List<ReviewGenerationSlot> slots) {
        StringBuilder builder = new StringBuilder("{\"drafts\":[");
        for (int index = 0; index < slots.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append("{\"reviewId\":")
                    .append(slots.get(index).reviewId())
                    .append(",\"draft\":\"На первичной диагностике стало понятнее, что делать с машиной дальше.\",")
                    .append("\"sourceFacts\":[\"первичная диагностика\"],")
                    .append("\"clientMustConfirm\":[\"какая машина была у клиента\"],")
                    .append("\"safetyNotes\":[\"проверить детали\"]}");
        }
        return builder.append("],\"safetyNotes\":[\"проверить перед публикацией\"]}").toString();
    }

    private String batchWritingGuideResponse() {
        return """
                {
                  "categoryLanguage":["для автосервиса естественно начинать с симптома или задачи, а не с названия сервиса"],
                  "termHints":["ходовая часть","наличие запчастей","старые детали"],
                  "ideaExpansion":[
                    {"reviewId":172137,"angles":["перед поездкой важно показать, что именно проверили и почему стало спокойнее"],"decisionCriteria":["понятные рекомендации без нагнетания"],"naturalDetails":["свет, жидкости, шины, расходники"],"avoidClaims":["не писать точные сроки и цены"]},
                    {"reviewId":172138,"angles":["повторное обращение лучше раскрывать через прошлый опыт и новый повод"],"decisionCriteria":["предсказуемость объяснений"],"naturalDetails":["стук, стойки, наличие деталей"],"avoidClaims":["не называть модель авто без входных данных"]}
                  ],
                  "diversityWarnings":["не начинай все отзывы с заехал или обратился"],
                  "safetyNotes":["справочник не является подтверждением фактов клиента"]
                }
                """;
    }

    private String jsonPayload(String userPrompt) {
        int start = userPrompt.indexOf('{');
        return start < 0 ? "{}" : userPrompt.substring(start);
    }

    private String firstAutoModel(JsonNode values) {
        if (!values.isArray()) {
            return "";
        }
        for (JsonNode value : values) {
            String text = value.asText("");
            if (AUTO_MODELS.contains(text)) {
                return text;
            }
        }
        return "";
    }

    private List<String> exampleDetailTexts(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(value.asText("")));
        return result;
    }

    private String normalized(String value) {
        return value == null
                ? ""
                : value.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
