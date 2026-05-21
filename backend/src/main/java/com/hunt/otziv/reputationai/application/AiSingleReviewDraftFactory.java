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
import java.util.Locale;
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
            "очень коротко: один живой вывод и одна деталь, без общего вступления",
            "причина обращения раскрывается через конкретную деталь и спокойный итог",
            "сомнение клиента раскрывается через то, что стало понятнее после общения или проверки",
            "результат стоит первым, а дальше объясняется, почему он был важен",
            "маленькая бытовая история: повод, одна деталь общения, итог",
            "чуть придирчиво: что насторожило, что проверили, почему в итоге нормально",
            "деловой отзыв без эмоций: задача, одно действие или проверка, вывод",
            "разговорно и неровно: мысль развивается как заметка между делом",
            "не по хронологии: начать с вывода, потом дать причину и одну проверку",
            "заметка после визита: две конкретные детали без рекламного вступления",
            "от первого лица с бытовым контекстом, но без пересказа всей услуги",
            "пишет спустя время: сначала проверил результат, потом сделал вывод",
            "маленький минус без претензии: что было неудобно и почему итог устроил",
            "сравнение обещаний с фактом: осторожно, без неподтверждённых гарантий",
            "постсервисный вопрос: после услуги осталось уточнение, его спокойно закрыли"
    );

    private static final List<BatchNarrativeMode> BATCH_NARRATIVE_MODES = List.of(
            new BatchNarrativeMode(
                    "micro_reaction",
                    "очень короткая реакция обычного клиента; можно написать почти без процесса",
                    "0-1 конкретная деталь, 1 короткая мысль",
                    "light: достаточно общего впечатления и одного сигнала темы, experienceFocus можно только слегка задеть",
                    "не используй связку 'показали-согласовали-объяснили'; максимум один процессный глагол"
            ),
            new BatchNarrativeMode(
                    "blunt_positive",
                    "короткое прямое одобрение без подробностей, как в обычном отзыве на бегу",
                    "0-1 деталь, допускается простое 'хорошо', 'нормально', 'без претензий'",
                    "light: важнее живой тон, чем раскрытие процесса",
                    "не добавляй цепочку этапов; не объясняй, если режим просит короткую реакцию"
            ),
            new BatchNarrativeMode(
                    "neutral_ok",
                    "ровная нейтральная оценка без восторга и без рекламы",
                    "1 короткая деталь или общий результат",
                    "light: достаточно спокойной оценки и одного тематического сигнала",
                    "не используй эмоциональные выводы и подробные согласования"
            ),
            new BatchNarrativeMode(
                    "concise_thanks",
                    "короткая благодарность за конкретный момент, без длинной истории",
                    "1 благодарность + 1 деталь",
                    "light: можно ограничиться одним понятным эпизодом",
                    "не пересказывай ход работы; не добавляй список действий"
            ),
            new BatchNarrativeMode(
                    "routine_customer",
                    "повторный или привычный опыт: человек обслуживается/ходит/заказывает не первый раз",
                    "1-2 детали, без длинного описания работ",
                    "light: можно писать про стабильность и отсутствие нареканий, без полного раскрытия процесса",
                    "не строй текст как разовую приемку; избегай 'сначала-потом-по итогу'"
            ),
            new BatchNarrativeMode(
                    "seasonal_repeat",
                    "повторяющийся сезонный или периодический опыт, если это совместимо с услугой",
                    "1 привычка + 1 результат",
                    "light: достаточно показать, что это не разовое обращение",
                    "не придумывай точные даты и периоды; пиши общо, без календарных обещаний"
            ),
            new BatchNarrativeMode(
                    "no_fuss_result",
                    "отзыв про отсутствие лишней суеты: коротко, спокойно, по делу",
                    "1 деталь процесса + 1 итог",
                    "light: минимальный процесс, акцент на том, что не пришлось усложнять",
                    "избегай связки 'показали-согласовали-объяснили'; выбери только один глагол действия"
            ),
            new BatchNarrativeMode(
                    "casual_phrase",
                    "простая разговорная оценка, как человек написал между делом",
                    "0-1 деталь, допускается короткая фраза вроде 'нормально сделали'",
                    "light: одна живая оценка важнее полного перечисления фактов",
                    "не перечисляй этапы; не объясняй всё подробно"
            ),
            new BatchNarrativeMode(
                    "plain_recommend",
                    "простая рекомендация без рекламного тона, как совет знакомому",
                    "1 причина рекомендации, без превосходных степеней",
                    "light: короткий вывод допустим, если тема считывается",
                    "не используй 'лучшие', 'идеально', 'всем советую'; не расписывай весь процесс"
            ),
            new BatchNarrativeMode(
                    "first_time_customer",
                    "первый опыт клиента: что было непонятно до обращения и что стало яснее",
                    "1 сомнение + 1 понятный итог",
                    "medium: раскрывай только один вопрос новичка",
                    "можно написать 'объяснили' один раз, без соседних 'показали' и 'согласовали'"
            ),
            new BatchNarrativeMode(
                    "story_with_details",
                    "мини-история с бытовым поводом и 2-3 конкретными деталями",
                    "2-3 детали из slot или writingGuide, без цен и точных сроков",
                    "full: раскрой experienceFocus как маленький клиентский эпизод",
                    "можно использовать процессные глаголы, но не подряд 'показали-согласовали-объяснили'"
            ),
            new BatchNarrativeMode(
                    "long_mini_story",
                    "более развернутая бытовая история на 4-5 предложений",
                    "3 детали: повод, один момент общения, один результат",
                    "full: раскрывай experienceFocus через последовательность, но без служебных формулировок",
                    "можно использовать хронологию, но не превращай текст в отчет по этапам"
            ),
            new BatchNarrativeMode(
                    "checklist_style",
                    "сухая заметка: что было важно и что проверили/получили",
                    "2-3 коротких пункта внутри обычного текста",
                    "medium: раскрывай тему через список наблюдений, без эмоций",
                    "разрешены короткие глаголы действий, но без мини-истории"
            ),
            new BatchNarrativeMode(
                    "works_list",
                    "перечень сделанного обычным текстом, без эмоций и без оценки сервиса",
                    "2-3 совместимые работы или наблюдения",
                    "medium: подходит для автосервиса, ремонта, обучения, услуг с несколькими шагами",
                    "не добавляй личную драму; используй фактические глаголы без рекламного вывода"
            ),
            new BatchNarrativeMode(
                    "skeptical_then_ok",
                    "сначала сомнение или раздражение, затем что стало понятно",
                    "1-2 детали, одна личная реакция",
                    "medium: покажи контраст ожидания и результата",
                    "можно один раз написать 'объяснили', но не добавляй рядом 'показали' и 'согласовали'"
            ),
            new BatchNarrativeMode(
                    "small_minus_ok",
                    "один небольшой минус или тревога, затем почему итог всё равно устроил",
                    "1 минус без обвинений + 1 компенсирующий результат",
                    "medium: отзыв не должен звучать идеально, но и не должен выдумывать конфликт",
                    "не придумывай претензию к срокам, цене или сотрудникам; используй только мягкое впечатление"
            ),
            new BatchNarrativeMode(
                    "one_detail_only",
                    "весь отзыв вокруг одной заметной детали, без пересказа всей услуги",
                    "ровно 1 главная деталь",
                    "medium: выбери один нюанс experienceFocus и не раскрывай остальные",
                    "запрещена цепочка этапов; только одна деталь и вывод"
            ),
            new BatchNarrativeMode(
                    "specific_object_focus",
                    "фокус на одном объекте, узле, товаре, сценарии или участке работы",
                    "1 предмет + 1 наблюдение по нему",
                    "medium: не распыляйся на всю услугу, держи один предмет в центре",
                    "не перечисляй соседние темы; не добавляй неподтвержденные варианты"
            ),
            new BatchNarrativeMode(
                    "practical_result",
                    "сразу результат, потом коротко почему это было важно",
                    "1-2 детали результата",
                    "medium: фокус на результате, а не на ходе работ",
                    "не начинай с причины; не перечисляй все согласования"
            ),
            new BatchNarrativeMode(
                    "before_after",
                    "контраст до и после: что мешало до обращения и что изменилось",
                    "1 проблема до + 1 изменение после",
                    "medium: итог должен быть конкретнее общей похвалы",
                    "не добавляй длинную середину процесса; держи пару 'было/стало'"
            ),
            new BatchNarrativeMode(
                    "expectation_vs_reality",
                    "ожидание против реальности: чего опасались и что оказалось иначе",
                    "1 ожидание + 1 реальный итог",
                    "medium: раскрывай через разницу между предположением клиента и опытом",
                    "не делай текст рекламным; избегай абсолютов вроде 'идеально' и 'лучше всех'"
            ),
            new BatchNarrativeMode(
                    "question_answer",
                    "отзыв строится вокруг одного вопроса клиента и полученного ответа",
                    "1 вопрос + 1 ответ/решение",
                    "medium: хорошо подходит для тем с выбором, диагностикой, правилами или условиями",
                    "не копируй вопросные формулировки из prompt; перефразируй как живой вопрос"
            ),
            new BatchNarrativeMode(
                    "choice_between_options",
                    "клиент выбирал между вариантами и понял, какой подходит",
                    "2 варианта без утверждения неподтвержденного факта + 1 критерий выбора",
                    "medium: раскрывай критерий выбора, а не только название выбранного варианта",
                    "если варианты не подтверждены, пиши осторожно: 'сравнивали варианты', без точных утверждений"
            ),
            new BatchNarrativeMode(
                    "practical_tip",
                    "отзыв с маленьким советом будущему клиенту",
                    "1 совет + 1 причина",
                    "medium: совет должен вытекать из опыта, а не звучать как инструкция компании",
                    "не добавляй правила, цены, гарантии или точные условия, если их нет во входе"
            ),
            new BatchNarrativeMode(
                    "business_like",
                    "деловой опыт: документы, график, организация или предсказуемость",
                    "1 организационная деталь + 1 спокойный вывод",
                    "medium: без эмоций, но не как служебный отчет",
                    "не выдумывай юридические условия, суммы, договорные пункты или должности"
            ),
            new BatchNarrativeMode(
                    "personal_reason",
                    "личная причина обращения без лишней драматизации",
                    "1 личный повод + 1 тематическая деталь",
                    "medium: причина должна быть бытовой и безопасной, без точных личных фактов",
                    "не придумывай имена, возраст, диагнозы, семейный состав или сроки"
            ),
            new BatchNarrativeMode(
                    "late_realization",
                    "клиент не сразу понял ценность услуги, но позже оценил один момент",
                    "1 момент сомнения + 1 поздний вывод",
                    "medium: хорошо работает для обучения, ремонта, сложных услуг и праздников",
                    "не растягивай процесс; не добавляй неподтвержденные последствия"
            ),
            new BatchNarrativeMode(
                    "compact_report",
                    "короткий отчет без эмоций: задача, действие, результат",
                    "3 коротких фрагмента в 1-2 предложениях",
                    "medium: структура отчетная, но без названий полей и служебных ярлыков",
                    "разрешена сухая последовательность, но без повторения формулы в соседних карточках"
            ),
            new BatchNarrativeMode(
                    "voice_note_style",
                    "разговорная заметка как голосовое сообщение, с неровным порядком мыслей",
                    "1-2 детали, допускается разговорная пауза или уточнение",
                    "medium: естественность важнее идеальной структуры",
                    "не пиши канцелярски; не делай список согласований"
            )
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
    private static final List<String> AUTO_CHASSIS_TERMS = List.of(
            "ходов", "подвес", "стук", "скрип", "неровност", "рулев", "шаров", "стойк",
            "втулк", "сайлент", "ступич", "ступиц", "опорн", "амортиз"
    );
    private static final List<String> AUTO_ELECTRIC_TERMS = List.of(
            "стартер", "генератор", "аккумулятор", "клемм", "масса кузова", "реле стартера",
            "щетк генератор", "щетки генератор", "запуск", "завод"
    );
    private static final List<String> AUTO_ENGINE_TERMS = List.of(
            "двигател", "гбц", "дефектов", "прокладк", "грм", "свеч", "катуш", "помпа",
            "термостат", "мотор"
    );
    private static final Map<String, List<String>> AUTO_MODEL_ALIASES = Map.ofEntries(
            Map.entry("Mazda MPV", List.of("Mazda MPV", "Мазда MPV", "Мазда МПВ", "MPV", "МПВ")),
            Map.entry("Toyota Corolla", List.of("Toyota Corolla", "Тойота Королла", "Королла")),
            Map.entry("Toyota Camry", List.of("Toyota Camry", "Тойота Камри", "Камри")),
            Map.entry("Toyota RAV4", List.of("Toyota RAV4", "Тойота Рав4", "Рав4", "RAV4")),
            Map.entry("Nissan X-Trail", List.of("Nissan X-Trail", "Ниссан X-Trail", "Ниссан Икстрейл", "Икстрейл", "X-Trail")),
            Map.entry("Nissan Qashqai", List.of("Nissan Qashqai", "Ниссан Кашкай", "Кашкай")),
            Map.entry("Mitsubishi Outlander", List.of("Mitsubishi Outlander", "Митсубиси Аутлендер", "Аутлендер")),
            Map.entry("Honda CR-V", List.of("Honda CR-V", "Хонда CR-V", "Хонда СРВ", "CR-V", "СРВ")),
            Map.entry("Honda Stepwgn", List.of("Honda Stepwgn", "Хонда Stepwgn", "Хонда Степвагон", "Степвагон")),
            Map.entry("Subaru Forester", List.of("Subaru Forester", "Субару Форестер", "Форестер")),
            Map.entry("Kia Rio", List.of("Kia Rio", "Киа Рио", "Rio", "Рио")),
            Map.entry("Hyundai Solaris", List.of("Hyundai Solaris", "Хендай Солярис", "Хёндэ Солярис", "Солярис")),
            Map.entry("Lada Vesta", List.of("Lada Vesta", "Лада Веста", "Веста")),
            Map.entry("Volkswagen Polo", List.of("Volkswagen Polo", "Фольксваген Поло", "VW Polo", "Поло"))
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
            log.info(
                    "OPENAI_SINGLE_SKIPPED reason=openai_unavailable companyId={} reviewId={} idea=\"{}\"",
                    companyId,
                    request == null ? null : request.targetReviewId(),
                    shortLogText(selectedIdea)
            );
            return Optional.empty();
        }

        PromptVariant variant = PromptVariant.random(request);
        try {
            log.info(
                    "OPENAI_SINGLE_REQUEST companyId={} reviewId={} profile={} idea=\"{}\"",
                    companyId,
                    request.targetReviewId(),
                    request.contentPackProfile(),
                    shortLogText(selectedIdea)
            );
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
                log.info(
                        "OPENAI_SINGLE_REJECTED reason=empty_response companyId={} reviewId={} provider={} error=\"{}\"",
                        companyId,
                        request.targetReviewId(),
                        response.provider(),
                        shortLogText(response.errorMessage())
                );
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
                log.info(
                        "OPENAI_SINGLE_REJECTED reason=empty_parsed_draft companyId={} reviewId={} provider={} model={}",
                        companyId,
                        request.targetReviewId(),
                        result.provider(),
                        result.model()
                );
                return Optional.empty();
            }
            log.info(
                    "OPENAI_SINGLE_ACCEPTED companyId={} reviewId={} provider={} model={} draftChars={}",
                    companyId,
                    request.targetReviewId(),
                    result.provider(),
                    result.model(),
                    result.draft().length()
            );
            return Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI single review draft generation failed: {}", exception.getMessage());
            log.info(
                    "OPENAI_SINGLE_REJECTED reason=exception companyId={} reviewId={} error=\"{}\"",
                    companyId,
                    request == null ? null : request.targetReviewId(),
                    shortLogText(exception.getMessage())
            );
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
        if (!openAiProvider.isAvailable()) {
            log.info(
                    "OPENAI_BATCH_SKIPPED reason=openai_unavailable companyId={} slots={} slotIds={}",
                    companyId,
                    slots == null ? 0 : slots.size(),
                    slotIds(slots)
            );
            return Optional.empty();
        }
        if (slots == null || slots.isEmpty()) {
            log.info("OPENAI_BATCH_SKIPPED reason=no_slots companyId={}", companyId);
            return Optional.empty();
        }

        PromptVariant variant = PromptVariant.random(singleRequest(request, ""));
        try {
            BatchWritingGuide writingGuide = BatchWritingGuide.empty();
            String variationNonce = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
            String systemPrompt = batchSystemPrompt();
            String userPrompt = batchUserPrompt(request, brief, slots, variant, writingGuide, variationNonce);
            log.info("""
                    AI batch review draft prompt
                    ===== SYSTEM =====
                    {}
                    ===== USER =====
                    {}
                    ===== END PROMPT =====
                    """, systemPrompt, userPrompt);
            log.info(
                    "OPENAI_BATCH_REQUEST companyId={} slots={} slotIds={} profile={} writingGuide={} variationNonce={}",
                    companyId,
                    slots.size(),
                    slotIds(slots),
                    request.contentPackProfile(),
                    !writingGuide.isEmpty(),
                    variationNonce
            );
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
                log.info(
                        "OPENAI_BATCH_REJECTED reason=empty_response companyId={} provider={} slots={} error=\"{}\"",
                        companyId,
                        response.provider(),
                        slots.size(),
                        shortLogText(response.errorMessage())
                );
                if (!response.errorMessage().isBlank()) {
                    return Optional.of(emptyOpenAiBatchResult(
                            companyId,
                            deepReportJobId,
                            contentPackJobId,
                            pack,
                            response.provider(),
                            modelLabel(request.contentPackProfile()),
                            response.errorMessage()
                    ));
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
            int parsedDrafts = result.drafts().size();
            log.info(
                    "OPENAI_BATCH_RESULT companyId={} provider={} model={} parsedDrafts={} acceptedDrafts={} droppedDrafts={} acceptedIds={}",
                    companyId,
                    result.provider(),
                    result.model(),
                    parsedDrafts,
                    result.drafts().size(),
                    0,
                    result.drafts().stream().map(ReputationBatchReviewDraftItem::reviewId).toList()
            );
            if (result.drafts().isEmpty()) {
                log.info(
                        "OPENAI_BATCH_REJECTED reason=all_drafts_filtered companyId={} parsedDrafts={} slotIds={}",
                        companyId,
                        parsedDrafts,
                        slotIds(slots)
                );
            }
            return result.drafts().isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (Exception exception) {
            log.warn("AI batch review draft generation failed: {}", exception.getMessage());
            log.info(
                    "OPENAI_BATCH_REJECTED reason=exception companyId={} slots={} error=\"{}\"",
                    companyId,
                    slots == null ? 0 : slots.size(),
                    shortLogText(exception.getMessage())
            );
            return Optional.empty();
        }
    }

    private Optional<BatchWritingGuide> createBatchWritingGuide(
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            List<ReviewGenerationSlot> slots
    ) {
        if (!openAiProvider.isAvailable() || brief == null || slots == null || slots.isEmpty()) {
            return Optional.empty();
        }
        try {
            AiResponse response = openAiProvider.generateBatchReviewWritingGuide(new AiRequest(
                    "reputation-batch-review-writing-guide",
                    batchWritingGuideSystemPrompt(),
                    batchWritingGuideUserPrompt(brief, slots),
                    0.25,
                    true
            ), request.contentPackProfile());
            if (response == null || response.text().isBlank()) {
                if (response != null && !response.errorMessage().isBlank()) {
                    log.warn("AI batch review writing guide returned no text: {}", response.errorMessage());
                }
                return Optional.empty();
            }
            BatchWritingGuide guide = parseBatchWritingGuide(response.text(), slots);
            return guide.isEmpty() ? Optional.empty() : Optional.of(guide);
        } catch (Exception exception) {
            log.warn("AI batch review writing guide failed: {}", exception.getMessage());
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
                Используй тему отзыва, выжимку по компании и конкретный контекст заказа.
                Подтверждённые факты бери из companyDigest и orderContext.
                Марку или модель автомобиля используй только если она явно есть во входных данных. Если модели нет, пиши нейтрально: машина, авто, автомобиль.
                Не придумывай цены, имена мастеров, адреса, сроки и гарантии.
                Название компании - это только справочный контекст. Не используй название компании в draft. Город, район или адрес упоминай только если это часть реального клиентского опыта по дороге/локации.
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
                Сохрани смысл, тему и подтверждённые факты. Не добавляй новые товары, услуги, автоузлы, запчасти, цены и имена.
                Марку или модель автомобиля не добавляй, если её нет во входных данных.
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
                Используй reviewGenerationBrief.businessType как мягкую отраслевую подсказку. Если theme/mustCover/mayCover задают конкретную ситуацию, следуй им.
                У каждой карточки есть theme, mustCover и mayCover. Draft этой карточки должен естественно покрыть тему и не обязан перечислять всё.
                Если в mustCover/mayCover есть конкретное название квеста, товара, пакета, услуги, длительность или стоимость, используй это только когда оно подходит теме карточки.
                Если используешь точную цену из входа, добавь её в clientMustConfirm/safetyNotes как деталь для проверки перед публикацией.
                Не используй topic/theme/mustCover как ярлыки задания: запрещены фразы вроде "обсудили задачу: конкретный филиал", "уточнили хоррор-квесты", "обозначили тему".
                Проверяй связность синтаксиса и разговорную манеру: не склеивай разные подсказки через двоеточия, не делай список полей, не обрывай вступление отдельной строкой.
                Поля reviewGenerationBrief, reviewSlots, theme, mustCover, mayCover и clientMustConfirm — это внутренние подсказки. В draft должен попасть только клиентский опыт.
                Ответ должен быть одним JSON-объектом строго такой формы: {"drafts":[{"reviewId":число,"draft":"текст","sourceFacts":["использованные факты"],"clientMustConfirm":["что проверить клиенту"],"safetyNotes":["что проверить"]}],"safetyNotes":["общие предупреждения"]}.
                Не добавляй поля вне этой схемы.
                В draft запрещено писать название компании из reviewGenerationBrief.company. Используй услугу, ситуацию и результат без бренда.
                Не используй в draft служебные фразы и ярлыки задания: "По теме", "Отзыв для карточки", "товар/услуга:", "категория:", "цена:", "нужно написать", "Главный вывод", "Главный якорь", "акцент из отчёта".
                Не копируй в draft аналитические заголовки отчёта: "Смешанный бизнес", "Операционный профиль", "Клиентский путь", "Репутационный вывод", "в отзывах упоминается".
                Используй смысл подсказок естественно: как клиентский опыт, а не как перечисление полей.
                Не добавляй шаблонные товары, услуги, автоузлы и запчасти сверх входных данных.
                Не выдумывай марку или модель авто. Если модель не дана явно, пиши "машина", "авто" или "автомобиль".
                Не придумывай имена, возраст, количество участников, точные цены, сроки, гарантии, медицинские результаты и сотрудников. Если такой точной детали нет во входе, не используй её.
                Не копируй previousDraft и не повторяй слабые общие фразы в каждом отзыве. Не начинай два текста одинаковыми словами.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String batchWritingGuideSystemPrompt() {
        return """
                Ты готовишь справочник для автора отзывов, а не сами отзывы.
                Используй только входные данные. Не добавляй факты о конкретной компании, конкурентах, ценах, адресах, сотрудниках, гарантиях или обещаниях.
                Не называй бренды конкурентов и не сравнивай компании.
                Если тема про автосервис, не назначай марки и модели авто: можно объяснять только узлы, симптомы, работы и запчасти.
                Если тема содержит варианты через "или", в draft выбери один конкретный вариант, а неподтверждённость выбранного варианта вынеси в clientMustConfirm/safetyNotes.
                Пиши коротко, как подсказки для дальнейшей генерации. Верни только валидный JSON по схеме.
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
        payload.put("previousDraftToAvoid", limit(cleanPreviousDraft(request.previousDraft()), 900));
        payload.put("rules", generationRules());
        return "Напиши один отзыв по теме и выжимке. Ответ JSON: idea, draft, sourceFacts, safetyNotes.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private String batchUserPrompt(
            ReputationBatchReviewDraftRequest request,
            ReviewGenerationBrief brief,
            List<ReviewGenerationSlot> slots,
            PromptVariant variant,
            BatchWritingGuide writingGuide,
            String variationNonce
    ) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReviewGenerationSlot slot : slots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reviewId", slot.reviewId());
            item.put("theme", slot.theme());
            item.put("mustCover", conciseSlotFacts(slot.mustUse(), 4, 105));
            item.put("mayCover", conciseSlotFacts(slot.mayUse(), 7, 105));
            item.put("clientMustConfirm", conciseSlotFacts(slot.clientMustConfirm(), 6, 120));
            item.put("previousDraftToAvoid", limit(cleanPreviousDraft(slot.previousDraft()), 700));
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
        if (writingGuide != null && !writingGuide.isEmpty()) {
            payload.put("writingGuide", writingGuidePayload(writingGuide));
        }
        payload.put("batchSelectors", Map.of(
                "style", request.style(),
                "authorType", request.authorType(),
                "emojiMode", emojiInstruction(request.emojiMode()),
                "length", "смешанная: часть коротких, часть средних, без одинакового размера",
                "globalStructure", variant.structure(),
                "globalVoice", variant.voice(),
                "variationNonce", variationNonce,
                "variationInstruction", "при повторной генерации меняй первое предложение и порядок мыслей относительно previousDraftToAvoid"
        ));
        payload.put("reviewSlots", items);
        payload.put("batchRules", List.of(
                "Внутри каждого drafts[] обязательно верни reviewId, draft, sourceFacts, clientMustConfirm и safetyNotes.",
                "Верни draft для каждого reviewId из reviewSlots, не пропускай карточки.",
                "Не нормализуй все drafts к формуле 'причина -> показали -> согласовали -> объяснили -> итог'. Такая формула допустима максимум для одной карточки в пачке.",
                "У соседних отзывов не должно быть одинакового начала, одинаковой концовки, одинаковой формулы или одинакового набора глаголов процесса.",
                "В каждом draft покрой смысл slot.theme и одну совместимую деталь из slot.mustCover/mayCover, если такая деталь действительно подходит.",
                "Если есть writingGuide, используй его только как подсказку по языку ниши, раскрытию темы и разнообразию. Не считай writingGuide подтверждёнными фактами компании или клиента.",
                "Не копируй writingGuide дословно в draft; превращай подсказки в естественный опыт клиента.",
                "Для автосервиса марку или модель авто используй только если она явно есть в slot.theme/service/product/extraDetail/mustCover/mayCover.",
                "Не пиши мета-фразы по структуре задания: 'обсудили задачу: ...', 'обозначили тему', 'уточнили хоррор-квесты/детские квесты'. Это не клиентский опыт.",
                "Если mustCover/mayCover содержит длинный список через запятые или обрезанный фрагмент, не копируй список в draft; выбери только совместимую конкретную деталь или вынеси её в clientMustConfirm/safetyNotes.",
                "Если в slot.mustCover/mayCover есть название товара, квеста, пакета, длительность или цена, не обходи это общей фразой; используй конкретику естественно.",
                "Если используешь точную цену из входа, добавь её в clientMustConfirm и safetyNotes: цена должна быть актуальна на момент публикации.",
                "Не выдумывай точные имена, возраст, количество участников, цены, сроки, гарантии и сотрудников, если их нет во входе.",
                "businessType и allowedScenarioTypes только помогают выбрать лексику; тема карточки, mustCover и mayCover важнее.",
                "Не пиши название компании из reviewGenerationBrief.company в draft.",
                "Не начинай draft с пересказа служебной темы. Сразу пиши сам отзыв от лица клиента.",
                "Не выводи в draft reviewId, названия полей, цену как поле, категорию как поле или формулировки задания.",
                "Не используй аналитические слова из отчёта: Смешанный бизнес, Операционный профиль, Клиентский путь, Репутационный вывод, позиционировать.",
                "Учитывай toneInstruction для каждой карточки, но не вставляй его в текст явно.",
                "Перед финальным JSON мысленно проверь сочетание фраз, синтаксис и разговорную манеру: отзыв должен звучать как единая история, а не набор пунктов.",
                "sourceFacts должны быть короткими использованными фактами, а не служебными полями."
        ));
        return "Напиши разные отзывы пачкой по чистой выжимке и слотам. Ответ JSON строго по схеме из system prompt.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private String batchWritingGuideUserPrompt(
            ReviewGenerationBrief brief,
            List<ReviewGenerationSlot> slots
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("businessType", brief.businessType());
        payload.put("category", brief.category());
        payload.put("city", brief.city());
        payload.put("services", conciseSlotFacts(brief.services(), 14, 90));
        payload.put("products", conciseSlotFacts(brief.products(), 14, 90));
        payload.put("reviewIdeas", conciseSlotFacts(brief.reviewIdeas(), 30, 130));
        payload.put("allowedScenarioTypes", conciseSlotFacts(brief.allowedScenarioTypes(), 12, 80));

        List<Map<String, Object>> slotPayloads = new ArrayList<>();
        for (ReviewGenerationSlot slot : slots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reviewId", slot.reviewId());
            item.put("theme", slot.theme());
            item.put("mustCover", conciseSlotFacts(slot.mustUse(), 4, 100));
            item.put("mayCover", conciseSlotFacts(slot.mayUse(), 6, 100));
            List<String> experienceFocus = ideaExperienceFocus(slot.theme());
            if (!experienceFocus.isEmpty()) {
                item.put("experienceFocus", experienceFocus);
            }
            slotPayloads.add(item);
        }
        payload.put("reviewSlots", slotPayloads);
        payload.put("task", List.of(
                "Расширь темы как клиентские истории: какие сомнения, критерии выбора, процесс и итог можно раскрыть.",
                "Дай типовую лексику категории и термины, но без фактов о конкретной компании.",
                "Подскажи, как разнообразить пачку, чтобы отзывы не были одним шаблоном.",
                "Для каждого reviewId верни angles, decisionCriteria, naturalDetails и avoidClaims."
        ));
        return "Подготовь безопасный справочник для пачковой генерации отзывов. Не пиши сами отзывы.\n"
                + objectMapper.writeValueAsString(payload);
    }

    private Map<String, Object> writingGuidePayload(BatchWritingGuide guide) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("categoryLanguage", guide.categoryLanguage());
        payload.put("termHints", guide.termHints());
        payload.put("diversityWarnings", guide.diversityWarnings());
        payload.put("safetyNotes", guide.safetyNotes());
        payload.put("ideaExpansion", guide.ideaExpansion().stream()
                .map(item -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("reviewId", item.reviewId());
                    map.put("angles", item.angles());
                    map.put("decisionCriteria", item.decisionCriteria());
                    map.put("naturalDetails", item.naturalDetails());
                    map.put("avoidClaims", item.avoidClaims());
                    return map;
                })
                .toList());
        return payload;
    }

    private Map<Long, BatchNarrativeMode> batchNarrativeModes(List<ReviewGenerationSlot> slots, String variationNonce) {
        Map<Long, BatchNarrativeMode> result = new LinkedHashMap<>();
        if (slots == null || slots.isEmpty()) {
            return result;
        }
        List<BatchNarrativeMode> shortModes = shortNarrativeModes();
        List<BatchNarrativeMode> storyModes = storyNarrativeModes();
        int offset = Math.floorMod((variationNonce == null ? "" : variationNonce).hashCode(), BATCH_NARRATIVE_MODES.size());
        int shortIndex = 0;
        int storyIndex = 0;
        for (int index = 0; index < slots.size(); index++) {
            ReviewGenerationSlot slot = slots.get(index);
            if (slot.reviewId() == null) {
                continue;
            }
            if (isCadenceShortSlot(index, slots.size())) {
                result.put(slot.reviewId(), shortModes.get(Math.floorMod(offset + shortIndex, shortModes.size())));
                shortIndex++;
            } else {
                result.put(slot.reviewId(), storyModes.get(Math.floorMod(offset + storyIndex, storyModes.size())));
                storyIndex++;
            }
        }
        return result;
    }

    private List<BatchNarrativeMode> shortNarrativeModes() {
        return BATCH_NARRATIVE_MODES.stream()
                .filter(this::isLightNarrativeMode)
                .toList();
    }

    private List<BatchNarrativeMode> storyNarrativeModes() {
        List<BatchNarrativeMode> result = BATCH_NARRATIVE_MODES.stream()
                .filter(mode -> !isLightNarrativeMode(mode))
                .toList();
        return result.isEmpty() ? BATCH_NARRATIVE_MODES : result;
    }

    private boolean isCadenceShortSlot(int zeroBasedIndex, int totalSlots) {
        return totalSlots > 1 && zeroBasedIndex % 3 == 1;
    }

    private String lengthInstructionForMode(ReviewGenerationSlot slot, BatchNarrativeMode mode) {
        if (!isLightNarrativeMode(mode)) {
            return slot == null ? "" : slot.length();
        }
        return "1 предложение. Можно максимум 2 короткие фразы, как обычный короткий отзыв: "
                + "«брал/заказывал/ходил ..., всё нормально, спасибо», но с конкретикой из theme/mustCover.";
    }

    private BatchNarrativeMode narrativeModeForSlot(
            Map<Long, BatchNarrativeMode> narrativeModes,
            ReviewGenerationSlot slot
    ) {
        if (slot != null && narrativeModes != null && slot.reviewId() != null) {
            BatchNarrativeMode mode = narrativeModes.get(slot.reviewId());
            if (mode != null) {
                return mode;
            }
        }
        return BATCH_NARRATIVE_MODES.getFirst();
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
                "Подтверждённые факты не меняй. Новые товары, услуги, автоузлы и запчасти не добавляй.",
                "Сделай текст менее вылизанным: допускается чуть разговорная, неровная фраза.",
                "Не начинай с 'Заехал', 'Обратился', 'В машине появилась', если это уже звучит шаблонно.",
                "commonPhrasesCanUseSparingly из specificity можно использовать, но не делай их основой каждого отзыва.",
                "Сохрани хотя бы одну конкретную деталь из availableSpecificDetails, если она есть.",
                "Марку или модель автомобиля не добавляй, если её нет во входных данных.",
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
                "Используй 1-2 конкретные детали из availableSpecificDetails, если они есть.",
                "Если точной марки автомобиля нет во входных данных, не придумывай её; пиши машина, авто или автомобиль.",
                "Если точной запчасти нет во входных данных, не придумывай её.",
                "Не добавляй цены, сроки, гарантии и имена мастеров без входных данных.",
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

    private List<String> batchExampleDetails(
            ReviewGenerationBrief brief,
            ReviewGenerationSlot slot,
            BatchExampleDetailsPicker picker
    ) {
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
        autoModelsFromSourceText(text).stream()
                .limit(1)
                .forEach(examples::add);
        picker.detailsFor(slot, details).stream()
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

    private List<String> autoModelsFromSourceText(String value) {
        String normalized = normalizedAutoModelText(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        return AUTO_MODELS.stream()
                .filter(model -> normalized.contains(normalizedAutoModelText(model))
                        || AUTO_MODEL_ALIASES.getOrDefault(model, List.of()).stream()
                        .anyMatch(alias -> normalized.contains(normalizedAutoModelText(alias))))
                .toList();
    }

    private String normalizedAutoModelText(String value) {
        return value == null
                ? ""
                : value.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private List<String> ideaExperienceFocus(String theme) {
        String clean = theme == null ? "" : theme
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return List.of();
        }
        String focus = "";
        int colon = firstColonIndex(clean);
        if (colon > 0 && colon < clean.length() - 1) {
            focus = clean.substring(colon + 1).trim();
        } else {
            java.util.regex.Matcher matcher = Pattern
                    .compile("(?iu)\\b(?:как|почему|что|какие|какой|какая|когда|насколько|была\\s+ли|были\\s+ли)\\b.+")
                    .matcher(clean);
            if (matcher.find() && matcher.start() > 12) {
                focus = matcher.group().trim();
            }
        }
        if (focus.isBlank()) {
            return List.of();
        }
        focus = focus
                .replaceAll("^[\\s:;,.!?]+", "")
                .replaceAll("[.。]+$", "")
                .replaceAll("(?iu)(^|\\s)как\\s+компания\\s+", "$1как здесь ")
                .replaceAll("(?iu)(^|\\s)компания\\s+", "$1")
                .replaceAll("\\s+", " ")
                .trim();
        if (focus.isBlank()) {
            return List.of();
        }
        String[] parts = focus.split("\\?+|;|\\s*,\\s*(?=(?:как|почему|что|какие|какой|какая|когда|насколько|была\\s+ли|были\\s+ли)(?:\\s|$))");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String candidate = part
                    .replaceAll("^[\\s:;,.!?]+", "")
                    .replaceAll("[.。]+$", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            candidate = naturalExperienceFocus(candidate);
            for (String naturalPart : candidate.split("\\s*,\\s*")) {
                String natural = naturalPart.trim();
                if (natural.length() >= 8) {
                    result.add(limit(natural, 130));
                }
            }
        }
        return result.stream().distinct().limit(3).toList();
    }

    private String naturalExperienceFocus(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "";
        }
        clean = clean
                .replaceAll("(?iu)^офис,\\s*коттедж,\\s*склад\\s+или\\s+несколько\\s+квартир\\s*[—-]\\s*как\\s+соблюдались\\s+сроки\\s+и\\s+смета$", "соблюдение сроков и сметы на крупной площади")
                .replaceAll("(?iu)(^|\\s)как\\s+прошли\\s+замер(?=$|\\s|,)", "$1процесс замера")
                .replaceAll("(?iu)(^|\\s)как\\s+быстро\\s+отвечали,\\s*согласовывали\\s+график\\s+и\\s+изменения(?=$|\\s|,)", "$1скорость ответов и согласование графика")
                .replaceAll("(?iu)(^|\\s)как\\s+(?:здесь\\s+)?объединил[аи]?\\s+перегородки,\\s*стяжку\\s+и\\s+подготовку\\s+стен(?=$|\\s|,)", "$1объединение перегородок, стяжки и подготовки стен")
                .replaceAll("(?iu)(^|\\s)как\\s+контролировали\\s+практику\\s+и\\s+исправляли\\s+ошибки(?=$|\\s|,)", "$1контроль практики и исправление ошибок")
                .replaceAll("(?iu)(^|\\s)как\\s+подбирали\\s+форму,\\s*надписи\\s+и\\s+общую\\s+композицию(?=$|\\s|,)", "$1подбор формы, надписей и общей композиции")
                .replaceAll("(?iu)(^|\\s)какие\\s+недостатки\\s+нашли\\s+и\\s+как\\s+помог\\s+акт/список\\s+замечаний(?=$|\\s|,)", "$1найденные недостатки и список замечаний")
                .replaceAll("(?iu)(^|\\s)чистота\\s+объекта\\s+и\\s+когда\\s+получилось\\s+продолжить\\s+ремонт(?=$|\\s|,)", "$1чистота объекта и переход к следующему этапу ремонта")
                .replaceAll("(?iu)(^|\\s)почему\\s+выбрали\\s+этот\\s+вариант\\s+и\\s+как\\s+оценили\\s+качество\\s+изображения(?=$|\\s|,)", "$1выбор варианта и оценка качества изображения")
                .replaceAll("(?iu)(^|\\s)было\\s+ли\\s+удобно\\s+ждать(?=$|\\s|,)", "$1удобство ожидания")
                .replaceAll("(?iu)(^|\\s)объясняли\\s+ли\\s+ход\\s+работ(?=$|\\s|,)", "$1объяснение хода работ")
                .replaceAll("(?iu)(^|\\s)можно\\s+ли\\s+было\\s+задать\\s+вопросы?\\s+мастеру(?=$|\\s|,)", "$1возможность задать вопросы мастеру")
                .replaceAll("(?iu)(^|\\s)были\\s+ли\\s+детали\\s+в\\s+наличии\\s+или\\s+под\\s+заказ(?=$|\\s|,)", "$1наличие деталей или заказ")
                .replaceAll("(?iu)(^|\\s)как\\s+согласовали\\s+стоимость\\s+и\\s+сроки(?=$|\\s|,)", "$1согласование стоимости и сроков")
                .replaceAll("(?iu)(^|\\s)присылали\\s+ли\\s+фото/видео(?=$|\\s|,)", "$1фото или видео по ремонту")
                .replaceAll("(?iu)(^|\\s)показывали\\s+ли\\s+старую\\s+деталь(?=$|\\s|,)", "$1показ старой детали")
                .replaceAll("(?iu)(^|\\s)согласовывали\\s+ли\\s+дополнительные\\s+работы(?=$|\\s|,)", "$1согласование дополнительных работ")
                .replaceAll("(?iu)(^|\\s)была\\s+ли\\s+срочность(?=$|\\s|,)", "$1срочность обращения")
                .replaceAll("(?iu)(^|\\s)как\\s+быстро\\s+приняли\\s+машину(?=$|\\s|,)", "$1как быстро приняли машину")
                .replaceAll("(?iu)(^|\\s)как\\s+объяснили\\s+результат(?=$|\\s|,)", "$1объяснение результата")
                .replaceAll("(?iu)(^|\\s)какие\\s+детали\\s+меняли(?=$|\\s|,)", "$1какие детали меняли")
                .replaceAll("(?iu)(^|\\s)сколько\\s+заняло\\s+времени(?=$|\\s|,)", "$1сколько заняли работы")
                .replaceAll("(?iu)(^|\\s)как\\s+изменилась\\s+управляемость(?=$|\\s|,)", "$1как изменилась управляемость")
                .replaceAll("(?iu)(^|\\s)почему\\s+выбрали\\s+этот\\s+вариант(?=$|\\s|,)", "$1выбор варианта")
                .replaceAll("(?iu)(^|\\s)как\\s+оценили\\s+качество\\s+изображения(?=$|\\s|,)", "$1оценка качества изображения")
                .replaceAll("\\s+", " ")
                .trim();
        return clean;
    }

    private int firstColonIndex(String value) {
        int colon = value.indexOf(':');
        int fullWidthColon = value.indexOf('：');
        if (colon < 0) {
            return fullWidthColon;
        }
        if (fullWidthColon < 0) {
            return colon;
        }
        return Math.min(colon, fullWidthColon);
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
                "Используй конкретику только из availableSpecificDetails, companyDigest или orderContext.",
                "Марку или модель автомобиля используй только если она явно есть во входных данных; если её нет, пиши нейтрально: машина, авто, автомобиль.",
                "Цены, сроки, гарантии, имена мастеров и точные адреса не делай шаблонными подстановками.",
                "Не делай текст идеальным: допустимы простые фразы, короткие вставки вроде 'если честно', 'ну', 'по итогу', но без перебора.",
                "Если previousDraftToAvoid не пустой, новый draft должен заметно отличаться от него.",
                "Не называй текст черновиком и не проси клиента что-то дописать.",
                "sourceFacts верни использованные подтверждённые факты.",
                "safetyNotes верни коротко: что клиенту стоит проверить перед публикацией."
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
        payload.put("commonPhrasesCanUseSparingly", COMMON_REVIEW_PHRASES);
        payload.put("missingClientDetails", missingClientDetails(selectedIdea, request.orderContext(), fallbackFacts));
        payload.put("instruction", specificDetails.isEmpty()
                ? "Если подтверждённых клиентских деталей мало, пиши осторожно и не выдумывай товар, услугу, марку или модель авто."
                : "В draft нужна видимая конкретика из availableSpecificDetails. Общие фразы допустимы только как фон.");
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
        List<String> models = autoModelsFromSourceText(text).stream()
                .limit(4)
                .toList();
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
            missing.add("Марка/модель автомобиля не подтверждена: не добавляй конкретную модель, используй нейтрально машина/авто/автомобиль.");
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

    private BatchWritingGuide parseBatchWritingGuide(String responseText, List<ReviewGenerationSlot> slots) throws Exception {
        JsonNode root = objectMapper.readTree(responseText);
        Set<Long> allowedIds = slots == null
                ? Set.of()
                : slots.stream()
                .map(ReviewGenerationSlot::reviewId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        List<BatchIdeaWritingGuide> ideaGuides = new ArrayList<>();
        JsonNode items = root.path("ideaExpansion");
        if (items.isArray()) {
            items.forEach(item -> {
                Long reviewId = item.path("reviewId").canConvertToLong() ? item.path("reviewId").asLong() : null;
                if (reviewId == null || !allowedIds.contains(reviewId)) {
                    return;
                }
                BatchIdeaWritingGuide guide = new BatchIdeaWritingGuide(
                        reviewId,
                        guideStrings(item.path("angles"), 5, 120),
                        guideStrings(item.path("decisionCriteria"), 5, 120),
                        guideStrings(item.path("naturalDetails"), 5, 120),
                        guideStrings(item.path("avoidClaims"), 5, 120)
                );
                if (!guide.isEmpty()) {
                    ideaGuides.add(guide);
                }
            });
        }
        return new BatchWritingGuide(
                guideStrings(root.path("categoryLanguage"), 8, 120),
                guideStrings(root.path("termHints"), 8, 120),
                ideaGuides.stream().limit(30).toList(),
                guideStrings(root.path("diversityWarnings"), 8, 140),
                guideStrings(root.path("safetyNotes"), 8, 140)
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

    private ReputationBatchReviewDraftResult emptyOpenAiBatchResult(
            Long companyId,
            Long deepReportJobId,
            Long contentPackJobId,
            ReputationContentPack pack,
            String provider,
            String model,
            String errorMessage
    ) {
        return new ReputationBatchReviewDraftResult(
                companyId,
                pack == null || pack.researchSnapshot() == null ? "" : pack.researchSnapshot().companyName(),
                deepReportJobId,
                contentPackJobId,
                firstNonBlank(provider, "openai"),
                model,
                List.of(),
                List.of(errorMessage),
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

    private List<String> guideStrings(JsonNode node, int limit, int charLimit) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = cleanGuideText(item.asText(""), charLimit);
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values.stream()
                .distinct()
                .limit(limit)
                .toList();
    }

    private String cleanGuideText(String value, int charLimit) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return "";
        }
        String lower = clean.toLowerCase().replace('ё', 'е');
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.")) {
            return "";
        }
        if (lower.contains("конкурент") || lower.contains("лучше чем") || lower.contains("хуже чем")) {
            return "";
        }
        return limit(clean, charLimit);
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
        if (containsExperienceFocusQuestionLeakage(clean) || containsAwkwardPromptLikePhrase(clean)) {
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
            List<ReviewGenerationSlot> slots,
            ReviewGenerationBrief brief,
            Map<Long, BatchNarrativeMode> narrativeModes
    ) {
        Map<Long, ReviewGenerationSlot> slotById = new LinkedHashMap<>();
        for (ReviewGenerationSlot slot : slots) {
            if (slot.reviewId() != null) {
                slotById.putIfAbsent(slot.reviewId(), slot);
            }
        }
        Set<Long> allowedIds = slots.stream()
                .map(ReviewGenerationSlot::reviewId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, List<Long>> rejectedIdsByReason = new LinkedHashMap<>();
        List<ReputationBatchReviewDraftItem> prelimDrafts = new ArrayList<>();
        for (ReputationBatchReviewDraftItem item : result.drafts()) {
            ReviewGenerationSlot slot = item.reviewId() == null ? null : slotById.get(item.reviewId());
            ReputationBatchReviewDraftItem normalizedItem = normalizeAlternativeChoiceDraft(item, slot, result.companyName());
            String rejectionReason = batchDraftRejectionReason(normalizedItem, allowedIds, slotById, brief, narrativeModes);
            if (rejectionReason == null) {
                prelimDrafts.add(normalizedItem);
            } else {
                addBatchDraftRejection(rejectedIdsByReason, rejectionReason, normalizedItem.reviewId());
            }
        }
        List<ReputationBatchReviewDraftItem> drafts = keepDiverseBatchDrafts(prelimDrafts, slotById);
        if (drafts.size() < prelimDrafts.size()) {
            Set<Long> keptIds = drafts.stream()
                    .map(ReputationBatchReviewDraftItem::reviewId)
                    .filter(id -> id != null)
                    .collect(java.util.stream.Collectors.toSet());
            prelimDrafts.stream()
                    .filter(item -> item.reviewId() != null && !keptIds.contains(item.reviewId()))
                    .forEach(item -> addBatchDraftRejection(rejectedIdsByReason, "duplicate_weak_detail", item.reviewId()));
        }
        if (!rejectedIdsByReason.isEmpty()) {
            log.info(
                    "OPENAI_BATCH_FILTER_RESULT companyId={} parsedDrafts={} acceptedDrafts={} rejected={}",
                    result.companyId(),
                    result.drafts().size(),
                    drafts.size(),
                    rejectedIdsByReason
            );
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

    private ReputationBatchReviewDraftItem normalizeAlternativeChoiceDraft(
            ReputationBatchReviewDraftItem item,
            ReviewGenerationSlot slot,
            String companyName
    ) {
        if (item == null || item.draft() == null || item.draft().isBlank() || slot == null) {
            return item;
        }
        AlternativeChoice choice = alternativeChoice(slot);
        if (choice == null) {
            return item;
        }
        String draft = item.draft();
        String normalized = draft;
        for (String phrase : choice.phrases()) {
            normalized = normalized.replaceAll("(?iu)" + Pattern.quote(phrase), choice.chosen());
        }
        if (normalized.equals(draft)) {
            return item;
        }
        List<String> notes = new ArrayList<>(item.safetyNotes());
        notes.add("В черновике выбран один вариант из альтернатив: клиент должен подтвердить, что заказывал именно " + choice.chosen() + ".");
        List<String> facts = new ArrayList<>(item.sourceFacts());
        facts.add(choice.chosen());
        return new ReputationBatchReviewDraftItem(
                item.reviewId(),
                item.idea(),
                cleanGeneratedText(normalized, companyName),
                facts,
                notes
        );
    }

    private AlternativeChoice alternativeChoice(ReviewGenerationSlot slot) {
        String text = batchSlotText(slot).toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (text.contains("фотоовал") && text.contains("фото на стекле")) {
            return new AlternativeChoice(
                    "фотоовал",
                    List.of("фотоовал или фото на стекле", "фотоовал/фото на стекле")
            );
        }
        if (text.contains("фотоовал") && text.contains("портрет")) {
            return new AlternativeChoice(
                    "фотоовал",
                    List.of("фотоовал или портрет", "фотоовал/портрет")
            );
        }
        if (text.contains("стартер") && text.contains("генератор")) {
            return new AlternativeChoice(
                    "ремонт стартера",
                    List.of("ремонт стартера или генератора", "ремонте стартера или генератора", "стартер или генератор")
            );
        }
        if (text.contains("двигател") && text.contains("гбц")) {
            return new AlternativeChoice(
                    "ремонт двигателя",
                    List.of("ремонт двигателя или гбц", "ремонте двигателя или гбц", "двигатель или гбц")
            );
        }
        if (text.contains("семейн") && text.contains("двойн") && text.contains("памятник")) {
            return new AlternativeChoice(
                    "семейный памятник",
                    List.of("семейный/двойной памятник", "семейный или двойной памятник")
            );
        }
        return null;
    }

    private String batchDraftRejectionReason(
            ReputationBatchReviewDraftItem item,
            Set<Long> allowedIds,
            Map<Long, ReviewGenerationSlot> slotById,
            ReviewGenerationBrief brief,
            Map<Long, BatchNarrativeMode> narrativeModes
    ) {
        if (item.reviewId() == null) {
            return "missing_review_id";
        }
        if (!allowedIds.contains(item.reviewId())) {
            return "unexpected_review_id";
        }
        if (item.draft() == null || item.draft().isBlank()) {
            return "blank_draft";
        }
        if (containsPromptLeakage(item.draft())) {
            return "prompt_leakage";
        }
        if (looksLikeTechnicalNote(item.draft())) {
            return "technical_note";
        }
        if (containsExperienceFocusQuestionLeakage(item.draft())) {
            return "experience_focus_question_leakage";
        }
        if (containsAwkwardPromptLikePhrase(item.draft())) {
            return "awkward_prompt_phrase";
        }
        ReviewGenerationSlot slot = slotById.get(item.reviewId());
        if (containsUnprovidedAutoModel(item.draft(), slot, brief)) {
            return "unprovided_auto_model";
        }
        if (containsConflictingAutoServiceTerms(item.draft(), slot)) {
            return "conflicting_auto_terms";
        }
        BatchNarrativeMode mode = narrativeModes == null ? null : narrativeModes.get(item.reviewId());
        if (!matchesSlotRequiredIntent(item.draft(), slot, mode)) {
            return "slot_intent_mismatch";
        }
        if (!matchesRequiredMustCover(item.draft(), slot, mode)) {
            return "must_cover_missing";
        }
        return null;
    }

    private void addBatchDraftRejection(Map<String, List<Long>> rejectedIdsByReason, String reason, Long reviewId) {
        rejectedIdsByReason.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(reviewId);
    }

    private List<ReputationBatchReviewDraftItem> keepDiverseBatchDrafts(
            List<ReputationBatchReviewDraftItem> drafts,
            Map<Long, ReviewGenerationSlot> slotById
    ) {
        if (drafts == null || drafts.size() <= 1) {
            return drafts == null ? List.of() : drafts;
        }
        Map<String, Integer> weakDetailCounts = new LinkedHashMap<>();
        List<ReputationBatchReviewDraftItem> result = new ArrayList<>();
        for (ReputationBatchReviewDraftItem item : drafts) {
            List<String> weakKeys = weakRepeatedDetailKeys(item.draft());
            ReviewGenerationSlot slot = slotById == null ? null : slotById.get(item.reviewId());
            boolean repeatsWeakDetail = weakKeys.stream()
                    .anyMatch(key -> weakDetailCounts.getOrDefault(key, 0) > 0 && !slotStronglyRequiresWeakDetail(slot, key));
            if (repeatsWeakDetail) {
                continue;
            }
            result.add(item);
            weakKeys.forEach(key -> weakDetailCounts.put(key, weakDetailCounts.getOrDefault(key, 0) + 1));
        }
        return result;
    }

    private List<String> weakRepeatedDetailKeys(String draft) {
        String clean = draft == null ? "" : draft.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (clean.matches(".*(?:посмотр\\S*|сравн\\S*|свер\\S*)\\s+(?:образц\\S*|материал\\S*|выставк\\S*|вариант\\S*).*")
                || clean.matches(".*(?:образц\\S*|материал\\S*|выставк\\S*)\\s+(?:камн\\S*|гранит\\S*|оформлен\\S*).*")) {
            keys.add("monument_material_samples");
        }
        if (clean.matches(".*\\b(?:сначала|потом|после этого)\\b.*\\b(?:сначала|потом|после этого)\\b.*")
                && clean.matches(".*\\b(?:объяснил\\S*|согласовал\\S*|уточнил\\S*|показал\\S*)\\b.*")) {
            keys.add("linear_process_chain");
        }
        return keys.stream().distinct().toList();
    }

    private boolean slotStronglyRequiresWeakDetail(ReviewGenerationSlot slot, String key) {
        if (slot == null || key == null || key.isBlank()) {
            return false;
        }
        String slotText = normalizedAutoConflictText(batchSlotText(slot));
        if ("monument_material_samples".equals(key)) {
            return slotText.contains("образц")
                    || slotText.contains("материал")
                    || slotText.contains("выставк")
                    || (slotText.contains("выбор") && (slotText.contains("гранит") || slotText.contains("камн")));
        }
        return false;
    }

    private boolean containsExperienceFocusQuestionLeakage(String draft) {
        String clean = draft == null ? "" : draft.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return false;
        }
        return clean.contains("было ли ")
                || clean.contains("были ли ")
                || clean.contains("была ли ")
                || clean.contains("присылали ли ")
                || clean.contains("показывали ли ")
                || clean.contains("согласовывали ли ")
                || clean.contains("объясняли ли ")
                || clean.contains("можно ли было ")
                || clean.contains("как согласовали стоимость")
                || clean.contains("как согласовали сроки")
                || clean.contains("как согласовали работы")
                || clean.contains("как объяснили результат")
                || clean.contains("какие детали меняли, сколько заняло времени");
    }

    private boolean containsAwkwardPromptLikePhrase(String draft) {
        String clean = draft == null ? "" : draft.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank()) {
            return false;
        }
        return clean.contains("с работе ")
                || clean.contains("с изготовление ")
                || clean.contains("с ремонте ")
                || clean.contains("отдельно разобрали:")
                || clean.contains("обсудили задачу:")
                || clean.contains("сначала обсудили задачу")
                || clean.contains("обсудили тему:")
                || clean.contains("сначала обсудили тему")
                || clean.contains("обозначили тему")
                || clean.contains("потом отдельно уточнили")
                || clean.contains("основную тему обозначил")
                || clean.contains("основную тему обозначили")
                || clean.contains("практические детали объяснили нормальным языком")
                || clean.contains("решение не выглядело выбором наугад")
                || clean.contains("дали понятные ориентиры:")
                || clean.matches(".*\\b(?:обсудили|обсуждали|разобрали|уточнили|обозначили)\\s+(?:задачу|тему|вопрос)\\s*:.*")
                || clean.matches(".*\\b(?:обсудили|обсуждали|уточнили|разобрали)\\s+(?:детские\\s+квесты|хоррор-?квесты|квесты\\s+с\\s+актерами|квесты\\s+с\\s+актёрами)(?:\\.|,|$).*")
                || clean.matches(".*(?:^|\\s)фото\\s+(стойк|шаров|ступич|рулев|опорн|втулк|сайлент).*")
                || clean.matches(".*(?:^|\\s)по\\s+(семейный|двойной|портретная|механизированная|полусухая|комплексное|изготовление)(?=$|\\s|/).*");
    }

    private boolean containsUnprovidedAutoModel(String draft, ReviewGenerationSlot slot, ReviewGenerationBrief brief) {
        if (!shouldCheckUnprovidedAutoModel(slot, brief)) {
            return false;
        }
        List<String> mentionedModels = autoModelsFromSourceText(draft);
        if (mentionedModels.isEmpty()) {
            return false;
        }
        String allowedSource = slot == null ? "" : batchSlotText(slot);
        String allowedNormalized = normalizedAutoModelText(allowedSource);
        return mentionedModels.stream()
                .map(this::normalizedAutoModelText)
                .anyMatch(model -> !allowedNormalized.contains(model));
    }

    private boolean shouldCheckUnprovidedAutoModel(ReviewGenerationSlot slot, ReviewGenerationBrief brief) {
        String businessType = normalizedAutoConflictText(brief == null ? "" : brief.businessType());
        if ("auto_service".equals(businessType)) {
            return true;
        }
        String briefText = normalizedAutoConflictText(briefAutoContextText(brief));
        String slotText = normalizedAutoConflictText(slot == null ? "" : batchSlotText(slot));
        return hasExplicitAutoBusinessSignal(briefText) || looksLikeAutoReviewSlot(slotText);
    }

    private String briefAutoContextText(ReviewGenerationBrief brief) {
        if (brief == null) {
            return "";
        }
        List<String> values = new ArrayList<>();
        values.add(brief.businessType());
        values.add(brief.category());
        addAll(values, brief.services());
        addAll(values, brief.products());
        addAll(values, brief.reviewIdeas());
        addAll(values, brief.allowedScenarioTypes());
        return String.join(" ", values);
    }

    private boolean hasExplicitAutoBusinessSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("автосервис")
                || text.contains("авто-сервис")
                || text.contains("автомоб")
                || text.contains("ремонт авто")
                || text.contains("ремонт машин")
                || text.contains("диагностик авто")
                || text.contains("диагностик машин")
                || text.contains("подготовка авто")
                || text.contains("подготовка машин");
    }

    private boolean looksLikeAutoReviewSlot(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (hasExplicitAutoBusinessSignal(text)) {
            return true;
        }
        if (containsAnyAutoTerm(text, AUTO_CHASSIS_TERMS) || containsAnyAutoTerm(text, AUTO_ENGINE_TERMS)) {
            return true;
        }
        if (containsAnyAutoTerm(text, List.of(
                "стартер", "аккумулятор", "клемм", "реле стартера",
                "щетк генератор", "щетки генератор", "ремень генератор",
                "зарядка генератор", "просадка напряж"
        ))) {
            return true;
        }
        return text.contains("комплексное то")
                || text.contains("плановое то")
                || text.contains("подбор запчаст")
                || text.contains("запчасти для авто")
                || text.contains("запчасти для машин")
                || text.contains("тормозные колод")
                || text.contains("давление в шинах")
                || text.contains("масло и фильтр");
    }

    private boolean containsConflictingAutoServiceTerms(String draft, ReviewGenerationSlot slot) {
        if (slot == null || draft == null || draft.isBlank()) {
            return false;
        }
        String slotText = normalizedAutoConflictText(batchSlotText(slot));
        String draftText = normalizedAutoConflictText(draft);
        if (!looksLikeAutoRepairSlot(slotText)) {
            return false;
        }
        return hasUnexpectedAutoTermGroup(draftText, slotText, AUTO_CHASSIS_TERMS)
                || hasUnexpectedAutoTermGroup(draftText, slotText, AUTO_ELECTRIC_TERMS)
                || hasUnexpectedAutoTermGroup(draftText, slotText, AUTO_ENGINE_TERMS);
    }

    private boolean looksLikeAutoRepairSlot(String slotText) {
        return containsAnyAutoTerm(slotText, AUTO_CHASSIS_TERMS)
                || containsAnyAutoTerm(slotText, AUTO_ELECTRIC_TERMS)
                || containsAnyAutoTerm(slotText, AUTO_ENGINE_TERMS);
    }

    private boolean hasUnexpectedAutoTermGroup(String draftText, String slotText, List<String> terms) {
        return containsAnyAutoTerm(draftText, terms) && !containsAnyAutoTerm(slotText, terms);
    }

    private boolean containsAnyAutoTerm(String text, List<String> terms) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return terms.stream().anyMatch(text::contains);
    }

    private String normalizedAutoConflictText(String value) {
        return value == null
                ? ""
                : value.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean matchesSlotRequiredIntent(String draft, ReviewGenerationSlot slot, BatchNarrativeMode mode) {
        if (slot == null || draft == null || draft.isBlank()) {
            return true;
        }
        String cleanDraft = normalizedIntentText(draft);
        if (cleanDraft.isBlank()) {
            return false;
        }
        if (isLightNarrativeMode(mode)) {
            return matchesLightSlotIntent(draft, slot);
        }

        List<String> primaryAnchors = new ArrayList<>();
        primaryAnchors.add(slot.service());
        primaryAnchors.add(slot.product());
        primaryAnchors.add(slot.extraDetail());
        primaryAnchors.add(topicBeforeColon(slot.theme()));
        if (slot.mustUse() != null && !slot.mustUse().isEmpty()) {
            primaryAnchors.add(slot.mustUse().getFirst());
        }

        List<String> primarySignals = intentSignals(primaryAnchors);
        if (!primarySignals.isEmpty()) {
            return primarySignals.stream().anyMatch(cleanDraft::contains);
        }

        List<String> fallbackAnchors = new ArrayList<>();
        addAll(fallbackAnchors, slot.mustUse());
        addAll(fallbackAnchors, slot.mayUse());
        List<String> fallbackSignals = intentSignals(fallbackAnchors);
        return fallbackSignals.isEmpty() || fallbackSignals.stream().anyMatch(cleanDraft::contains);
    }

    private boolean matchesRequiredMustCover(String draft, ReviewGenerationSlot slot, BatchNarrativeMode mode) {
        if (slot == null || slot.mustUse() == null || slot.mustUse().isEmpty()) {
            return true;
        }
        String cleanDraft = normalizedIntentText(draft);
        if (cleanDraft.isBlank()) {
            return false;
        }
        List<String> concreteMusts = new ArrayList<>();
        List<String> signalSources = new ArrayList<>();
        for (String must : slot.mustUse()) {
            if (must == null || must.isBlank() || looksLikeLocationOnly(must)) {
                continue;
            }
            if (looksLikeConcreteMustCoverName(must)) {
                concreteMusts.add(normalizedIntentText(must));
            }
            signalSources.add(must);
        }
        if (concreteMusts.stream().anyMatch(cleanDraft::contains)) {
            return true;
        }
        List<String> signals = intentSignals(signalSources);
        if (!signals.isEmpty()) {
            return signals.stream().anyMatch(cleanDraft::contains);
        }
        if (isLightNarrativeMode(mode)) {
            return concreteMusts.isEmpty();
        }
        for (String concreteMust : concreteMusts) {
            if (!concreteMust.isBlank() && cleanDraft.contains(concreteMust)) {
                return true;
            }
        }
        return concreteMusts.isEmpty();
    }

    private boolean isLightNarrativeMode(BatchNarrativeMode mode) {
        if (mode == null || mode.requiredDepth() == null) {
            return false;
        }
        return mode.requiredDepth().startsWith("light:");
    }

    private boolean matchesLightSlotIntent(String draft, ReviewGenerationSlot slot) {
        String cleanDraft = normalizedIntentText(draft);
        String slotText = normalizedAutoConflictText(batchSlotText(slot));
        List<String> primarySignals = intentSignals(List.of(slot.service(), slot.product(), slot.extraDetail()));
        if (requiresSpecificLightIntent(slotText) && !primarySignals.isEmpty()) {
            return primarySignals.stream().anyMatch(cleanDraft::contains);
        }
        if (!primarySignals.isEmpty() && primarySignals.stream().anyMatch(cleanDraft::contains)) {
            return true;
        }
        if (slotText.contains("квест") || slotText.contains("лазертаг") || slotText.contains("праздник")
                || slotText.contains("аниматор") || slotText.contains("актер") || slotText.contains("актёр")) {
            return containsAnyNormalized(cleanDraft, List.of("квест", "игр", "праздник", "дет", "команд", "актер", "актер", "аниматор", "чай"));
        }
        if (looksLikeAutoRepairSlot(slotText)
                || slotText.contains("авто") || slotText.contains("машин") || slotText.contains("запчаст")
                || slotText.contains("масл") || slotText.contains("то")) {
            return containsAnyNormalized(cleanDraft, List.of("машин", "авто", "сервис", "ремонт", "то", "диагност", "запчаст", "масл", "ходов", "стартер", "двигател"));
        }
        if (slotText.contains("штукатур") || slotText.contains("стяжк") || slotText.contains("white box")
                || slotText.contains("квартир") || slotText.contains("ремонт")) {
            return containsAnyNormalized(cleanDraft, List.of("ремонт", "квартир", "объект", "стен", "стяжк", "штукатур", "мастер", "whitebox"));
        }
        if (slotText.contains("памятник") || slotText.contains("гранит") || slotText.contains("фотоовал")) {
            return containsAnyNormalized(cleanDraft, List.of("памятник", "заказ", "камн", "гранит", "портрет", "фото"));
        }
        return true;
    }

    private boolean requiresSpecificLightIntent(String slotText) {
        if (slotText == null || slotText.isBlank()) {
            return false;
        }
        return slotText.contains("стяжк")
                || slotText.contains("штукатур")
                || slotText.contains("white box")
                || slotText.contains("памятник")
                || slotText.contains("гранит")
                || slotText.contains("фотоовал")
                || slotText.contains("фото на стекле")
                || slotText.contains("маникюр")
                || slotText.contains("ногт")
                || slotText.contains("наращив")
                || slotText.contains("моделир")
                || slotText.contains("курс")
                || slotText.contains("обучен");
    }

    private boolean containsAnyNormalized(String normalizedText, List<String> rawSignals) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return rawSignals.stream()
                .map(this::normalizedIntentText)
                .anyMatch(normalizedText::contains);
    }

    private boolean looksLikeConcreteMustCoverName(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[«»\"“”]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isBlank() || clean.length() > 42 || clean.contains(",") || clean.contains("/")) {
            return false;
        }
        String lower = clean.toLowerCase().replace('ё', 'е');
        if (lower.matches(".*\\b(квесты|детские|хоррор|актер|актёр|аниматор|дни рождения|чайная зона|замер|смета|ремонт|диагност|подбор|запчаст|работа|изготовление|полусухая|механизированная|первичная|подготовка|дорога|наличии|заказ)\\b.*")) {
            return false;
        }
        return value.contains("«")
                || clean.matches("(?U)[\\p{Lu}A-Z0-9][\\p{L}0-9+-]*(?:\\s+[\\p{Lu}A-Z0-9][\\p{L}0-9+-]*){0,2}");
    }

    private String topicBeforeColon(String value) {
        String clean = value == null ? "" : value.trim();
        int colon = firstColonIndex(clean);
        return colon > 0 ? clean.substring(0, colon).trim() : clean;
    }

    private List<String> intentSignals(List<String> values) {
        List<String> signals = new ArrayList<>();
        if (values == null) {
            return signals;
        }
        for (String value : values) {
            String clean = value == null ? "" : value.toLowerCase()
                    .replace('ё', 'е')
                    .replaceAll("\\s+", " ")
                    .trim();
            if (clean.isBlank() || looksLikeLocationOnly(clean)) {
                continue;
            }
            if (clean.contains("полусух") || clean.contains("стяжк")) {
                signals.addAll(List.of("полусух", "стяжк"));
            }
            if (clean.contains("механизирован") || clean.contains("штукатур")) {
                signals.addAll(List.of("механизирован", "штукатур"));
            }
            if (clean.contains("white box") || clean.contains("вайт бокс")) {
                signals.addAll(List.of("whitebox", "вайтбокс", "профильн", "мастер", "перегород", "подготовкстен"));
            }
            if (clean.contains("коммуникац") || clean.contains("менеджер") || clean.contains("прораб")
                    || clean.contains("график") || clean.contains("изменен")) {
                signals.addAll(List.of("коммуникац", "менеджер", "прораб", "график", "соглас", "отвеч"));
            }
            if (clean.contains("замер") || clean.contains("смет")) {
                signals.addAll(List.of("замер", "смет"));
            }
            if (clean.contains("приемк") || clean.contains("приёмк")) {
                signals.addAll(List.of("приемк", "приемк"));
            }
            if (clean.contains("договор") || clean.contains("безнал")) {
                signals.addAll(List.of("договор", "безнал"));
            }
            if (clean.contains("крупн") && clean.contains("площад")) {
                signals.addAll(List.of("крупн", "площад", "объект"));
            }
            if (clean.contains("ходов") || clean.contains("подвес") || clean.contains("стук") || clean.contains("скрип")
                    || clean.contains("управляем") || clean.contains("рулев")) {
                signals.addAll(List.of("ходов", "подвес", "стук", "скрип", "рулев", "шаров", "стойк", "втулк", "сайлент", "ступич", "опорн"));
            }
            if (clean.contains("первичн") || clean.contains("диагност")) {
                signals.addAll(List.of("первичн", "диагност"));
            }
            if (clean.contains("масл") || clean.contains("то") || clean.contains("фильтр")) {
                signals.addAll(List.of("масл", "фильтр", "расходник", " то "));
            }
            if (clean.contains("двигател") || clean.contains("гбц") || clean.contains("дефектов")) {
                signals.addAll(List.of("двигател", "гбц", "дефектов", "свеч", "катуш", "грм"));
            }
            if (clean.contains("стартер") || clean.contains("генератор") || clean.contains("аккумулятор")) {
                signals.addAll(List.of("стартер", "генератор", "аккумулятор", "клемм", "масса"));
            }
            if (clean.contains("запчаст")) {
                signals.addAll(List.of("запчаст", "детал", "налич", "заказ"));
            }
            if (clean.contains("дальн") || clean.contains("поезд") || clean.contains("дорог")) {
                signals.addAll(List.of("дальн", "поезд", "дорог", "трасс"));
            }
            if (clean.contains("ожидан") || clean.contains("клиентск") || clean.contains("зон")) {
                signals.addAll(List.of("ожидан", "ждал", "зон", "вопрос"));
            }
            if (clean.contains("памятник") || clean.contains("гранит") || clean.contains("фотоовал") || clean.contains("гравиров")) {
                signals.addAll(List.of("памятник", "гранит", "фотоовал", "гравиров", "портрет", "макет", "камн"));
            }
            if (clean.contains("квест") || clean.contains("лазертаг") || clean.contains("праздник") || clean.contains("аниматор")) {
                signals.addAll(List.of("квест", "лазертаг", "праздник", "аниматор", "чайнойзон"));
            }
            if (clean.contains("администратор") || clean.contains("брон") || clean.contains("предоплат")
                    || clean.contains("возврат") || clean.contains("тайминг") || clean.contains("правил")
                    || clean.contains("оплат") || clean.contains("цен")) {
                signals.addAll(List.of("администратор", "брон", "предоплат", "возврат", "тайминг", "правил", "оплат", "цен", "услов"));
            }
            if (clean.contains("хоррор") || clean.contains("страш") || clean.contains("контакт")
                    || clean.contains("возраст")) {
                signals.addAll(List.of("хоррор", "страш", "контакт", "возраст", "актер", "актер"));
            }
            if (clean.contains("филиал") || clean.contains("вывеск") || clean.contains("вход")
                    || clean.contains("парков") || clean.contains("навигац")) {
                signals.addAll(List.of("филиал", "вывеск", "вход", "парков", "навигац", "нашли", "добрат"));
            }
            if (clean.contains("маникюр") || clean.contains("ногт") || clean.contains("наращив")
                    || clean.contains("моделир") || clean.contains("педикюр") || clean.contains("покрыт")
                    || clean.contains("укреплен") || clean.contains("коррекц")) {
                signals.addAll(List.of("маникюр", "ногт", "наращив", "моделир", "педикюр", "покрыт", "укреплен", "коррекц", "форма", "аккурат", "носк"));
            }
            if (clean.contains("курс") || clean.contains("обучен") || clean.contains("преподав")
                    || clean.contains("практик") || clean.contains("моделях") || clean.contains("ошибк")) {
                signals.addAll(List.of("курс", "обучен", "преподав", "практик", "модел", "ошибк", "занят", "учеб"));
            }
            if (clean.contains("студи") || clean.contains("рабоч") || clean.contains("советск")) {
                signals.addAll(List.of("студи", "рабоч", "мест", "советск", "чист", "атмосфер"));
            }
        }
        return signals.stream()
                .map(this::normalizedIntentText)
                .filter(value -> value.length() >= 3)
                .distinct()
                .toList();
    }

    private boolean looksLikeLocationOnly(String value) {
        String clean = value == null ? "" : value.toLowerCase().replace('ё', 'е');
        return clean.matches(".*\\b(район|мжк|улиц|проспект|город|владивосток|новосибирск|ставропол|иркутск|ангарск)\\b.*")
                && !clean.matches(".*(диагност|ремонт|замен|подбор|стяжк|штукатур|памятник|квест|курс|маникюр|охран).*");
    }

    private String normalizedIntentText(String value) {
        return value == null
                ? ""
                : value.toLowerCase()
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", "");
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

    private String shortLogText(String value) {
        return limit(value, 180);
    }

    private List<Long> slotIds(List<ReviewGenerationSlot> slots) {
        if (slots == null) {
            return List.of();
        }
        return slots.stream()
                .map(ReviewGenerationSlot::reviewId)
                .toList();
    }

    private record AlternativeChoice(String chosen, List<String> phrases) {
    }

    private record BatchWritingGuide(
            List<String> categoryLanguage,
            List<String> termHints,
            List<BatchIdeaWritingGuide> ideaExpansion,
            List<String> diversityWarnings,
            List<String> safetyNotes
    ) {
        private BatchWritingGuide {
            categoryLanguage = categoryLanguage == null ? List.of() : categoryLanguage;
            termHints = termHints == null ? List.of() : termHints;
            ideaExpansion = ideaExpansion == null ? List.of() : ideaExpansion;
            diversityWarnings = diversityWarnings == null ? List.of() : diversityWarnings;
            safetyNotes = safetyNotes == null ? List.of() : safetyNotes;
        }

        private static BatchWritingGuide empty() {
            return new BatchWritingGuide(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        private boolean isEmpty() {
            return categoryLanguage.isEmpty()
                    && termHints.isEmpty()
                    && ideaExpansion.isEmpty()
                    && diversityWarnings.isEmpty()
                    && safetyNotes.isEmpty();
        }
    }

    private record BatchIdeaWritingGuide(
            Long reviewId,
            List<String> angles,
            List<String> decisionCriteria,
            List<String> naturalDetails,
            List<String> avoidClaims
    ) {
        private BatchIdeaWritingGuide {
            angles = angles == null ? List.of() : angles;
            decisionCriteria = decisionCriteria == null ? List.of() : decisionCriteria;
            naturalDetails = naturalDetails == null ? List.of() : naturalDetails;
            avoidClaims = avoidClaims == null ? List.of() : avoidClaims;
        }

        private boolean isEmpty() {
            return angles.isEmpty()
                    && decisionCriteria.isEmpty()
                    && naturalDetails.isEmpty()
                    && avoidClaims.isEmpty();
        }
    }

    private record BatchNarrativeMode(
            String key,
            String instruction,
            String detailBudget,
            String requiredDepth,
            String processVerbPolicy
    ) {
    }

    private record PromptVariant(
            String structure,
            String voice,
            String anchorPolicy,
            String openingPolicy,
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
                    COMMON_PHRASE_POLICIES.get(random.nextInt(COMMON_PHRASE_POLICIES.size())),
                    TEMPERATURES.get(random.nextInt(TEMPERATURES.size()))
            );
        }
    }

    private final class BatchExampleDetailsPicker {
        private final String variationNonce;

        private BatchExampleDetailsPicker(String variationNonce) {
            this.variationNonce = variationNonce == null ? "" : variationNonce;
        }

        private List<String> detailsFor(ReviewGenerationSlot slot, List<String> details) {
            List<String> clean = details == null
                    ? List.of()
                    : details.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (clean.isEmpty()) {
                return List.of();
            }
            String previous = normalizedExampleText(slot == null ? "" : slot.previousDraft());
            List<String> fresh = orderedForSlot(slot, clean).stream()
                    .filter(candidate -> !previous.contains(normalizedExampleText(candidate)))
                    .toList();
            return fresh.isEmpty() ? orderedForSlot(slot, clean) : fresh;
        }

        private List<String> orderedForSlot(ReviewGenerationSlot slot, List<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            int offset = Math.floorMod((variationNonce + "|" + (slot == null ? "" : slot.reviewId())).hashCode(), values.size());
            List<String> result = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                result.add(values.get((offset + index) % values.size()));
            }
            return result;
        }

        private String normalizedExampleText(String value) {
            return value == null
                    ? ""
                    : value.toLowerCase()
                    .replace('ё', 'е')
                    .replaceAll("[^\\p{L}\\p{N}]+", "");
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
