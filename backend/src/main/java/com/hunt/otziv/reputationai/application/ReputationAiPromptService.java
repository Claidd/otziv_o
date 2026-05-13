package com.hunt.otziv.reputationai.application;

import com.hunt.otziv.reputationai.domain.ReputationAiPrompt;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptPreview;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptPreset;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptValidation;
import com.hunt.otziv.reputationai.domain.ReputationAiPromptVersion;
import com.hunt.otziv.reputationai.persistence.ReputationAiPromptHistoryEntity;
import com.hunt.otziv.reputationai.persistence.ReputationAiPromptHistoryRepository;
import com.hunt.otziv.reputationai.persistence.ReputationAiPromptOverrideEntity;
import com.hunt.otziv.reputationai.persistence.ReputationAiPromptOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReputationAiPromptService {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{[A-Za-z0-9_.]+\\}\\}");

    @Value("classpath:reputation-ai/deep-company-research-instructions.md")
    private Resource deepReportInstructionsPrompt;

    @Value("classpath:reputation-ai/deep-company-research-input.md")
    private Resource deepReportInputPrompt;

    private final ReputationAiPromptOverrideRepository overrideRepository;
    private final ReputationAiPromptHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public List<ReputationAiPrompt> listPrompts() {
        return definitions().stream()
                .map(this::toPrompt)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReputationAiPrompt getPrompt(String key) {
        return toPrompt(definition(key));
    }

    @Transactional(readOnly = true)
    public String content(String key) {
        PromptDefinition definition = definition(key);
        return overrideRepository.findById(key)
                .map(ReputationAiPromptOverrideEntity::getContent)
                .orElseGet(definition::defaultContent);
    }

    @Transactional
    public ReputationAiPrompt update(String key, String content, String actor) {
        PromptDefinition definition = definition(key);
        String cleanContent = content == null ? "" : content.trim();
        if (cleanContent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Текст промпта не может быть пустым");
        }
        ReputationAiPromptValidation validation = validate(definition, cleanContent);
        if (!validation.valid()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "В промпте не хватает обязательных маркеров: " + String.join(", ", validation.missingPlaceholders())
            );
        }

        String previousContent = content(key);
        ReputationAiPromptOverrideEntity entity = overrideRepository.findById(key)
                .orElseGet(ReputationAiPromptOverrideEntity::new);
        entity.setPromptKey(key);
        entity.setContent(cleanContent);
        overrideRepository.save(entity);
        saveHistory(key, "update", actor, previousContent, cleanContent);
        return toPrompt(definition);
    }

    @Transactional
    public ReputationAiPrompt reset(String key, String actor) {
        PromptDefinition definition = definition(key);
        String previousContent = content(key);
        overrideRepository.deleteById(key);
        saveHistory(key, "reset", actor, previousContent, definition.defaultContent());
        return toPrompt(definition);
    }

    @Transactional
    public ReputationAiPrompt applyPreset(String key, String presetKey, String actor) {
        PromptDefinition definition = definition(key);
        PromptPreset preset = preset(definition, presetKey);
        String previousContent = content(key);
        if ("stable".equals(preset.key())) {
            overrideRepository.deleteById(key);
            saveHistory(key, "preset:stable", actor, previousContent, definition.defaultContent());
            return toPrompt(definition);
        }

        String presetContent = presetContent(definition.defaultContent(), preset);
        ReputationAiPromptValidation validation = validate(definition, presetContent);
        if (!validation.valid()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Пресет не прошёл проверку обязательных маркеров: " + String.join(", ", validation.missingPlaceholders())
            );
        }

        ReputationAiPromptOverrideEntity entity = overrideRepository.findById(key)
                .orElseGet(ReputationAiPromptOverrideEntity::new);
        entity.setPromptKey(key);
        entity.setContent(presetContent);
        overrideRepository.save(entity);
        saveHistory(key, "preset:" + preset.key(), actor, previousContent, presetContent);
        return toPrompt(definition);
    }

    @Transactional(readOnly = true)
    public ReputationAiPromptValidation validate(String key, String content) {
        return validate(definition(key), content == null ? "" : content.trim());
    }

    @Transactional(readOnly = true)
    public List<ReputationAiPromptVersion> history(String key, int limit) {
        definition(key);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return historyRepository.findByPromptKeyOrderByCreatedAtDesc(key).stream()
                .limit(safeLimit)
                .map(this::toVersion)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReputationAiPromptPreview preview(String key, String content) {
        PromptDefinition definition = definition(key);
        String template = content == null || content.isBlank() ? content(key) : content.trim();
        Map<String, String> sampleValues = sampleValues();
        List<String> replaced = new ArrayList<>();
        String rendered = template;

        for (Map.Entry<String, String> entry : sampleValues.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (rendered.contains(placeholder)) {
                rendered = rendered.replace(placeholder, entry.getValue());
                replaced.add(placeholder);
            }
        }

        List<String> unresolved = unresolvedPlaceholders(rendered);
        List<String> warnings = new ArrayList<>();
        ReputationAiPromptValidation validation = validate(definition, template);
        if (!validation.valid()) {
            warnings.add("В исходном шаблоне не хватает обязательных маркеров: " + String.join(", ", validation.missingPlaceholders()));
        }
        if (!unresolved.isEmpty()) {
            warnings.add("Остались неподставленные маркеры: " + String.join(", ", unresolved));
        }

        return new ReputationAiPromptPreview(
                key,
                "Демо-компания: Студия Сервис, Иркутск",
                rendered,
                replaced,
                unresolved,
                warnings
        );
    }

    private ReputationAiPrompt toPrompt(PromptDefinition definition) {
        Optional<ReputationAiPromptOverrideEntity> override = overrideRepository.findById(definition.key());
        String defaultContent = definition.defaultContent();
        String content = override.map(ReputationAiPromptOverrideEntity::getContent).orElse(defaultContent);
        return new ReputationAiPrompt(
                definition.key(),
                definition.title(),
                definition.description(),
                content,
                defaultContent,
                override.isPresent(),
                override.map(ReputationAiPromptOverrideEntity::getUpdatedAt).orElse(null),
                definition.requiredPlaceholders(),
                presets(definition).stream()
                        .map(preset -> new ReputationAiPromptPreset(preset.key(), preset.title(), preset.description()))
                        .toList()
        );
    }

    private PromptDefinition definition(String key) {
        return definitions().stream()
                .filter(definition -> definition.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Промпт не найден"));
    }

    private PromptPreset preset(PromptDefinition definition, String presetKey) {
        return presets(definition).stream()
                .filter(preset -> preset.key().equals(presetKey))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пресет промпта не найден"));
    }

    private List<PromptPreset> presets(PromptDefinition definition) {
        if (definition.key().startsWith("content_pack.")) {
            return List.of(
                    new PromptPreset("stable", "Дефолт", "Вернуть дефолтный текст из проекта.", List.of()),
                    new PromptPreset("strict_facts", "Строгий фактчек", "Жёстче запрещает непроверенные факты, цены и обещания.", strictFactsPresetRules()),
                    new PromptPreset("concise_pack", "Короткий пакет", "Просит меньше воды и более компактные тексты.", conciseContentPackPresetRules()),
                    new PromptPreset("expressive_pack", "Больше маркетинга", "Делает тексты живее, но сохраняет фактчек.", expressiveContentPackPresetRules())
            );
        }
        if (definition.key().startsWith("deep_report.")) {
            return List.of(
                    new PromptPreset("stable", "Дефолт", "Вернуть дефолтный текст из проекта.", List.of()),
                    new PromptPreset("strict_facts", "Строгий фактчек", "Больше осторожности к источникам, городам, ценам и отзывам.", strictFactsPresetRules()),
                    new PromptPreset("short_report", "Короткий отчёт", "Сжимает отчёт, оставляя главные выводы, риски и источники.", shortReportPresetRules()),
                    new PromptPreset("deep_evidence", "Больше доказательств", "Просит подробнее связывать выводы с источниками и сомнениями.", deepEvidencePresetRules())
            );
        }
        return List.of(new PromptPreset("stable", "Дефолт", "Вернуть дефолтный текст из проекта.", List.of()));
    }

    private String presetContent(String defaultContent, PromptPreset preset) {
        if (preset.rules().isEmpty()) {
            return defaultContent;
        }
        return defaultContent.trim() + "\n\n"
                + "Дополнительный пресет: " + preset.title() + "\n"
                + String.join("\n", preset.rules());
    }

    private List<String> strictFactsPresetRules() {
        return List.of(
                "- Усиль фактчек: любые адреса, цены, сроки, гарантии и рейтинги используй только при прямом подтверждении во входных данных.",
                "- Если факт не подтверждён, явно пометь его как требующий ручной проверки, не превращай его в рекламное утверждение.",
                "- Не делай выводов по каталогам конкурентов и сомнительным агрегаторам; такие URL используй только как сигнал для проверки."
        );
    }

    private List<String> shortReportPresetRules() {
        return List.of(
                "- Сжимай результат: меньше общих рассуждений, больше коротких выводов, таблиц и списков.",
                "- Приоритет секций: краткая сводка, услуги/цены, репутационные темы, риски данных, идеи для контента.",
                "- Если данных мало, не расширяй текст предположениями; лучше коротко перечисли, что нужно уточнить."
        );
    }

    private List<String> deepEvidencePresetRules() {
        return List.of(
                "- Для каждого сильного вывода указывай, какие источники или фрагменты входных данных его подтверждают.",
                "- Явно разделяй подтверждённые факты, косвенные признаки и сомнения.",
                "- Отдельно перечисляй противоречия между сайтом, картами, отзывами и CRM."
        );
    }

    private List<String> conciseContentPackPresetRules() {
        return List.of(
                "- Делай AI-пакет компактнее: меньше повторов, короче рекламные тексты и посты, больше готовых формулировок.",
                "- Каждый блок должен давать разные смыслы; не повторяй один и тот же плюс в УТП, рекламе и постах.",
                "- Сохраняй только конкретику, которая поможет менеджеру быстро использовать текст."
        );
    }

    private List<String> expressiveContentPackPresetRules() {
        return List.of(
                "- Пиши живее и ближе к клиентскому сценарию: проблема, спокойное решение, доказательство, мягкий следующий шаг.",
                "- Рекламные тексты делай более продающими, но не добавляй неподтверждённые факты, гарантии, цены или превосходные степени.",
                "- УТП должны звучать как речь компании, а не как пересказ источников."
        );
    }

    private List<PromptDefinition> definitions() {
        return List.of(
                new PromptDefinition(
                        ReputationAiPromptKeys.DEEP_REPORT_INSTRUCTIONS,
                        "Глубокий отчёт: системные инструкции",
                        "Главные правила исследования: источники, фактчек, секции, ограничения и формат результата.",
                        readPrompt(deepReportInstructionsPrompt),
                        List.of()
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.DEEP_REPORT_INPUT,
                        "Глубокий отчёт: входной шаблон",
                        "Шаблон пользовательского input с CRM-фактами, ручными данными и приоритетными URL.",
                        readPrompt(deepReportInputPrompt),
                        List.of("{{companyFacts}}", "{{manualDescription}}", "{{productsOrServices}}", "{{publicUrls}}", "{{crmPriorityUrls}}")
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.DEEP_REPORT_SOURCE_REFRESH_INSTRUCTIONS,
                        "Обновление источников",
                        "Инструкции для режима, который обновляет только список публичных URL и сомнения.",
                        """
                        Ты обновляешь только источники глубокого отчёта о репутации компании.
                        Используй web search, официальный сайт, карты, справочники, отзовики и соцсети.
                        Не переписывай текст отчёта и не добавляй sections. Верни только JSON по схеме: sources и warnings.
                        sources должны быть обычными публичными URL без citation placeholders и без utm_source=openai.
                        В note коротко напиши, какие факты источник подтверждает или почему он спорный.
                        """.stripIndent().trim(),
                        List.of()
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.DEEP_REPORT_REBUILD_TEXT_INSTRUCTIONS,
                        "Пересборка текста",
                        "Дополнительные инструкции к основному промпту для пересборки текста без нового поиска.",
                        """
                        Режим пересборки текста без нового поиска:
                        - не используй web search и не придумывай новые URL;
                        - работай только с CRM-фактами, сохранёнными sections, sources, warnings и factSnapshot из входа;
                        - можно улучшать структуру, ясность, порядок и формулировки;
                        - если факта нет в сохранённых данных, отмечай, что его нужно уточнить, а не выдумывай;
                        - sources верни только из входного списка, можно убрать дубли, но нельзя добавлять новые ссылки.
                        """.stripIndent().trim(),
                        List.of()
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.DEEP_REPORT_REBUILD_SECTION_INSTRUCTIONS,
                        "Пересборка раздела",
                        "Инструкции для точечного режима, который переписывает один раздел отчёта.",
                        """
                        Ты точечно переписываешь один раздел глубокого отчёта о репутации компании.
                        Верни только JSON по схеме: section {title, body} и warnings.
                        Не запускай web search, не добавляй новые URL и не меняй остальные разделы.
                        Работай только с CRM-фактами, сохранёнными sources, factSnapshot и текстом базового отчёта.
                        Если факта нет в сохранённых данных, отметь это как ограничение в warnings, а не выдумывай.
                        body должен быть самостоятельным markdown-фрагментом без заголовка верхнего уровня.
                        """.stripIndent().trim(),
                        List.of()
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.CONTENT_PACK_SYSTEM,
                        "AI-пакет: системные инструкции",
                        "Главный системный промпт для УТП, рекламы, постов, отзывов и ответов компании.",
                        contentPackSystemPrompt(),
                        List.of("{{adTextRange}}")
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.CONTENT_PACK_USER,
                        "AI-пакет: входной шаблон",
                        "Основной user prompt для генерации AI-пакета. JSON payload подставляется автоматически.",
                        contentPackUserPrompt(),
                        List.of("{{utpRange}}", "{{adTextRange}}", "{{socialPostRange}}", "{{reviewDraftRange}}", "{{payloadJson}}")
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.CONTENT_PACK_COMPACT_SYSTEM,
                        "AI-пакет: компактный system retry",
                        "Системный промпт для короткой повторной генерации, когда основной ответ сломал JSON.",
                        contentPackCompactSystemPrompt(),
                        List.of()
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.CONTENT_PACK_COMPACT_RETRY,
                        "AI-пакет: компактный retry",
                        "User prompt для повторного форматирования AI-пакета после битого или оборванного JSON.",
                        contentPackCompactRetryPrompt(),
                        List.of("{{parseError}}", "{{adTextsCount}}", "{{socialPostsCount}}", "{{positiveReplyCount}}", "{{negativeReplyCount}}", "{{previousResponse}}", "{{userPrompt}}")
                ),
                new PromptDefinition(
                        ReputationAiPromptKeys.CONTENT_PACK_REVISION,
                        "AI-пакет: исправление качества",
                        "User prompt для полной пересборки пакета, если автоматическая проверка качества нашла проблемы.",
                        contentPackRevisionPrompt(),
                        List.of("{{qualityIssues}}", "{{userPrompt}}", "{{previousResponse}}")
                )
        );
    }

    private String contentPackSystemPrompt() {
        return """
                Ты элитная команда маркетинга в одном лице: бренд-стратег, performance-маркетолог, SMM-редактор, reputation manager и строгий фактчекер.
                Твоя задача - превратить глубокое исследование компании в сильный AI-пакет для репутации, рекламы, отзывов и соцсетей.
                Главный источник истины - deepResearch. Быстрый слепок и sources используй как дополнительный контекст.
                Используй только факты из deepResearch, входного слепка компании и публичных источников.
                Не выдумывай адреса, даты, товары, услуги, награды, цены, сроки и гарантии.
                Если факта нет, формулируй осторожно: "можно уточнить", "клиент может отметить, если это было в опыте".
                Каждый рекламный текст, УТП, статья и черновик отзыва должны использовать конкретику: город, адрес, рейтинг, отзывы, сценарии, цены, возраст, формат, интерьер/экстерьер, доверие, возражения, контакты, товары или услуги.
                Пиши как лучшие маркетологи: не общими словами, а через инсайт, клиентский сценарий, доказательство, выгоду и честный следующий шаг.
                УТП в поле utp пиши как готовые фразы продавца от лица компании: 1-2 живых предложения, без технических ссылок на источники и без конструкции "источник сообщает".
                Каждый пункт УТП должен раскрывать отдельное достоинство. Не делай семь вариантов одной мысли. Начинай с разных смысловых углов: "Удобно начать в Благовещенске", "Помогаем с маршрутом", "Объясняем этапы лечения", "Снижаем тревогу перед поездкой", "Подбираем формат под задачу".
                Рекламные тексты в поле adTexts пиши как готовые продающие мини-тексты {{adTextRange}} знаков от лица компании. Это не анкета и не таблица: не используй ярлыки "Заголовок:", "Кому:", "Зачем:", "Почему можно доверять:", "Текст:", "Следующий шаг:".
                Рекламные тексты должны отличаться от УТП: это не список преимуществ, а готовые объявления под разные аудитории и ситуации. Один текст - для тех, кто боится логистики; другой - для тех, кто сравнивает стоимость; третий - для протезирования/брекетов; четвертый - для первого обращения; пятый - про офис и консультацию; шестой - про сопровождение.
                В каждом рекламном тексте естественно объясняй клиенту: чем поможем, для какой ситуации услуга подходит, какую проблему снимаем, почему нам можно доверять и как обратиться. Не повторяй одинаковый первый абзац.
                Пиши живой речью: "Поможем оформить ДТП без лишней паники", "Возьмем на себя документы", "Подскажем, что делать на месте". Не копируй стиль справочника.
                Запрещены пустые формулы вроде "индивидуальный подход", "высокое качество", "лучший сервис", если рядом нет конкретного факта из исследования.
                Запрещено делать utp/adTexts списком ссылок, ИНН/ОГРН, телефонов, названий источников или сухих SEO-фраз. Реквизиты можно упоминать только в safetyNotes или companyProfile, если это важно для доверия.
                Запрещены формулировки "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "в открытых данных указано", "официальный сайт описывает" внутри utp и adTexts. Переписывай такие факты в человеческую пользу без упоминания источника.
                Посты для соцсетей должны быть полноценными готовыми статьями: 900-1600 знаков, с явным заголовком первой строкой, лидом, 3-5 смысловыми абзацами и мягким CTA. Заголовок нужен именно в socialPosts, но не нужен в adTexts.
                Черновики отзывов должны быть почти готовыми текстами для реальных клиентов: 500-900 знаков, с конкретными деталями компании, но без утверждения непроверенного личного опыта.
                В черновиках отзывов оставляй только 2-4 короткие вставки в квадратных скобках для того, что обязан подтвердить сам клиент.
                Не делай короткие заголовки вместо статей. Не используй общие фразы без фактов, если во входных данных есть конкретика.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String contentPackCompactSystemPrompt() {
        return """
                Ты маркетолог и фактчекер. Твоя задача - вернуть короткий, законченный и строго валидный JSON для AI-пакета.
                Главный приоритет - валидность JSON: никаких markdown-блоков, пояснений вокруг объекта и незакрытых строк.
                Не выдумывай факты. Используй deepResearch, CRM-данные и priorityUrls как смысловой контекст.
                Лучше вернуть меньше элементов, но каждый должен быть человеческим, понятным и основанным на фактах.
                """.stripIndent().trim();
    }

    private String contentPackUserPrompt() {
        return """
                Подготовь структурированный AI-пакет компании.
                Главный материал - deepResearch: это уже собранное исследование компании. Быстрый snapshot нужен только как дополнительная CRM/поисковая подложка.
                Сделай тексты конкретными: используй название, город, категорию, найденные товары/услуги, цены, филиалы, интерьер/экстерьер, репутационные темы, доверие, возражения, сценарии и факты из sources.
                priorityUrls - это не текст для копирования пользователю, а список источников, из которых нужно понять контекст компании: официальный сайт, услуги, цены, контакты, карточки и документы.
                Если источников или подтверждений мало, явно добавь factualWarnings и safetyNotes, но не усиливай непроверенные утверждения.
                Требование к качеству:
                - companyProfile: сжатое позиционирование, продукты, преимущества, репутационные плюсы/минусы и предупреждения;
                - utp: {{utpRange}} сильных УТП. Каждое УТП - отдельное преимущество компании, а не вариация одной мысли. Пиши готовой человеческой фразой от лица компании: что мы делаем, чем это помогает клиенту, почему это удобно/надежно. Не пиши "сайт указывает", "2ГИС указывает", "источник подтверждает";
                - adTexts: готовые продающие мини-тексты {{adTextRange}} знаков от лица компании. Они должны раскрывать разные сценарии покупки и не дублировать УТП слово в слово. Не делай анкету с ярлыками "Заголовок/Кому/Зачем/Почему можно доверять/Текст/Следующий шаг". Не делай сухой набор ссылок, ИНН, ОГРН, телефонов и названий. Пиши живой речью продавца: "поможем", "подскажем", "возьмем на себя", "оставьте заявку";
                - socialPostTopics: темы-планы с аудиторией, сценарием и смыслом поста, не просто заголовки;
                - socialPosts: полноценные статьи по темам socialPostTopics, {{socialPostRange}} знаков каждая, с явным заголовком первой строкой, хорошим лидом и мягким CTA. В постах заголовок обязателен;
                - honestReviewTopics: конкретные темы, по которым клиенту легко вспомнить реальный опыт;
                - reviewDraftTemplates: почти готовые отзывы {{reviewDraftRange}} знаков, но с короткими местами для проверки клиентом;
                - positiveReviewReplies/negativeReviewReplies: ответы компании с учетом ниши, возражений и репутационного тона.
                Количество элементов в списках ориентируй на request.*Count.
                В utp и adTexts не упоминай источники данных как говорящего. Факты из источников превращай в клиентскую пользу: не "2ГИС указывает круглосуточный прием", а "можно обратиться в любое время, если это подтверждено отчетом".
                
                Входные данные:
                {{payloadJson}}
                """.stripIndent().trim();
    }

    private String contentPackCompactRetryPrompt() {
        return """
                Предыдущий ответ OpenAI был невалидным JSON или оборвался по лимиту output tokens.
                Ошибка парсинга: {{parseError}}

                Верни новый компактный JSON по той же схеме. Требования:
                - companyProfile заполни коротко, но осмысленно;
                - utp: 5-7 пунктов;
                - adTexts: {{adTextsCount}} готовых продающих мини-текста по 280-500 знаков от лица компании, каждый под отдельный сценарий клиента, без ярлыков "Заголовок/Кому/Зачем/Почему можно доверять/Текст/Следующий шаг";
                - socialPostTopics: 4-5 тем;
                - socialPosts: {{socialPostsCount}} статьи по 650-1000 знаков, каждая с коротким заголовком первой строкой;
                - honestReviewTopics: 5-7 тем;
                - reviewDraftTemplates: 4-6 черновиков по 350-650 знаков;
                - positiveReviewReplies: {{positiveReplyCount}} ответов;
                - negativeReviewReplies: {{negativeReplyCount}} ответов;
                - safetyNotes: 3-7 предупреждений.
                Не копируй сырой список ссылок или реквизитов. Ссылки используй только для понимания смысла компании.
                В utp и adTexts не пиши "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "по открытым данным". Говори как компания: "мы поможем", "подскажем", "оформим", "можно обратиться".
                Обязательно закрой все массивы, строки и объект.

                Сжатый фрагмент предыдущего ответа для понимания намерения:
                {{previousResponse}}

                Входные данные:
                {{userPrompt}}
                """.stripIndent().trim();
    }

    private String contentPackRevisionPrompt() {
        return """
                Предыдущий JSON был отклонен контролем качества.
                Причины: {{qualityIssues}}
                
                Перепиши пакет полностью. Обязательные правила:
                - главным источником считай deepResearch: репутационные темы, доверие, возражения, сценарии, услуги/цены, филиалы, интерьер/экстерьер и риски;
                - прямо используй конкретные факты из deepResearch, evidenceFacts и trusted/review sources: названия сценариев, город, адреса, рейтинг, количество отзывов, возраст, цены, сайт, карточки, товары или услуги;
                - sources с типами catalog_listing, competitor_listing и unknown_public не используй как факты о компании; это только внешний контекст выдачи;
                - УТП и рекламные карточки должны звучать как готовые тексты от лица самой компании: живо, уверенно, по-человечески, с пользой для клиента;
                - adTexts перепиши как готовые продающие тексты от первого лица множественного числа ("мы", "поможем", "подскажем", "возьмем на себя"), а не как анкету с полями "Заголовок/Кому/Зачем";
                - если в deepResearch или request.publicUrls есть официальный сайт, страницы услуг, прайса, контактов или карточки 2ГИС, используй их как контекст смысла: какие услуги, для кого, зачем, при каких условиях. Не выводи их сухим списком;
                - не пиши в УТП и adTexts фразы вроде "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "по открытым данным"; источники нужны для фактчекинга, а не для рекламного текста;
                - разведи роли блоков: utp = разные короткие преимущества компании; adTexts = разные продающие сценарии/объявления; socialPosts = статьи с заголовками и раскрытием темы;
                - не повторяй один и тот же плюс в каждом УТП и каждом рекламном тексте. Каждый элемент должен иметь отдельный угол: маршрут, экономия, консультация, офис, запись, сопровождение, подготовка документов, спокойствие клиента, сроки, доверие;
                - socialPosts должны быть полноценными статьями, а не заголовками и не короткими заметками;
                - reviewDraftTemplates должны быть почти готовыми отзывами, но с честными местами для личной проверки клиента;
                - если факт сомнительный, напиши предупреждение в factualWarnings или safetyNotes, но не теряй остальную конкретику.
                
                Исходное задание и данные:
                {{userPrompt}}
                
                Отклоненный ответ:
                {{previousResponse}}
                """.stripIndent().trim();
    }

    private ReputationAiPromptValidation validate(PromptDefinition definition, String content) {
        List<String> missing = definition.requiredPlaceholders().stream()
                .filter(placeholder -> !content.contains(placeholder))
                .toList();
        List<String> warnings = content.isBlank()
                ? List.of("Промпт пустой.")
                : List.of();
        return new ReputationAiPromptValidation(
                definition.key(),
                missing.isEmpty() && warnings.isEmpty(),
                missing,
                warnings
        );
    }

    private Map<String, String> sampleValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("companyFacts", """
                Название: Студия Сервис
                Город: Иркутск
                Сайт из CRM: https://example-service.ru
                Категория: локальные услуги
                Подкатегория: ремонт и обслуживание
                Комментарий CRM: сильная сторона - быстрый выезд и понятные цены.
                Филиал: Иркутск, ул. Ленина, 10; 2ГИС: https://2gis.ru/irkutsk/firm/example
                """.stripIndent().trim());
        values.put("manualDescription", "Клиенты часто отмечают аккуратную консультацию, гарантию на работы и помощь с подбором комплектующих.");
        values.put("productsOrServices", """
                Диагностика
                Ремонт под ключ
                Выезд специалиста
                Гарантийное обслуживание
                """.stripIndent().trim());
        values.put("publicUrls", """
                https://example-service.ru/services
                https://example-service.ru/prices
                https://2gis.ru/irkutsk/firm/example
                """.stripIndent().trim());
        values.put("crmPriorityUrls", """
                CRM website: https://example-service.ru
                CRM филиал: https://2gis.ru/irkutsk/firm/example
                """.stripIndent().trim());
        values.put("adTextRange", "350-700");
        values.put("utpRange", "7-10");
        values.put("socialPostRange", "900-1600");
        values.put("reviewDraftRange", "500-900");
        values.put("payloadJson", samplePayloadJson());
        values.put("parseError", "Unexpected end-of-input: массив socialPosts не был закрыт.");
        values.put("adTextsCount", "4");
        values.put("socialPostsCount", "2");
        values.put("positiveReplyCount", "4");
        values.put("negativeReplyCount", "2");
        values.put("previousResponse", """
                {"companyProfile":{"shortDescription":"Студия ремонта в Иркутске"},"utp":["Помогаем быстро понять объём работ"],"adTexts":[
                """.stripIndent().trim());
        values.put("qualityIssues", "мало конкретики в adTexts; socialPosts выглядят как короткие заголовки; не использованы факты о гарантии");
        values.put("userPrompt", sampleContentPackUserPrompt(values.get("payloadJson")));
        return values;
    }

    private String samplePayloadJson() {
        return """
                {
                  "company": {
                    "id": 1,
                    "name": "Студия Сервис",
                    "city": "Иркутск",
                    "website": "https://example-service.ru",
                    "category": "локальные услуги",
                    "subCategory": "ремонт и обслуживание",
                    "products": ["Диагностика", "Ремонт под ключ", "Выезд специалиста"],
                    "advantages": ["понятные цены", "гарантия", "быстрый выезд"],
                    "warnings": ["цены нужно сверить с актуальным прайсом"]
                  },
                  "search": {
                    "provider": "demo",
                    "available": true,
                    "queries": ["Студия Сервис Иркутск отзывы", "example-service.ru цены"],
                    "resultsCount": 8,
                    "websitePagesRead": 3
                  },
                  "sources": [
                    {
                      "type": "official_site",
                      "title": "Услуги и цены",
                      "url": "https://example-service.ru/prices",
                      "excerpt": "Диагностика, ремонт под ключ, гарантийное обслуживание."
                    },
                    {
                      "type": "map_card",
                      "title": "Карточка 2ГИС",
                      "url": "https://2gis.ru/irkutsk/firm/example",
                      "excerpt": "Адрес, режим работы, отзывы клиентов."
                    }
                  ],
                  "deepResearch": {
                    "summary": "Компания выглядит как локальная сервисная студия с выездом и гарантией.",
                    "sections": [
                      {"title": "Краткая сводка", "body": "Есть сайт, карточка на картах и повторяющиеся темы про скорость и гарантию."},
                      {"title": "Услуги, товары и цены", "body": "Нужно сверить актуальные цены, но сайт показывает диагностику и ремонт под ключ."}
                    ],
                    "warnings": ["не все цены подтверждены публично"]
                  },
                  "request": {
                    "adTextsCount": 6,
                    "socialPostsCount": 4,
                    "positiveReplyCount": 6,
                    "negativeReplyCount": 4
                  }
                }
                """.stripIndent().trim();
    }

    private String sampleContentPackUserPrompt(String payloadJson) {
        return """
                Подготовь структурированный AI-пакет компании.
                Используй deepResearch как главный источник фактов.

                Входные данные:
                %s
                """.formatted(payloadJson);
    }

    private List<String> unresolvedPlaceholders(String value) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value == null ? "" : value);
        List<String> placeholders = new ArrayList<>();
        while (matcher.find()) {
            String placeholder = matcher.group();
            if (!placeholders.contains(placeholder)) {
                placeholders.add(placeholder);
            }
        }
        return placeholders;
    }

    private void saveHistory(String key, String action, String actor, String previousContent, String content) {
        ReputationAiPromptHistoryEntity entity = new ReputationAiPromptHistoryEntity();
        entity.setPromptKey(key);
        entity.setAction(action);
        entity.setActor(actor == null || actor.isBlank() ? "unknown" : actor);
        entity.setPreviousContent(previousContent);
        entity.setContent(content);
        historyRepository.save(entity);
    }

    private ReputationAiPromptVersion toVersion(ReputationAiPromptHistoryEntity entity) {
        return new ReputationAiPromptVersion(
                entity.getId(),
                entity.getPromptKey(),
                entity.getAction(),
                entity.getActor(),
                entity.getPreviousContent(),
                entity.getContent(),
                entity.getCreatedAt()
        );
    }

    private String readPrompt(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось прочитать prompt AI-репутации", exception);
        }
    }

    private record PromptDefinition(
            String key,
            String title,
            String description,
            String defaultContent,
            List<String> requiredPlaceholders
    ) {
    }

    private record PromptPreset(
            String key,
            String title,
            String description,
            List<String> rules
    ) {
    }
}
