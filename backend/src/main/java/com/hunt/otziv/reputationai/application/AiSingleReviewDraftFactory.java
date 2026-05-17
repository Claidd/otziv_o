package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftRequest;
import com.hunt.otziv.reputationai.api.dto.ReputationBatchReviewDraftTarget;
import com.hunt.otziv.reputationai.api.dto.ReputationSingleReviewDraftRequest;
import com.hunt.otziv.reputationai.config.ContentPackProfile;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftItem;
import com.hunt.otziv.reputationai.domain.ReputationBatchReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ReputationSingleReviewDraftResult;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.domain.ReviewGenerationBrief;
import com.hunt.otziv.reputationai.domain.ReviewGenerationSlot;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiSingleReviewDraftFactory {

    private static final List<String> STRUCTURE_VARIANTS = List.of(
            "очень коротко, 1-2 живых предложения, без вступления",
            "сначала причина обращения, потом одна конкретная деталь и спокойный итог",
            "сначала сомнение или опасение клиента, потом что прояснилось",
            "сначала результат, потом коротко почему обратился",
            "как маленькая бытовая история: повод, одна деталь общения, итог",
            "чуть придирчиво: что насторожило, что объяснили, почему в итоге нормально",
            "деловой отзыв без эмоций: задача, что проверили или сделали, вывод",
            "разговорно и неровно: как человек написал между делом",
            "не по хронологии: начать с вывода, потом дать одну причину",
            "как заметка после визита: две конкретные детали без общего вступления",
            "от первого лица с бытовым контекстом, но без пересказа всей услуги"
    );

    private static final List<String> VOICE_VARIANTS = List.of(
            "практичный человек, которому важен понятный результат",
            "осторожный клиент, который заранее перепроверяет детали",
            "клиент немного торопился и не хотел лишних разговоров",
            "человек пишет спокойно, без восторга и рекламы",
            "клиент с бытовой личной причиной, но без драматизации",
            "постоянный клиент сравнивает с прошлым опытом",
            "человек не очень любит писать отзывы, поэтому формулирует просто"
    );

    private static final List<String> ANCHOR_POLICIES = List.of(
            "не упоминать название компании, адрес и улицу; держать фокус на ситуации клиента",
            "название компании не писать; можно упомянуть только район или город, если это важно для дороги",
            "не начинать с бренда и не вставлять бренд в середину текста; писать как обычный клиент",
            "название, адрес и улицу не использовать; конкретику брать из услуги, товара, ситуации и результата"
    );

    private static final List<String> OPENING_POLICIES = List.of(
            "начать с личной причины: зачем клиенту понадобилась услуга",
            "начать с результата или ощущения после обращения",
            "начать с конкретной проблемы без слов 'заехал' и 'обратился'",
            "начать с короткой бытовой детали, затем перейти к услуге",
            "начать с сомнения или опасения, но не повторять формулу 'сначала хотел понять'"
    );

    private static final List<String> COMMON_REVIEW_PHRASES = List.of(
            "разложили понятно",
            "что делать сейчас",
            "что потом",
            "без давления",
            "без спешки",
            "не навязывали",
            "согласовали запчасти",
            "согласовали ремонт",
            "показали конкретно",
            "объяснили обычными словами",
            "осталось нормальное",
            "все стало ясно",
            "нормальное впечатление",
            "спокойное впечатление",
            "не общими словами",
            "вопросы по электрике",
            "машину отдали в нормальном состоянии"
    );

    private static final List<String> STYLE_MINING_TASKS = List.of(
            "найти 2-3 живых публичных отзыва по похожей нише и взять только ритм: как начинается, где бытовая деталь, как заканчивается",
            "посмотреть отзывы на картах по похожей услуге и выделить 2-3 разных композиции: короткая благодарность, осторожная проверка, история с результатом",
            "подсмотреть разговорные обороты в реальных отзывах по категории, но не копировать фразы дословно",
            "сравнить, как люди пишут короткие и средние отзывы по этой теме: что обычно упоминают первым и чем завершают",
            "найти не рекламные, а обычные отзывы с умеренным тоном; взять только структуру и естественный темп речи"
    );

    private static final List<String> COMMON_PHRASE_POLICIES = List.of(
            "общие фразы можно использовать, но максимум одну и только если она звучит уместно",
            "если просится фраза про понятное объяснение, вырази её бытовым образом, не тем же оборотом",
            "можно оставить одну спокойную фразу вроде 'без спешки', но текст не должен на ней держаться",
            "лучше заменить общую оценку маленьким наблюдением: что проверяли, что уточняли, что стало проще",
            "не повторяй фразы из previousDraftToAvoid; в новом варианте выбери другой речевой ход"
    );

    private static final List<String> AUTO_DETAIL_MARKERS = List.of(
            "ходов", "подвес", "стук", "скрип", "управляем", "двигател", "гбц", "дефектов",
            "электрик", "стартер", "генератор", "акпп", "мкпп", "тормоз", "масл", "ремн",
            "жидкост", "рычаг", "стойк", "шаров", "втулк", "сайлент", "ступиц", "рулев",
            "амортиз", "свеч", "колод", "диск", "радиатор", "патруб", "насос"
    );
    private static final List<String> COMMERCIAL_DETAIL_MARKERS = List.of(
            "услуг", "товар", "позици", "пакет", "программ", "формат", "тариф", "абонемент",
            "меню", "комплект", "цена", "стоим", "прайс", "руб", "₽", "билет", "брон",
            "предоплат", "достав", "самовывоз", "заказ", "квест", "праздник", "день рождения",
            "аниматор", "актер", "актёр", "лазертаг", "мафия", "мастер-класс", "сертификат"
    );

    private static final List<String> AUTO_MODELS = List.of(
            "Mazda MPV", "Toyota Corolla", "Toyota Camry", "Toyota RAV4", "Nissan X-Trail",
            "Nissan Qashqai", "Mitsubishi Outlander", "Honda CR-V", "Honda Stepwgn",
            "Subaru Forester", "Kia Rio", "Hyundai Solaris", "Lada Vesta", "Volkswagen Polo"
    );

    private static final List<String> SUSPENSION_TEMPLATE_DETAILS = List.of(
            "стойки стабилизатора", "втулки стабилизатора", "шаровая опора",
            "сайлентблоки передних рычагов", "рулевые наконечники", "ступичный подшипник",
            "стойки амортизаторов", "опорные подшипники"
    );

    private static final List<String> ENGINE_TEMPLATE_DETAILS = List.of(
            "свечи зажигания", "катушки зажигания", "прокладка ГБЦ", "ремень ГРМ",
            "помпа", "термостат", "патрубки охлаждения", "масляный фильтр"
    );

    private static final List<String> ELECTRIC_TEMPLATE_DETAILS = List.of(
            "стартер", "щётки генератора", "ремень генератора", "аккумулятор",
            "клеммы аккумулятора", "реле стартера", "масса кузова"
    );

    private static final List<String> ROAD_TRIP_TEMPLATE_DETAILS = List.of(
            "масло и фильтры", "тормозные колодки", "тормозная жидкость",
            "антифриз", "ремень генератора", "свет фар", "зарядка генератора",
            "давление в шинах", "проверка подвески"
    );

    private static final List<Double> TEMPERATURES = List.of(0.45, 0.55, 0.65, 0.72, 0.8);

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;
    private final ReviewSafetyService reviewSafetyService;

    public boolean isOpenAiAvailable() {
        return openAiProvider.isAvailable();
    }

    public Optional<ReputationSingleReviewDraftResult> create(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts
    ) {
        if (!openAiProvider.isAvailable()) {
            return Optional.empty();
        }

        PromptVariant variant = PromptVariant.random(request);
        try {
            AiResponse response = openAiProvider.generateSingleReviewDraft(new AiRequest(
                    "reputation-single-review-draft",
                    draftSystemPrompt(),
                    draftUserPrompt(deepReport, pack, request, selectedIdea, fallbackFacts, variant),
                    variant.temperature(),
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                if (!response.errorMessage().isBlank()) {
                    log.warn("AI single review draft generation returned no text: {}", response.errorMessage());
                }
                return Optional.empty();
            }
            ReputationSingleReviewDraftResult result = parseResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    request.style(),
                    response.text(),
                    selectedIdea,
                    fallbackFacts
            );
            if (result.draft().isBlank()) {
                return Optional.empty();
            }
            ReputationSingleReviewDraftResult polished = polishResult(companyId, deepReportJobId, contentPackJobId, pack, request, selectedIdea, fallbackFacts, result, variant)
                    .orElse(result);
            if (needsSpecificityRepair(polished, request, selectedIdea, fallbackFacts)) {
                return repairResult(companyId, deepReportJobId, contentPackJobId, pack, request, selectedIdea, fallbackFacts, polished, variant)
                        .or(() -> Optional.of(polished));
            }
            return Optional.of(polished);
        } catch (Exception exception) {
            log.warn("AI single review draft generation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ReputationBatchReviewDraftResult> createBatch(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            List<ReviewGenerationSlot> slots
    ) {
        if (!openAiProvider.isAvailable() || slots == null || slots.isEmpty()) {
            return Optional.empty();
        }

        PromptVariant variant = PromptVariant.random(singleRequest(request, ""));
        try {
            String systemPrompt = batchSystemPrompt();
            String userPrompt = batchUserPrompt(request, brief, slots, variant);
            log.info("""
                    AI batch review draft prompt
                    ===== SYSTEM =====
                    {}
                    ===== USER =====
                    {}
                    ===== END PROMPT =====
                    """, systemPrompt, userPrompt);
            AiResponse response = openAiProvider.generateBatchReviewDraft(new AiRequest(
                    "reputation-batch-review-drafts",
                    systemPrompt,
                    userPrompt,
                    Math.max(0.62, variant.temperature()),
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                if (!response.errorMessage().isBlank()) {
                    log.warn("AI batch review draft generation returned no text: {}", response.errorMessage());
                }
                return Optional.empty();
            }
            ReputationBatchReviewDraftResult result = parseBatchResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    response.text()
            );
            result = keepOnlyUsableBatchDrafts(result, slots);
            return result.drafts().isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI batch review draft generation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReputationSingleReviewDraftResult> polishResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts,
            ReputationSingleReviewDraftResult initial,
            PromptVariant variant
    ) {
        try {
            AiResponse response = openAiProvider.generateSingleReviewDraft(new AiRequest(
                    "reputation-single-review-draft-polish",
                    polishSystemPrompt(),
                    polishUserPrompt(request, selectedIdea, fallbackFacts, initial, variant),
                    Math.max(0.35, variant.temperature() - 0.15),
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                return Optional.empty();
            }
            ReputationSingleReviewDraftResult polished = parseResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    request.style(),
                    response.text(),
                    selectedIdea,
                    initial.sourceFacts().isEmpty() ? fallbackFacts : initial.sourceFacts()
            );
            return polished.draft().isBlank() ? Optional.empty() : Optional.of(polished);
        } catch (Exception exception) {
            log.warn("AI single review polish failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReputationSingleReviewDraftResult> repairResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts,
            ReputationSingleReviewDraftResult current,
            PromptVariant variant
    ) {
        try {
            AiResponse response = openAiProvider.generateSingleReviewDraft(new AiRequest(
                    "reputation-single-review-draft-specificity-repair",
                    polishSystemPrompt(),
                    repairUserPrompt(request, selectedIdea, fallbackFacts, current, variant),
                    Math.min(0.82, Math.max(0.55, variant.temperature() + 0.08)),
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                return Optional.empty();
            }
            ReputationSingleReviewDraftResult repaired = parseResult(
                    companyId,
                    deepReportJobId,
                    contentPackJobId,
                    pack,
                    response.provider(),
                    modelLabel(request.contentPackProfile()),
                    request.style(),
                    response.text(),
                    selectedIdea,
                    current.sourceFacts().isEmpty() ? fallbackFacts : current.sourceFacts()
            );
            return repaired.draft().isBlank() ? Optional.empty() : Optional.of(repaired);
        } catch (Exception exception) {
            log.warn("AI single review specificity repair failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String draftSystemPrompt() {
        return """
                Ты пишешь черновик отзыва от лица обычного клиента.
                Используй тему отзыва, выжимку по компании и шаблонные детали из specificity.
                Подтверждённые факты бери из companyDigest и orderContext. Шаблонные марки, товары, услуги, авто и запчасти можно использовать из templateCandidateDetails или из web search как кандидаты для выбора клиентом.
                Не придумывай подтверждённые цены, имена мастеров, адреса, сроки и гарантии. Шаблонные детали не выдавай за подтверждённый факт.
                Название компании - это только справочный контекст. Не используй название компании в draft. Город, район или адрес упоминай только если это часть реального клиентского опыта по дороге/локации.
                Можно использовать web search для изучения речевых паттернов и типовых сочетаний товара/услуги/авто/запчасти по похожей нише. Не копируй чужие отзывы дословно.
                Отзыв должен звучать живо, не как рекламный текст и не как инструкция для клиента.
                Каждый новый вариант должен менять композицию, первое предложение и набор использованных деталей.
                Не используй квадратные скобки, заглушки, markdown и пояснения.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String polishSystemPrompt() {
        return """
                Ты редактор пользовательских отзывов.
                Перепиши черновик так, чтобы он звучал естественнее и отличался структурой от типового текста.
                Сохрани смысл, тему и подтверждённые факты. Можно добавлять шаблонные товары/услуги/авто/запчасти только из specificity.templateCandidateDetails и помечать их в safetyNotes для проверки клиентом.
                Убери канцелярит, рекламность, название компании, лишний адрес и одинаковые связки.
                Если черновик построен как "причина - проверили - объяснили - итог", перестрой порядок.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String batchSystemPrompt() {
        return """
                Ты пишешь пачку разных черновиков отзывов от лица обычных клиентов.
                На входе несколько карточек reviewId. Для каждой карточки нужен свой draft.
                Все тексты должны отличаться: тема, первое предложение, длина, тон, порядок мыслей, бытовая деталь и финал.
                Не делай серию по шаблону "обратился/заехал - сделали - объяснили - итог".
                Не вставляй название компании в draft вообще. Адрес используй только если карточка прямо про дорогу, вход или локацию.
                Подтверждённые факты бери только из reviewGenerationBrief и конкретного reviewSlot.
                Используй reviewGenerationBrief.businessType и allowedScenarioTypes как мягкую отраслевую подсказку. Если theme/mustCover/mayCover задают конкретную ситуацию, следуй им.
                У каждой карточки есть theme, mustCover, mayCover и иногда exampleDetails. Draft этой карточки должен естественно покрыть тему, 1-3 детали из mustCover/mayCover и минимум одну деталь из exampleDetails, если этот список есть.
                exampleDetails — шаблонные детали для конкретики, а не подтверждённые факты клиента. Если там есть марка/модель и деталь/запчасть, постарайся использовать обе, если это звучит естественно.
                Если в mustCover/mayCover есть конкретное название квеста, товара, пакета, услуги, длительность или стоимость, используй это в draft естественно хотя бы в части карточек.
                Поля reviewGenerationBrief, reviewSlots, theme, mustCover, mayCover, exampleDetails, clientMustConfirm, openingInstruction, lengthInstruction и toneInstruction — это внутренние подсказки. В draft должен попасть только клиентский опыт.
                Ответ должен быть одним JSON-объектом строго такой формы: {"drafts":[{"reviewId":число,"draft":"текст","sourceFacts":["использованные факты"],"clientMustConfirm":["что проверить клиенту"],"safetyNotes":["что проверить"]}],"safetyNotes":["общие предупреждения"]}.
                Не добавляй поля вне этой схемы.
                В draft запрещено писать название компании из reviewGenerationBrief.company. Используй услугу, ситуацию и результат без бренда.
                Не используй в draft служебные фразы и ярлыки задания: "По теме", "Отзыв для карточки", "товар/услуга:", "категория:", "цена:", "нужно написать", "Главный вывод", "Главный якорь", "акцент из отчёта".
                Не копируй в draft аналитические заголовки отчёта: "Смешанный бизнес", "Операционный профиль", "Клиентский путь", "Репутационный вывод", "в отзывах упоминается".
                Используй смысл подсказок естественно: как клиентский опыт, а не как перечисление полей.
                Шаблонные марки, товары, услуги, авто и запчасти используй из exampleDetails/mustCover/mayCover; если это личная деталь клиента, добавь её в clientMustConfirm и safetyNotes.
                Можно добавить одну уместную бытовую деталь для живости: торт, пицца, одноразовая посуда, пакет с угощениями, дорога после работы, ожидание в зоне, если это совместимо с темой. Такие детали не являются подтверждёнными фактами: добавь их в clientMustConfirm и safetyNotes.
                Не придумывай имена, возраст, количество участников, точные цены, сроки, гарантии, медицинские результаты и сотрудников. Если такой точной детали нет во входе, не используй её.
                Не копируй previousDraft и не повторяй слабые общие фразы в каждом отзыве. Не начинай два текста одинаковыми словами.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String draftUserPrompt(
            DeepCompanyResearchReport deepReport,
            ReputationContentPack pack,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts,
            PromptVariant variant
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewTopic", selectedIdea);
        payload.put("companyDigest", companyDigest(deepReport, pack, fallbackFacts, request.orderContext(), selectedIdea));
        payload.put("selectors", selectors(request, variant));
        payload.put("specificity", specificityPayload(request, selectedIdea, fallbackFacts));
        payload.put("webStyleMining", webStyleMining(selectedIdea, pack, variant));
        payload.put("previousDraftToAvoid", limit(cleanPreviousDraft(request.previousDraft()), 900));
        payload.put("rules", generationRules());
        return "Напиши один отзыв по теме и выжимке. Ответ JSON: idea, draft, sourceFacts, safetyNotes.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private String batchUserPrompt(
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            List<ReviewGenerationSlot> slots,
            PromptVariant variant
    ) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReviewGenerationSlot slot : slots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reviewId", slot.reviewId());
            item.put("theme", slot.theme());
            item.put("mustCover", conciseSlotFacts(slot.mustUse(), 4, 105));
            item.put("mayCover", conciseSlotFacts(slot.mayUse(), 7, 105));
            List<String> exampleDetails = batchExampleDetails(brief, slot);
            if (!exampleDetails.isEmpty()) {
                item.put("exampleDetails", exampleDetails);
            }
            item.put("clientMustConfirm", conciseSlotFacts(slot.clientMustConfirm(), 6, 120));
            item.put("previousDraftToAvoid", limit(cleanPreviousDraft(slot.previousDraft()), 700));
            item.put("openingInstruction", slot.structure());
            item.put("lengthInstruction", slot.length());
            item.put("toneInstruction", slot.tone());
            items.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> briefPayload = new LinkedHashMap<>();
        briefPayload.put("company", brief.company());
        briefPayload.put("city", brief.city());
        briefPayload.put("category", brief.category());
        briefPayload.put("businessType", brief.businessType());
        briefPayload.put("services", brief.services());
        briefPayload.put("products", brief.products());
        briefPayload.put("prices", brief.prices());
        briefPayload.put("advantages", brief.advantages());
        briefPayload.put("reviewIdeas", brief.reviewIdeas());
        briefPayload.put("travelFromCenter", brief.travelFromCenter());
        briefPayload.put("employees", brief.employees());
        briefPayload.put("amenities", brief.amenities());
        briefPayload.put("parking", brief.parking());
        briefPayload.put("interestingFacts", brief.interestingFacts());
        briefPayload.put("allowedScenarioTypes", brief.allowedScenarioTypes());
        payload.put("reviewGenerationBrief", briefPayload);
        payload.put("batchSelectors", Map.of(
                "style", request.style(),
                "authorType", request.authorType(),
                "emojiMode", emojiInstruction(request.emojiMode()),
                "length", "смешанная: часть коротких, часть средних, без одинакового размера",
                "globalStructure", variant.structure(),
                "globalVoice", variant.voice()
        ));
        payload.put("reviewSlots", items);
        payload.put("batchRules", List.of(
                "Внутри каждого drafts[] обязательно верни reviewId, draft, sourceFacts, clientMustConfirm и safetyNotes.",
                "Верни draft для каждого reviewId из reviewSlots, не пропускай карточки.",
                "Каждый draft 1-6 предложений; длины должны различаться.",
                "У соседних отзывов не должно быть одинакового начала, одинаковой концовки или одинакового набора общих фраз.",
                "В каждом draft покрой смысл slot.theme и 1-3 детали из slot.mustCover/mayCover.",
                "Если slot.exampleDetails не пустой, обязательно используй минимум одну деталь оттуда в draft; для автосервиса желательно взять и модель авто, и одну деталь/запчасть.",
                "Если взял деталь из exampleDetails, добавь её в clientMustConfirm и safetyNotes.",
                "Если в slot.mustCover/mayCover есть название товара, квеста, пакета, длительность или цена, не обходи это общей фразой; используй конкретику естественно.",
                "Можно добавить одну бытовую деталь для естественности: торт, пицца, одноразовая посуда, угощения, дорога после работы, ожидание, если она совместима с темой; добавь её в clientMustConfirm и safetyNotes.",
                "Не выдумывай точные имена, возраст, количество участников, цены, сроки, гарантии и сотрудников, если их нет во входе.",
                "businessType и allowedScenarioTypes только помогают выбрать лексику; тема карточки, mustCover и mayCover важнее.",
                "Не пиши название компании из reviewGenerationBrief.company в draft.",
                "Не начинай draft с пересказа служебной темы. Сразу пиши сам отзыв от лица клиента.",
                "Не выводи в draft reviewId, названия полей, цену как поле, категорию как поле или формулировки задания.",
                "Не используй аналитические слова из отчёта: Смешанный бизнес, Операционный профиль, Клиентский путь, Репутационный вывод, позиционировать.",
                "Выполни openingInstruction, lengthInstruction и toneInstruction для каждой карточки.",
                "sourceFacts должны быть короткими использованными фактами, а не служебными полями. Шаблонные детали из exampleDetails помечай как требующие проверки."
        ));
        return "Напиши разные отзывы пачкой по чистой выжимке и слотам. Ответ JSON строго по схеме из system prompt.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private String polishUserPrompt(
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts,
            ReputationSingleReviewDraftResult initial,
            PromptVariant variant
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewTopic", selectedIdea);
        payload.put("initialDraft", initial.draft());
        payload.put("usedFacts", initial.sourceFacts().isEmpty() ? fallbackFacts : initial.sourceFacts());
        payload.put("previousDraftToAvoid", limit(cleanPreviousDraft(request.previousDraft()), 900));
        payload.put("selectors", selectors(request, variant));
        payload.put("specificity", specificityPayload(request, selectedIdea, fallbackFacts));
        payload.put("rules", List.of(
                "Сохрани факты и общий смысл.",
                "Подтверждённые факты не меняй. Шаблонные детали можно добавлять из templateCandidateDetails, если они подходят теме.",
                "Сделай текст менее вылизанным: допускается чуть разговорная, неровная фраза.",
                "Не начинай с 'Заехал', 'Обратился', 'В машине появилась', если это уже звучит шаблонно.",
                "commonPhrasesCanUseSparingly из specificity можно использовать, но не делай их основой каждого отзыва.",
                "Сохрани или добавь хотя бы одну конкретную деталь из availableSpecificDetails или templateCandidateDetails.",
                "Если добавляешь шаблонную марку, товар, услугу или запчасть, safetyNotes должен сказать клиенту проверить эту подстановку перед публикацией.",
                "Если previousDraftToAvoid не пустой, новый draft должен заметно отличаться от него первым предложением, порядком мыслей и финалом.",
                "Ответ верни JSON с теми же полями: idea, draft, sourceFacts, safetyNotes."
        ));
        return "Сделай черновик более живым, не меняя факты:\n" + objectMapper.writeValueAsString(payload);
    }

    private String repairUserPrompt(
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts,
            ReputationSingleReviewDraftResult current,
            PromptVariant variant
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewTopic", selectedIdea);
        payload.put("draftToFix", current.draft());
        payload.put("usedFacts", current.sourceFacts().isEmpty() ? fallbackFacts : current.sourceFacts());
        payload.put("orderContext", limit(request.orderContext(), 900));
        payload.put("selectors", selectors(request, variant));
        payload.put("specificity", specificityPayload(request, selectedIdea, fallbackFacts));
        payload.put("rules", List.of(
                "Перепиши draftToFix заметно иначе, но только на основе usedFacts, orderContext и reviewTopic.",
                "commonPhrasesCanUseSparingly можно оставить точечно, но не повторяй те же связки, если они уже есть в draftToFix или previousDraftToAvoid.",
                "Используй 1-2 конкретные детали из availableSpecificDetails или templateCandidateDetails.",
                "Если точной марки автомобиля или запчасти нет во входных данных, можно поставить шаблонную марку/запчасть из templateCandidateDetails, подходящую теме.",
                "Не добавляй цены, сроки, гарантии и имена мастеров без входных данных.",
                "Если используешь шаблонную подстановку, явно добавь в safetyNotes, что клиент должен выбрать/подтвердить эту марку или деталь.",
                "Сделай одно предложение с новым углом: бытовая причина, неловкость, сомнение, результат или сравнение с прошлым разом.",
                "Ответ верни JSON с полями idea, draft, sourceFacts, safetyNotes."
        ));
        return "Черновик слишком общий или повторяет шаблон. Почини конкретику и лексику:\n"
                + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> selectors(ReputationSingleReviewDraftRequest request, PromptVariant variant) {
        Map<String, Object> selectors = new LinkedHashMap<>();
        selectors.put("length", lengthInstruction(request.length()));
        selectors.put("style", request.style());
        selectors.put("authorType", request.authorType());
        selectors.put("emojiMode", emojiInstruction(request.emojiMode()));
        selectors.put("manualNotes", request.manualNotes());
        selectors.put("structureVariant", variant.structure());
        selectors.put("voiceVariant", variant.voice());
        selectors.put("identityAnchorPolicy", variant.anchorPolicy());
        selectors.put("openingPolicy", variant.openingPolicy());
        selectors.put("commonPhrasePolicy", variant.commonPhrasePolicy());
        return selectors;
    }

    private Map<String, Object> webStyleMining(
            String selectedIdea,
            ReputationContentPack pack,
            PromptVariant variant
    ) {
        ResearchSnapshot snapshot = pack.researchSnapshot();
        String category = firstNonBlank(snapshot.subCategory(), snapshot.category(), pack.companyProfile().category(), "услуги");
        Map<String, Object> mining = new LinkedHashMap<>();
        mining.put("enabled", true);
        mining.put("task", variant.styleMiningTask());
        mining.put("searchQueryHint", "живые отзывы " + category + " " + limit(selectedIdea, 80));
        mining.put("rules", List.of(
                "Используй web search только чтобы понять типичные формы реальных отзывов: начало, длину, ритм, бытовые детали.",
                "Не копируй чужие тексты и фразы дословно.",
                "Можно брать типовые категории марок, товаров, услуг, узлов и запчастей как шаблонные кандидаты, если они подходят теме.",
                "Не бери из найденных отзывов чужие цены, имена мастеров, адреса, сроки, гарантии и уникальные личные истории.",
                "Итоговый draft должен смешивать наши данные из companyDigest/orderContext/reviewTopic и шаблонные детали как кандидаты для выбора клиентом."
        ));
        return mining;
    }

    private Map<String, Object> companyDigest(
            DeepCompanyResearchReport report,
            ReputationContentPack pack,
            List<String> fallbackFacts,
            String orderContext,
            String selectedIdea
    ) {
        ResearchSnapshot snapshot = pack.researchSnapshot();
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("identityContext", Map.of(
                "companyName", firstNonBlank(report.companyName(), snapshot.companyName()),
                "city", firstNonBlank(report.city(), snapshot.city()),
                "category", firstNonBlank(snapshot.subCategory(), snapshot.category(), pack.companyProfile().category()),
                "usage", "Справочно. Не вставлять название, город или адрес в draft, если identityAnchorPolicy это не разрешает."
        ));
        digest.put("shortDescription", firstNonBlank(pack.companyProfile().shortDescription(), firstTopicSection(report, selectedIdea)));
        digest.put("products", limitList(snapshot.products(), pack.companyProfile().products(), 10, 120));
        digest.put("commercialDetails", commercialDetails(report, pack, fallbackFacts, selectedIdea));
        digest.put("advantages", limitList(snapshot.advantages(), pack.companyProfile().advantages(), reviewFacts(fallbackFacts), 10, 160).stream()
                .filter(value -> !isInternalReportText(value))
                .toList());
        digest.put("reviewTopicFacts", limitList(reviewFacts(fallbackFacts), 10, 180));
        digest.put("orderContext", limit(orderContext, 900));
        digest.put("warnings", limitList(report.warnings(), 5, 140));
        digest.put("reportSections", report.sections().stream()
                .filter(section -> importantDigestSection(section.title(), section.body()))
                .sorted((left, right) -> Integer.compare(
                        sectionScore(right, selectedIdea),
                        sectionScore(left, selectedIdea)
                ))
                .limit(8)
                .map(section -> Map.of(
                        "title", limit(section.title(), 120),
                        "body", limit(section.body(), 700)
                ))
                .toList());
        return digest;
    }

    private List<String> limitList(List<String> first, List<String> second, List<String> third, int limit, int textLimit) {
        List<String> values = new ArrayList<>();
        addAll(values, first);
        addAll(values, second);
        addAll(values, third);
        return values.stream()
                .map(value -> limit(value, textLimit))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> limitList(List<String> values, List<String> moreValues, int limit, int textLimit) {
        List<String> merged = new ArrayList<>();
        addAll(merged, values);
        addAll(merged, moreValues);
        return limitList(merged, limit, textLimit);
    }

    private List<String> limitList(List<String> values, int limit, int textLimit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> limit(value, textLimit))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> conciseSlotFacts(List<String> values, int limit, int textLimit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> conciseSlotFact(value, textLimit))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(limit)
                .toList();
    }

    private List<String> conciseSlotFacts(List<String> first, List<String> second, int limit, int textLimit) {
        List<String> values = new ArrayList<>();
        addAll(values, first);
        addAll(values, second);
        return conciseSlotFacts(values, limit, textLimit);
    }

    private List<String> batchExampleDetails(ReviewGenerationBrief brief, ReviewGenerationSlot slot) {
        if (brief == null || slot == null || !"auto_service".equals(brief.businessType())) {
            return List.of();
        }
        String text = batchSlotText(slot);
        List<String> details = new ArrayList<>();
        if (text.matches(".*(ходов|подвес|стук|скрип|неровност|управляем|рулев).*")) {
            details.addAll(SUSPENSION_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(двигател|гбц|дефектов|зажиган|охлажд|троит|дым|температур).*")) {
            details.addAll(ENGINE_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(электрик|стартер|генератор|аккумулятор|заряд|завод).*")) {
            details.addAll(ELECTRIC_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(замена масла|комплексное то|\\bто\\b|дальн|дорог|отпуск|трасс|путешеств|перед поезд).*")) {
            details.addAll(ROAD_TRIP_TEMPLATE_DETAILS);
        }
        if (details.isEmpty()) {
            details.addAll(List.of(
                    "диагностика ходовой",
                    "проверка электрики",
                    "замена масла и фильтров",
                    "подбор запчастей"
            ));
        }

        List<String> examples = new ArrayList<>();
        int modelIndex = Math.floorMod(slot.reviewId() == null ? 0 : slot.reviewId().hashCode(), AUTO_MODELS.size());
        examples.add(AUTO_MODELS.get(modelIndex));
        details.stream()
                .distinct()
                .limit(4)
                .forEach(examples::add);
        return examples.stream()
                .map(value -> limit(value, 90))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(5)
                .toList();
    }

    private String batchSlotText(ReviewGenerationSlot slot) {
        List<String> values = new ArrayList<>();
        values.add(slot.theme());
        values.add(slot.service());
        values.add(slot.product());
        values.add(slot.advantage());
        values.add(slot.extraDetail());
        addAll(values, slot.mustUse());
        addAll(values, slot.mayUse());
        return String.join(" ", values).toLowerCase().replace('ё', 'е');
    }

    private String conciseSlotFact(String value, int textLimit) {
        String clean = value == null ? "" : value
                .replaceAll("^[\\s\\-*]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return "";
        }
        String lower = clean.toLowerCase().replace('ё', 'е');
        if (lower.startsWith("отзыв о ")
                || lower.startsWith("добавить")
                || lower.startsWith("указать")
                || lower.startsWith("объяснять")
                || lower.startsWith("как ")
                || lower.contains("sourcefacts")
                || lower.contains("reviewslots")
                || containsPromptLeakage(clean)
                || looksLikeTechnicalNote(clean)) {
            return "";
        }
        if (clean.contains(";")) {
            clean = clean.substring(0, clean.indexOf(';')).trim();
        }
        int colon = clean.indexOf(':');
        if (colon > 0 && colon <= 48) {
            String label = clean.substring(0, colon).toLowerCase().replace('ё', 'е').trim();
            if (label.matches(".*(отзыв|идея|логистика|удобства|парковка|сотрудник|команда|цена|стоимость|что собрать|что входит|где ждать|как найти).*")) {
                clean = clean.substring(colon + 1).trim();
            }
        }
        clean = clean
                .replaceAll("[.。]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return limit(clean, textLimit);
    }

    private List<String> commercialDetails(
            DeepCompanyResearchReport report,
            ReputationContentPack pack,
            List<String> fallbackFacts,
            String selectedIdea
    ) {
        List<String> values = new ArrayList<>();
        if (pack != null && pack.researchSnapshot() != null) {
            pack.researchSnapshot().products().forEach(value -> addCommercialDetail(values, value));
        }
        if (pack != null && pack.companyProfile() != null) {
            pack.companyProfile().products().forEach(value -> addCommercialDetail(values, value));
        }
        if (fallbackFacts != null) {
            fallbackFacts.forEach(value -> addCommercialDetail(values, value));
        }
        if (report != null && report.sections() != null) {
            report.sections().stream()
                    .filter(section -> commercialDigestSection(section.title(), section.body()))
                    .forEach(section -> addCommercialDetails(values, section.body()));
        }
        List<String> distinct = values.stream()
                .map(value -> limit(value, 260))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        List<String> sorted = new ArrayList<>(distinct);
        sorted.sort((left, right) -> Integer.compare(detailScore(right, selectedIdea), detailScore(left, selectedIdea)));
        return sorted.stream().limit(12).toList();
    }

    private boolean commercialDigestSection(String title, String body) {
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase().replace('ё', 'е');
        return text.matches(".*(услуг|товар|цен|стоим|прайс|тариф|пакет|программ|формат|меню|абонемент|комплект|квест|праздник).*");
    }

    private void addCommercialDetails(List<String> target, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isBlank() || line.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")) {
                continue;
            }
            String candidate = line.contains("|") ? tableDetail(line) : cleanDetail(line);
            if (candidate.isBlank() || headerLikeDetail(candidate)) {
                continue;
            }
            addCommercialDetail(target, candidate);
        }
    }

    private String tableDetail(String line) {
        List<String> cells = java.util.Arrays.stream(line.split("\\|"))
                .map(this::cleanDetail)
                .filter(value -> !value.isBlank())
                .filter(value -> !value.matches("(?i).*(источник|уверенность|source|confidence).*"))
                .toList();
        if (cells.isEmpty()) {
            return "";
        }
        if (cells.size() == 1) {
            return cells.getFirst();
        }
        return cells.getFirst() + " — " + String.join("; ", cells.subList(1, cells.size()));
    }

    private String cleanDetail(String value) {
        return value == null
                ? ""
                : value
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("^(?:[-*]|\\d+[.)])\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean headerLikeDetail(String value) {
        String clean = value.toLowerCase().replace('ё', 'е');
        return clean.matches(".*(позиция|название|описание).*цена.*")
                || clean.matches(".*(товар|услуга).*описание.*")
                || clean.matches(".*(условия|сроки).*источник.*");
    }

    private void addAll(List<String> target, List<String> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private List<String> generationRules() {
        return List.of(
                "Главное: отзыв строится вокруг reviewTopic, а не вокруг всех услуг компании сразу.",
                "Используй 1-3 конкретных факта из companyDigest: услугу, жизненную ситуацию, этап работы, особенность сервиса или полезную деталь.",
                "Если companyDigest.commercialDetails не пустой, используй одну конкретную позицию оттуда: товар/услугу/пакет, описание, условие или подтверждённую цену.",
                "Название компании не используй в draft вообще. Город, филиал или адрес допускаются только если это связано с дорогой, входом или локацией.",
                "Если в теме есть жизненная ситуация, развивай её, но не превращай отзыв в фантазию без опоры на факты.",
                "Не пиши одинаковую схему 'заехал - проверили - посоветовали - уехал'. Меняй порядок подачи, первое предложение и финальную мысль.",
                "commonPhrasesCanUseSparingly из specificity разрешены, но не должны повторяться в каждом отзыве и заменять конкретику.",
                "Используй хотя бы одну конкретную услугу, товар, симптом, модель авто, узел или запчасть из availableSpecificDetails или templateCandidateDetails.",
                "Если точной марки машины или детали нет во входных данных, можно использовать шаблонную подстановку из templateCandidateDetails или webStyleMining, но safetyNotes должен сказать клиенту проверить её.",
                "Цены, сроки, гарантии, имена мастеров и точные адреса не делай шаблонными подстановками.",
                "Не делай текст идеальным: допустимы простые фразы, короткие вставки вроде 'если честно', 'ну', 'по итогу', но без перебора.",
                "Используй webStyleMining как источник композиции, живого ритма и типовых шаблонных деталей.",
                "Если previousDraftToAvoid не пустой, новый draft должен заметно отличаться от него.",
                "Не называй текст черновиком и не проси клиента что-то дописать.",
                "sourceFacts верни использованные подтверждённые факты и шаблонные детали. Шаблонные детали помечай как 'Шаблонная деталь для проверки: ...'.",
                "safetyNotes верни коротко: что клиенту стоит проверить перед публикацией, особенно марки авто, товары, услуги и запчасти из шаблона."
        );
    }

    private Map<String, Object> specificityPayload(
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts
    ) {
        List<String> specificDetails = availableSpecificDetails(selectedIdea, request.orderContext(), fallbackFacts);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("availableSpecificDetails", specificDetails);
        payload.put("commercialDetails", availableCommercialDetails(selectedIdea, request.orderContext(), fallbackFacts));
        payload.put("templateCandidateDetails", templateCandidateDetails(selectedIdea, request.orderContext(), fallbackFacts));
        payload.put("commonPhrasesCanUseSparingly", COMMON_REVIEW_PHRASES);
        payload.put("missingClientDetails", missingClientDetails(selectedIdea, request.orderContext(), fallbackFacts));
        payload.put("instruction", specificDetails.isEmpty()
                ? "Если подтверждённых клиентских деталей мало, используй templateCandidateDetails как шаблонные подстановки для выбора клиентом."
                : "В draft нужна видимая конкретика из availableSpecificDetails или templateCandidateDetails. Общие фразы допустимы только как фон.");
        return payload;
    }

    private boolean needsSpecificityRepair(
            ReputationSingleReviewDraftResult result,
            ReputationSingleReviewDraftRequest request,
            String selectedIdea,
            List<String> fallbackFacts
    ) {
        String draft = result.draft() == null ? "" : result.draft().toLowerCase();
        if (draft.isBlank()) {
            return false;
        }
        long commonHits = COMMON_REVIEW_PHRASES.stream()
                .filter(phrase -> draft.contains(phrase.toLowerCase()))
                .count();
        String previous = request.previousDraft() == null ? "" : request.previousDraft().toLowerCase();
        long repeatedCommonHits = COMMON_REVIEW_PHRASES.stream()
                .map(String::toLowerCase)
                .filter(phrase -> !phrase.isBlank() && draft.contains(phrase) && previous.contains(phrase))
                .count();
        if (repeatedCommonHits >= 1 || commonHits >= 4) {
            return true;
        }
        List<String> details = availableSpecificDetails(selectedIdea, request.orderContext(), fallbackFacts);
        if (details.isEmpty()) {
            details = templateCandidateDetails(selectedIdea, request.orderContext(), fallbackFacts);
        }
        if (details.isEmpty()) {
            return false;
        }
        boolean hasDetail = details.stream()
                .map(value -> value.toLowerCase().replace('ё', 'е'))
                .anyMatch(detail -> draft.replace('ё', 'е').contains(detail)
                        || importantDetailMarkers(detail).stream().anyMatch(marker -> draft.replace('ё', 'е').contains(marker)));
        return !hasDetail;
    }

    private List<String> availableSpecificDetails(String selectedIdea, String orderContext, List<String> fallbackFacts) {
        List<String> values = new ArrayList<>();
        addSpecificDetail(values, selectedIdea);
        addSpecificDetail(values, orderContext);
        if (fallbackFacts != null) {
            fallbackFacts.forEach(value -> addSpecificDetail(values, value));
        }
        return values.stream()
                .map(value -> limit(value, 140))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(10)
                .toList();
    }

    private List<String> availableCommercialDetails(String selectedIdea, String orderContext, List<String> fallbackFacts) {
        List<String> values = new ArrayList<>();
        addCommercialDetail(values, selectedIdea);
        addCommercialDetail(values, orderContext);
        if (fallbackFacts != null) {
            fallbackFacts.forEach(value -> addCommercialDetail(values, value));
        }
        List<String> distinct = values.stream()
                .map(value -> limit(value, 220))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        List<String> sorted = new ArrayList<>(distinct);
        sorted.sort((left, right) -> Integer.compare(detailScore(right, selectedIdea), detailScore(left, selectedIdea)));
        return sorted.stream().limit(10).toList();
    }

    private List<String> templateCandidateDetails(String selectedIdea, String orderContext, List<String> fallbackFacts) {
        String text = (selectedIdea + "\n" + orderContext + "\n" + String.join("\n", fallbackFacts == null ? List.of() : fallbackFacts))
                .toLowerCase()
                .replace('ё', 'е');
        List<String> details = new ArrayList<>();
        boolean auto = text.matches(".*(авто|автомоб|машин|сервис|то\\b|ремонт|диагност|ходов|подвес|двигател|гбц|стартер|генератор|акпп|мкпп).*");
        if (!auto) {
            return List.of();
        }
        if (text.matches(".*(ходов|подвес|стук|скрип|неровност|управляем|рулев).*")) {
            details.addAll(SUSPENSION_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(двигател|гбц|зажиган|охлажд|троит|дым|температур).*")) {
            details.addAll(ENGINE_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(электрик|стартер|генератор|аккумулятор|заряд|завод).*")) {
            details.addAll(ELECTRIC_TEMPLATE_DETAILS);
        }
        if (text.matches(".*(то\\b|дальн|дорог|отпуск|переезд|трасс|путешеств|перед поезд).*")) {
            details.addAll(ROAD_TRIP_TEMPLATE_DETAILS);
        }
        if (details.isEmpty()) {
            details.addAll(List.of("диагностика ходовой", "проверка электрики", "замена масла и фильтров", "подбор запчастей"));
        }
        List<String> models = AUTO_MODELS.stream()
                .filter(model -> text.contains(model.toLowerCase().replace('ё', 'е')) || text.matches(".*(марка|модель|авто|машин|автомоб).*"))
                .limit(4)
                .toList();
        if (models.isEmpty() && auto) {
            models = AUTO_MODELS.stream().limit(4).toList();
        }
        List<String> candidates = new ArrayList<>();
        for (String detail : details.stream().distinct().limit(8).toList()) {
            candidates.add(detail);
        }
        for (String model : models) {
            candidates.add(model);
        }
        if (!models.isEmpty() && !details.isEmpty()) {
            candidates.add(models.getFirst() + " + " + details.getFirst());
        }
        return candidates.stream()
                .map(value -> limit(value, 120))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(14)
                .toList();
    }

    private void addSpecificDetail(List<String> target, String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return;
        }
        String normalized = clean.toLowerCase().replace('ё', 'е');
        boolean hasMarker = AUTO_DETAIL_MARKERS.stream().anyMatch(normalized::contains);
        if (!hasMarker) {
            hasMarker = hasCommercialMarker(normalized);
        }
        if (!hasMarker) {
            return;
        }
        for (String part : clean.split("(?<=[.!?])\\s+|[;•\\n]")) {
            String candidate = part.replaceAll("^(?:[-*]|\\d+[.)])\\s+", "").trim();
            String candidateNormalized = candidate.toLowerCase().replace('ё', 'е');
            if (candidate.length() >= 8 && (AUTO_DETAIL_MARKERS.stream().anyMatch(candidateNormalized::contains)
                    || hasCommercialMarker(candidateNormalized))) {
                target.add(candidate);
            }
        }
    }

    private void addCommercialDetail(List<String> target, String value) {
        String clean = cleanDetail(value);
        if (clean.isBlank()) {
            return;
        }
        String normalized = clean.toLowerCase().replace('ё', 'е');
        if (!hasCommercialMarker(normalized) || isInternalReportText(clean)) {
            return;
        }
        target.add(clean);
    }

    private boolean hasCommercialMarker(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return COMMERCIAL_DETAIL_MARKERS.stream().anyMatch(normalized::contains)
                || normalized.matches(".*\\b\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?|тыс\\.?).*")
                || normalized.matches(".*(от\\s+\\d|до\\s+\\d).*");
    }

    private int detailScore(String value, String selectedIdea) {
        String clean = value == null ? "" : value.toLowerCase().replace('ё', 'е');
        String idea = selectedIdea == null ? "" : selectedIdea.toLowerCase().replace('ё', 'е');
        int score = 0;
        for (String token : idea.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && clean.contains(token)) {
                score += 2;
            }
        }
        if (clean.matches(".*\\b\\d[\\d\\s]*(?:[,.]\\d+)?\\s*(?:₽|руб\\.?|р\\.?|тыс\\.?).*")) {
            score += 3;
        }
        if (clean.matches(".*(цена|стоим|прайс|тариф|пакет|программ|формат|комплект).*")) {
            score += 2;
        }
        return score;
    }

    private List<String> importantDetailMarkers(String detail) {
        String clean = detail == null ? "" : detail.toLowerCase().replace('ё', 'е');
        List<String> markers = new ArrayList<>();
        AUTO_DETAIL_MARKERS.stream()
                .filter(marker -> clean.contains(marker))
                .forEach(markers::add);
        COMMERCIAL_DETAIL_MARKERS.stream()
                .filter(marker -> clean.contains(marker))
                .forEach(markers::add);
        for (String token : clean.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && !isDetailStopWord(token)) {
                markers.add(token);
            }
        }
        return markers.stream().distinct().limit(8).toList();
    }

    private boolean isDetailStopWord(String token) {
        return List.of(
                "отзыв", "клиент", "компания", "услуга", "товар", "описание",
                "условия", "сроки", "источник", "уверенность", "можно", "нужно",
                "есть", "если", "после", "перед", "когда", "которые", "который"
        ).contains(token);
    }

    private List<String> missingClientDetails(String selectedIdea, String orderContext, List<String> fallbackFacts) {
        String text = (selectedIdea + "\n" + orderContext + "\n" + String.join("\n", fallbackFacts == null ? List.of() : fallbackFacts))
                .toLowerCase()
                .replace('ё', 'е');
        List<String> missing = new ArrayList<>();
        if (text.matches(".*(авто|автомоб|машин|ходов|двигател|гбц|акпп|мкпп|стартер|генератор|подвес).*")
                && !text.matches(".*(toyota|honda|nissan|mazda|mitsubishi|subaru|suzuki|kia|hyundai|ford|volkswagen|vw|audi|bmw|mercedes|renault|lada|ваз|газ|уаз|lexus|skoda|chevrolet|opel|peugeot|citroen|mpv|прадо|камри|королл|солярис|рио|веста|гранта|аутлендер|форестер|икстрейл|кашкай).*")) {
            missing.add("Марка/модель автомобиля не подтверждена: можно использовать шаблонную подстановку, клиент должен выбрать подходящую.");
        }
        if (text.matches(".*(запчаст|детал|ремонт|замен).*")
                && !text.matches(".*(рычаг|шаров|стойк|втулк|сайлент|ступиц|колод|диск|свеч|ремень|цепь|насос|радиатор|патруб|датчик|подшипник|амортизатор).*")) {
            missing.add("Точные заменённые детали не подтверждены: можно использовать шаблонную запчасть, клиент должен выбрать подходящую.");
        }
        return missing;
    }

    private List<String> reviewFacts(List<String> fallbackFacts) {
        if (fallbackFacts == null) {
            return List.of();
        }
        return fallbackFacts.stream()
                .map(value -> limit(value, 180))
                .filter(value -> !value.isBlank())
                .filter(value -> !identityOnlyFact(value))
                .filter(value -> !isInternalReportText(value))
                .distinct()
                .toList();
    }

    private boolean identityOnlyFact(String value) {
        String text = value == null ? "" : value.toLowerCase();
        boolean hasAddress = text.matches(".*(адрес|улиц|ул\\.|проспект|пр-т|дом|филиал|телефон|контакт|карта|2гис).*");
        boolean hasService = text.matches(".*(диагност|ремонт|то\\b|обслужив|замен|подбор|провер|услуг|запчаст|ходов|электрик|масл).*");
        return hasAddress && !hasService;
    }

    private boolean importantDigestSection(String title, String body) {
        String text = ((title == null ? "" : title) + " " + (body == null ? "" : body)).toLowerCase();
        return !isInternalReportText(text)
                && text.matches(".*(услуг|товар|утп|отзыв|филиал|адрес|режим|цена|срок|логист|парков|вход|ожидан|сценари|довер|качества).*");
    }

    private String firstReportSection(DeepCompanyResearchReport report) {
        return report.sections().stream()
                .filter(section -> section.body() != null && !section.body().isBlank())
                .map(section -> limit(section.body(), 500))
                .findFirst()
                .orElse("");
    }

    private String firstTopicSection(DeepCompanyResearchReport report, String selectedIdea) {
        return report.sections().stream()
                .filter(section -> section.body() != null && !section.body().isBlank())
                .filter(section -> !isInternalReportText(section.title() + " " + section.body()))
                .sorted((left, right) -> Integer.compare(
                        sectionScore(right, selectedIdea),
                        sectionScore(left, selectedIdea)
                ))
                .map(section -> limit(section.body(), 500))
                .findFirst()
                .orElse("");
    }

    private int sectionScore(DeepCompanyResearchReport.Section section, String selectedIdea) {
        String text = ((section.title() == null ? "" : section.title()) + " " + (section.body() == null ? "" : section.body())).toLowerCase();
        String idea = selectedIdea == null ? "" : selectedIdea.toLowerCase();
        int score = 0;
        for (String token : idea.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && text.contains(token)) {
                score += 2;
            }
        }
        if (text.matches(".*(иде.*отзыв|сценари|услуг|диагност|ремонт|обслужив).*")) {
            score += 1;
        }
        if (text.matches(".*(адрес|телефон|контакт|реквизит).*")) {
            score -= 1;
        }
        return score;
    }

    private ReputationSingleReviewDraftResult parseResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            String provider,
            String model,
            String style,
            String responseText,
            String fallbackIdea,
            List<String> fallbackFacts
    ) throws Exception {
        JsonNode root = objectMapper.readTree(responseText);
        String idea = firstNonBlank(root.path("idea").asText(""), fallbackIdea);
        String companyName = pack.researchSnapshot().companyName();
        String draft = cleanGeneratedText(root.path("draft").asText(""), companyName);
        List<String> sourceFacts = strings(root.path("sourceFacts"));
        if (sourceFacts.isEmpty()) {
            sourceFacts = fallbackFacts;
        }
        List<String> safetyNotes = strings(root.path("safetyNotes"));
        if (safetyNotes.isEmpty()) {
            safetyNotes = defaultSafetyNotes();
        }
        return new ReputationSingleReviewDraftResult(
                companyId,
                companyName,
                deepReportJobId,
                contentPackJobId,
                provider,
                model,
                idea,
                style,
                draft,
                sourceFacts,
                safetyNotes,
                reviewSafetyService.check(draft, sourceFacts),
                LocalDateTime.now()
        );
    }

    private ReputationBatchReviewDraftResult parseBatchResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            String provider,
            String model,
            String responseText
    ) throws Exception {
        JsonNode root = objectMapper.readTree(responseText);
        List<ReputationBatchReviewDraftItem> drafts = new ArrayList<>();
        JsonNode items = root.path("drafts");
        if (items.isArray()) {
            items.forEach(item -> {
                Long reviewId = item.path("reviewId").canConvertToLong() ? item.path("reviewId").asLong() : null;
                String draft = cleanGeneratedText(item.path("draft").asText(""), pack.researchSnapshot().companyName());
                if (reviewId == null || draft.isBlank()) {
                    return;
                }
                List<String> itemSafetyNotes = mergeSafetyNotes(
                        strings(item.path("safetyNotes")),
                        strings(item.path("clientMustConfirm"))
                );
                drafts.add(new ReputationBatchReviewDraftItem(
                        reviewId,
                        item.path("idea").asText(""),
                        draft,
                        strings(item.path("sourceFacts")),
                        itemSafetyNotes
                ));
            });
        }
        List<String> safetyNotes = strings(root.path("safetyNotes"));
        if (safetyNotes.isEmpty()) {
            safetyNotes = defaultSafetyNotes();
        }
        return new ReputationBatchReviewDraftResult(
                companyId,
                pack.researchSnapshot().companyName(),
                deepReportJobId,
                contentPackJobId,
                provider,
                model,
                drafts,
                safetyNotes,
                LocalDateTime.now()
        );
    }

    private ReputationBatchReviewDraftResult reinforceBatchAnchors(
            ReputationBatchReviewDraftResult result,
            List<BatchDraftTarget> targets
    ) {
        Map<Long, BatchDraftTarget> targetById = new LinkedHashMap<>();
        for (BatchDraftTarget target : targets) {
            targetById.put(target.reviewId(), target);
        }
        List<ReputationBatchReviewDraftItem> drafts = new ArrayList<>();
        int index = 0;
        for (ReputationBatchReviewDraftItem draft : result.drafts()) {
            if (draft.draft() == null || draft.draft().isBlank()) {
                drafts.add(draft);
                index++;
                continue;
            }
            BatchDraftTarget target = targetById.get(draft.reviewId());
            if (target == null || hasAnchor(draft.draft(), target)) {
                drafts.add(draft);
                index++;
                continue;
            }
            String anchor = cleanAnchorForDraft(firstNonBlank(
                    target.requiredAnchor(),
                    target.mustMentionCandidates().isEmpty() ? "" : target.mustMentionCandidates().getFirst(),
                    target.angleFromReport()
            ));
            if (anchor.isBlank()) {
                drafts.add(draft);
                index++;
                continue;
            }
            String text = anchorSentence(anchor, index) + " " + draft.draft();
            List<String> facts = new ArrayList<>(draft.sourceFacts());
            facts.add(anchor);
            List<String> notes = new ArrayList<>(draft.safetyNotes());
            notes.add("В текст добавлен обязательный якорь карточки: клиент должен проверить, что он совпадает с реальным опытом.");
            drafts.add(new ReputationBatchReviewDraftItem(
                    draft.reviewId(),
                    draft.idea(),
                    cleanGeneratedText(text, result.companyName()),
                    facts,
                    notes
            ));
            index++;
        }
        return new ReputationBatchReviewDraftResult(
                result.companyId(),
                result.companyName(),
                result.deepReportJobId(),
                result.contentPackJobId(),
                result.provider(),
                result.model(),
                drafts,
                result.safetyNotes(),
                result.generatedAt()
        );
    }

    private boolean hasAnchor(String draft, BatchDraftTarget target) {
        String cleanDraft = draft == null ? "" : draft.toLowerCase().replace('ё', 'е');
        List<String> candidates = new ArrayList<>();
        candidates.add(target.requiredAnchor());
        candidates.add(target.angleFromReport());
        candidates.addAll(target.mustMentionCandidates());
        return candidates.stream()
                .flatMap(value -> anchorTokens(value).stream())
                .distinct()
                .filter(token -> token.length() >= 5)
                .limit(12)
                .anyMatch(cleanDraft::contains);
    }

    private List<String> anchorTokens(String value) {
        String clean = value == null ? "" : value.toLowerCase().replace('ё', 'е');
        List<String> tokens = new ArrayList<>();
        for (String token : clean.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 5 && !isDetailStopWord(token) && !Set.of(
                    "отзыв", "карточки", "главный", "вывод", "якорь", "акцент", "нужно", "новый", "вариант",
                    "товар", "услуга", "категория", "цена", "позиционировать"
            ).contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String cleanAnchorForDraft(String value) {
        String clean = removePromptLeakage(limit(value, 150))
                .replaceAll("(?i)^\\s*(главный\\s+якорь\\s+карточки|дополнительный\\s+акцент\\s+из\\s+отч[её]та|идея\\s+из\\s+отч[её]та\\s+для\\s+этой\\s+карточки|конкретика\\s+для\\s+упоминания)\\s*:\\s*", "")
                .replaceAll("(?i)^\\s*товар/услуга\\s*:\\s*", "")
                .trim();
        return containsPromptLeakage(clean) ? "" : clean;
    }

    private String anchorSentence(String anchor, int index) {
        return switch (index % 5) {
            case 0 -> "По " + anchor + " всё получилось понятнее, чем ожидал.";
            case 1 -> "Отдельно запомнилось, что по " + anchor + " заранее проговорили детали.";
            case 2 -> "Для меня главным было именно " + anchor + ".";
            case 3 -> "Больше всего пригодилась конкретика по " + anchor + ".";
            default -> "С " + anchor + " не пришлось разбираться наугад.";
        };
    }

    private ReputationSingleReviewDraftRequest singleRequest(ReputationBatchReviewDraftRequest request, String previousDraft) {
        return new ReputationSingleReviewDraftRequest(
                request.deepReportJobId(),
                request.contentPackJobId(),
                "",
                request.style(),
                request.authorType(),
                request.emojiMode(),
                request.manualNotes(),
                request.length(),
                request.contentPackProfile(),
                null,
                previousDraft,
                ""
        );
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private List<String> mergeSafetyNotes(List<String> safetyNotes, List<String> clientMustConfirm) {
        List<String> result = new ArrayList<>();
        addAll(result, safetyNotes);
        if (clientMustConfirm != null) {
            clientMustConfirm.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> "Проверить у клиента: " + value.trim())
                    .forEach(result::add);
        }
        return result.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> defaultSafetyNotes() {
        return List.of("Перед публикацией клиент должен проверить, что личный опыт и использованные факты совпадают.");
    }

    private String cleanPreviousDraft(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank() || containsPromptLeakage(clean) || looksLikeTechnicalNote(clean)) {
            return "";
        }
        String lower = clean.toLowerCase().replace('ё', 'е');
        if (lower.contains("по авто-сервис")
                || lower.contains("по автосервис")
                || lower.contains("по действующий")
                || lower.contains("2гис показывает")
                || lower.contains("владельцам нескольких авто")
                || lower.contains("цена воспринимается")
                || lower.contains("нет публичного прайса")
                || lower.contains("на сайте/в карточках")
                || lower.contains("команда: имена")) {
            return "";
        }
        return clean;
    }

    private ReputationBatchReviewDraftResult keepOnlyUsableBatchDrafts(
            ReputationBatchReviewDraftResult result,
            List<ReviewGenerationSlot> slots
    ) {
        Set<Long> allowedIds = slots.stream()
                .map(ReviewGenerationSlot::reviewId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        List<ReputationBatchReviewDraftItem> drafts = result.drafts().stream()
                .filter(item -> item.reviewId() != null && allowedIds.contains(item.reviewId()))
                .filter(item -> item.draft() != null && !item.draft().isBlank())
                .filter(item -> !containsPromptLeakage(item.draft()))
                .filter(item -> !looksLikeTechnicalNote(item.draft()))
                .toList();
        return new ReputationBatchReviewDraftResult(
                result.companyId(),
                result.companyName(),
                result.deepReportJobId(),
                result.contentPackJobId(),
                result.provider(),
                result.model(),
                drafts,
                result.safetyNotes(),
                result.generatedAt()
        );
    }

    private String cleanGeneratedText(String value) {
        return cleanGeneratedText(value, "");
    }

    private String cleanGeneratedText(String value, String companyName) {
        if (value == null) {
            return "";
        }
        String clean = value
                .replace('[', ' ')
                .replace(']', ' ')
                .replaceAll("(?iu)^\\s*по\\s+теме\\s+\"?[^.?!]{0,700}(?:отзыв\\s+для\\s+карточки|товар/услуга|категория|цена|нужно\\s+написать)[^.?!]*[.?!:]?\\s*", "")
                .replaceAll("(?iu)^\\s*по\\s+теме\\s+\"?\\s*", "")
                .replaceAll("(?iu)^\\s*из\\s+полезного\\s*:\\s*", "")
                .replaceAll("(?iu)^\\s*главный\\s+вывод\\s*:\\s*", "")
                .replaceAll("(?iu)^\\s*по\\s+(смешанн(?:ый|ому)\\s+бизнес|операционн(?:ый|ому)\\s+профил[ьюя]|клиентск(?:ий|ому)\\s+пут[ьюя]|репутационн(?:ый|ому)\\s+вывод[ау]?)\\s*:?\\s*[^.?!]{0,500}[.?!]?\\s*", "")
                .replaceAll("(?iu)\\bотзыв\\s+для\\s+карточки\\s*#?\\d+\\s*;?\\s*", " ")
                .replaceAll("(?iu)(^|[;.]\\s*)(товар/услуга|категория|цена)\\s*:\\s*[^;.!?]{0,180}[;.]?\\s*", " ")
                .replaceAll("(?iu)(^|[;.]\\s*)нужно\\s+написать\\s+нов(?:ый|ого)\\s+вариант[^;.!?]*[;.]?\\s*", " ")
                .replaceAll("(?iu)(^|[;.]\\s*)(главный\\s+якорь\\s+карточки|дополнительный\\s+акцент\\s+из\\s+отч[её]та|идея\\s+из\\s+отч[её]та\\s+для\\s+этой\\s+карточки|конкретика\\s+для\\s+упоминания)\\s*:\\s*", " ")
                .replaceAll("(?i)\\s*добавьте\\s+[^.?!]+[.?!]?", " ")
                .replaceAll("\\s+", " ")
                .trim();
        clean = removeCompanyName(clean, companyName);
        return containsPromptLeakage(clean) ? "" : clean;
    }

    private String removeCompanyName(String value, String companyName) {
        if (value == null || value.isBlank() || companyName == null || companyName.isBlank()) {
            return value == null ? "" : value;
        }
        String clean = value;
        for (String alias : companyAliases(companyName)) {
            String aliasPattern = "(?:[«\"“”']\\s*)?"
                    + Pattern.quote(alias)
                    + "(?:\\s*[»\"“”'])?(?![\\p{L}\\p{N}])";
            String withPreposition = "(?iu)(?<![\\p{L}\\p{N}])(?:в|во|у|об|о|про|от|для|с|со|по)\\s+"
                    + aliasPattern;
            clean = clean.replaceAll(withPreposition, " ");
            String pattern = "(?iu)(?<![\\p{L}\\p{N}])" + aliasPattern;
            clean = clean.replaceAll(pattern, " ");
        }
        return clean
                .replaceAll("\\s+([,.;:!?])", "$1")
                .replaceAll("(^|[.!?]\\s*)[,;:]\\s*", "$1")
                .replaceAll("(?iu)(^|[.!?]\\s*)[Вв]\\s+(?=понравилось|приш[её]л|обратил|выбрал|искал|смотрел|наш[её]л)", "$1")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private List<String> companyAliases(String companyName) {
        String clean = companyName == null ? "" : companyName.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        aliases.add(clean);
        String unquoted = clean
                .replaceAll("(?iu)\\b(ооо|ип|ао|пао|зао)\\b\\s*", "")
                .replaceAll("[«»\"“”']", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (!unquoted.isBlank() && !unquoted.equalsIgnoreCase(clean)) {
            aliases.add(unquoted);
        }
        return aliases.stream().distinct().toList();
    }

    private String removePromptLeakage(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?iu)^\\s*по\\s+теме\\s+\"?\\s*", "")
                .replaceAll("(?iu)\\bотзыв\\s+для\\s+карточки\\s*#?\\d+\\s*;?\\s*", " ")
                .replaceAll("(?iu)(^|[;.]\\s*)(товар/услуга|категория|цена)\\s*:\\s*[^;.!?]{0,180}[;.]?\\s*", " ")
                .replaceAll("(?iu)(^|[;.]\\s*)нужно\\s+написать\\s+нов(?:ый|ого)\\s+вариант[^;.!?]*[;.]?\\s*", " ")
                .replaceAll("(?iu)^\\s*(главный\\s+вывод|главный\\s+якорь\\s+карточки|дополнительный\\s+акцент\\s+из\\s+отч[её]та|идея\\s+из\\s+отч[её]та\\s+для\\s+этой\\s+карточки|конкретика\\s+для\\s+упоминания)\\s*:\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsPromptLeakage(String value) {
        String clean = value == null ? "" : value.toLowerCase().replace('ё', 'е');
        return clean.contains("отзыв для карточки")
                || clean.contains("товар/услуга:")
                || clean.contains("категория:")
                || clean.contains("цена:")
                || clean.contains("нужно написать новый")
                || clean.contains("главный якорь")
                || clean.contains("акцент из отчета")
                || clean.contains("конкретика для упоминания")
                || clean.contains("не готово к публикации")
                || clean.contains("слот просит")
                || clean.contains("карточка требует")
                || clean.contains("текущие ограничения")
                || clean.contains("заданные запреты")
                || clean.contains("заданным запретам")
                || clean.contains("разрешенными сценариями")
                || clean.contains("разрешёнными сценариями")
                || isInternalReportText(clean)
                || clean.contains("reviewtopic")
                || clean.contains("requiredanchor");
    }

    private boolean looksLikeTechnicalNote(String value) {
        String clean = value == null ? "" : value.trim();
        String lower = clean.toLowerCase().replace('ё', 'е');
        return lower.matches("^по\\s+[а-яa-z0-9 /+-]{3,60}:.*")
                || lower.matches("^с\\s+[а-яa-z0-9 /+-]{3,60}\\s+.*(воспринимается|упоминается|указан|найден|вывод).*")
                || lower.contains("указаны в 2гис")
                || lower.contains("найдено много подробностей")
                || lower.contains("большинство отзывов говорит")
                || lower.contains("один клиент отмечает")
                || lower.contains("отдельный плюс за то")
                || lower.contains("не готово к публикации")
                || lower.contains("не совместим с разрешенными")
                || lower.contains("не совместим с разрешёнными")
                || lower.contains("противоречит заданным");
    }

    private boolean isInternalReportText(String value) {
        String clean = value == null ? "" : value.toLowerCase().replace('ё', 'е');
        return clean.contains("смешанный бизнес")
                || clean.contains("операционный профиль")
                || clean.contains("клиентский путь")
                || clean.contains("репутационный вывод")
                || clean.contains("главный вывод")
                || clean.contains("позиционирован")
                || clean.contains("лучше позиционировать")
                || clean.contains("есть признаки формата")
                || clean.contains("в отзывах несколько раз")
                || clean.contains("по отзывам")
                || clean.contains("для клиента это")
                || clean.contains("сервисный бокс/сто")
                || clean.contains("публичный дозбор")
                || clean.contains("что спросить у владельца");
    }

    private String lengthInstruction(String length) {
        String clean = length == null ? "" : length.toLowerCase();
        if (clean.contains("micro")) {
            return "1-2 предложения, очень коротко";
        }
        if (clean.contains("short")) {
            return "2-3 предложения, коротко";
        }
        if (clean.contains("long")) {
            return "6-8 предложений, подробнее";
        }
        if (clean.contains("story")) {
            return "мини-история 7-10 предложений";
        }
        return "3-5 предложений, средний отзыв";
    }

    private String emojiInstruction(String emojiMode) {
        String clean = emojiMode == null ? "" : emojiMode.toLowerCase();
        if (clean.contains("один")) {
            return "можно использовать максимум один уместный смайл";
        }
        if (clean.contains("немного")) {
            return "можно использовать 1-2 смайла, если естественно";
        }
        return "без смайлов";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String modelLabel(String profileKey) {
        ContentPackProfile profile = ContentPackProfile.fromKey(profileKey);
        return profile == null ? profileKey : profile.model();
    }

    private String limit(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= limit ? clean : clean.substring(0, limit).trim();
    }

    private record PromptVariant(
            String structure,
            String voice,
            String anchorPolicy,
            String openingPolicy,
            String styleMiningTask,
            String commonPhrasePolicy,
            double temperature
    ) {
        private static PromptVariant random(ReputationSingleReviewDraftRequest request) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            String requestedVoice = request.authorType();
            String voice = requestedVoice == null || requestedVoice.isBlank() || "нейтральный клиент".equalsIgnoreCase(requestedVoice)
                    ? VOICE_VARIANTS.get(random.nextInt(VOICE_VARIANTS.size()))
                    : requestedVoice;
            return new PromptVariant(
                    STRUCTURE_VARIANTS.get(random.nextInt(STRUCTURE_VARIANTS.size())),
                    voice,
                    ANCHOR_POLICIES.get(random.nextInt(ANCHOR_POLICIES.size())),
                    OPENING_POLICIES.get(random.nextInt(OPENING_POLICIES.size())),
                    STYLE_MINING_TASKS.get(random.nextInt(STYLE_MINING_TASKS.size())),
                    COMMON_PHRASE_POLICIES.get(random.nextInt(COMMON_PHRASE_POLICIES.size())),
                    TEMPERATURES.get(random.nextInt(TEMPERATURES.size()))
            );
        }
    }

    public record BatchDraftTarget(
            Long reviewId,
            String idea,
            String previousDraft,
            String orderContext,
            List<String> facts,
            String requiredAnchor,
            String angleFromReport,
            List<String> mustMentionCandidates,
            String openingInstruction,
            String lengthInstruction,
            String toneInstruction
    ) {
        public BatchDraftTarget(ReputationBatchReviewDraftTarget target, List<String> facts) {
            this(
                    target.reviewId(),
                    target.idea(),
                    target.previousDraft(),
                    target.orderContext(),
                    facts == null ? List.of() : facts,
                    facts == null || facts.isEmpty() ? "" : facts.getFirst(),
                    "",
                    facts == null ? List.of() : facts.stream().limit(4).toList(),
                    "начать не так, как соседние отзывы",
                    "средняя длина",
                    "спокойный"
            );
        }

        public BatchDraftTarget {
            idea = idea == null ? "" : idea.trim();
            previousDraft = previousDraft == null ? "" : previousDraft.trim();
            orderContext = orderContext == null ? "" : orderContext.trim();
            facts = facts == null ? List.of() : facts.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            requiredAnchor = requiredAnchor == null ? "" : requiredAnchor.trim();
            angleFromReport = angleFromReport == null ? "" : angleFromReport.trim();
            mustMentionCandidates = mustMentionCandidates == null ? List.of() : mustMentionCandidates.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(8)
                    .toList();
            openingInstruction = openingInstruction == null ? "" : openingInstruction.trim();
            lengthInstruction = lengthInstruction == null ? "" : lengthInstruction.trim();
            toneInstruction = toneInstruction == null ? "" : toneInstruction.trim();
        }
    }
}
