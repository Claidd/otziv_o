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
    private static final String MANDATORY_DEEP_REPORT_CARD_CONTROL = """
            Контроль карточки компании перед финальным JSON:
            - проверь, не повторяется ли одна и та же информация в разных секциях; если смысл уже раскрыт, во второй секции добавляй только новый факт или короткую ссылку на предыдущий вывод;
            - перед анализом проверь идентичность компании по названию, городу, адресу, телефону, домену, ИНН/ОГРН, ссылкам из карточек, соцсетям и фото филиалов; не смешивай одноименные компании, франшизы, старые филиалы и конкурентов;
            - если источник относится к компании неуверенно, используй его только как слабый сигнал, выставь confidence=low и вынеси сомнение в warnings;
            - полезные неподтвержденные сведения не игнорируй: включай их в отчет как **Неподтверждено:**, **Слабый сигнал:** или **Нужно проверить:** с источником/площадкой и confidence=low; не подавай их как доказанный факт, сильное УТП, гарантию, юридический/медицинский факт, точную цену или окончательный адрес;
            - проверь, что учтены CRM-данные, ручные факты, публичные URL, карты и справочники; если какой-то источник не использован или выглядит ошибочным, вынеси это в warnings и риски;
            - после официальных источников и карт проверь до 15-20 релевантных результатов выдачи, но не добирай количество искусственно: если после 6-10 качественных источников идут дубли, каталоги-клоны, конкуренты или нерелевантные страницы, остановись и отметь это в warnings;
            - для каждого sources заполняй title, url, type, usedFor, confidence и note; type только official_site, map_card, directory, review_platform, social, legal, aggregator, media или other; confidence: high для официального/юридического источника или 2+ независимых подтверждений, medium для карт/крупных справочников/агрегаторов/соцсетей компании, low для отзывов, сниппетов, старых страниц, неподтвержденных каталогов и единичных упоминаний;
            - имена сотрудников из отзывов не игнорируй: отделяй их от официального списка, подписывай как "упомянуты в отзывах", не присваивай должность без явного текста и ставь низкую/среднюю уверенность по числу повторов;
            - проверь, достаточно ли сведений для будущей карточки компании: позиционирование, услуги/товары/пакеты, цены/условия, филиалы, адреса, режим, вход, этаж, ориентиры, парковка, доступность, зона ожидания, Wi-Fi, туалет, гардероб, детская зона, доставка/самовывоз/выезд, онлайн-запись, оплата картой и наличными, правила заказа и возврата;
            - по картам и справочникам отдельно ищи и сверяй фото входа/фасада, как найти, этаж, парковку рядом, отзывы по конкретному филиалу, рейтинг/количество отзывов, режим и статус филиала, признаки Wi-Fi, туалета, гардероба, детской зоны, зоны ожидания и доступности;
            - если парковка, вход, этаж, доступность, Wi-Fi, туалет, гардероб, детская зона, зона ожидания, оплата картой или другие удобства не подтверждены, не придумывай их; явно напиши, что эти пункты нужно уточнить;
            - если найдены расхождения между официальным сайтом, картами, справочниками и CRM, перечисли спорный факт и где его надо проверить;
            - разделяй идеи для постов/карточки и идеи для отзывов на два разных sections; в section "Идеи для отзывов" верни ровно 30 неповторяющихся идей одним нумерованным списком: первые 15 как раньше про клиентские сценарии, возражения, удобства, УТП и репутационные сигналы, остальные 15 про разные конкретные услуги, товары, пакеты, тарифы, меню, комплекты или доплаты из section "Услуги, товары и цены"; по возможности называй конкретную позицию из таблицы, если позиций меньше 15 — используй все найденные и добей ближайшими конкретными направлениями с пометкой "нужно уточнить"; эмоции и личный опыт клиент допишет сам;
            - в разделе "Что ещё собирать" обязательно дай мини-аудит готовности карточки компании: что повторяется, что не подтверждено, чего не хватает из карт/справочников/CRM и какие поля менеджеру нужно дозвонить перед публикацией.
            """.stripIndent().trim();
    private static final String MANDATORY_CONTENT_PACK_REVIEW_CONTROL = """
            Контроль отзывов AI-пакета:
            - сначала сформируй companyProfile, УТП, рекламные тексты и посты; только после этого делай honestReviewTopics и reviewDraftTemplates на основе уже выбранных УТП/сценариев/постов и фактов deepResearch;
            - honestReviewTopics не должны быть простыми вопросами вроде "что понравилось" или "как помог администратор"; пиши их как неповторяющиеся смысловые темы для клиента: конкретная услуга/товар/пакет/особенность/УТП + какая рекламная польза подсвечивается + где клиент допишет личный опыт;
            - reviewDraftTemplates должны быть не анкетой впечатлений, а полезными полуготовыми отзывами с мягкой рекламной пользой: 60-75% факты, сценарий, услуга, УТП, позиция, формат, условия или вывод из поста; 25-40% место для личного опыта клиента;
            - в каждом черновике используй одну конкретную тему из AI-пакета: одну услугу/товар/пакет, одну позицию из УТП, один рекламный сценарий или одну тему поста. Не пытайся вставить все сразу;
            - не используй квадратные скобки, прочерки-заглушки и шаблонные поля; личный опыт клиента обозначай обычной фразой без placeholder-формата;
            - черновики должны начинаться по-разному и брать разные индивидуальные факты: адрес/филиал, парковка/вход/этаж, путь/логистика, режим, цена/условия, возраст, чайная зона, фото, безопасность, отзывы - только если это подтверждено;
            - черновик должен помогать клиенту написать отзыв: дать структуру, полезную конкретику и рекламный смысл, а не только спрашивать "что вы чувствовали";
            - не делай фейковые отзывы и не утверждай непроверенный личный опыт; факты компании можно использовать как контекст, а личный опыт клиент дописывает сам.
            """.stripIndent().trim();

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
        String content = overrideRepository.findById(key)
                .map(ReputationAiPromptOverrideEntity::getContent)
                .orElseGet(definition::defaultContent);
        return effectiveContent(key, content);
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
        String template = content == null || content.isBlank() ? content(key) : effectiveContent(key, content.trim());
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
        String defaultContent = effectiveContent(definition.key(), definition.defaultContent());
        String content = override
                .map(ReputationAiPromptOverrideEntity::getContent)
                .map(value -> effectiveContent(definition.key(), value))
                .orElse(defaultContent);
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

    private String effectiveContent(String key, String content) {
        String cleanContent = content == null ? "" : content.trim();
        if (ReputationAiPromptKeys.DEEP_REPORT_INSTRUCTIONS.equals(key)) {
            return appendMandatoryBlock(cleanContent, "контроль карточки компании", MANDATORY_DEEP_REPORT_CARD_CONTROL);
        }
        if (ReputationAiPromptKeys.CONTENT_PACK_USER.equals(key)) {
            return appendMandatoryBlock(cleanContent, "контроль отзывов ai-пакета", MANDATORY_CONTENT_PACK_REVIEW_CONTROL);
        }
        return cleanContent;
    }

    private String appendMandatoryBlock(String content, String marker, String block) {
        if (content.toLowerCase().contains(marker)) {
            return content;
        }
        return content + "\n\n" + block;
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
                        Перед добавлением источника проверь идентичность компании по названию, городу, адресу, телефону, домену, ИНН/ОГРН, ссылкам из карточек, соцсетям и фото филиалов; не смешивай одноименные компании, франшизы, старые филиалы и конкурентов.
                        После официальных источников и карт проверь до 15-20 релевантных результатов выдачи, но не добирай количество искусственно: если после 6-10 качественных источников идут дубли, каталоги-клоны, конкуренты или нерелевантные страницы, остановись и отметь это в warnings.
                        sources должны быть обычными публичными URL без citation placeholders и без utm_source=openai.
                        Каждый source заполняй как {title, url, type, usedFor, confidence, note}.
                        type только: official_site, map_card, directory, review_platform, social, legal, aggregator, media, other.
                        confidence: high для официального/юридического источника или 2+ независимых подтверждений; medium для карт, крупных справочников, агрегаторов и соцсетей компании; low для отзывов, сниппетов, старых страниц, неподтвержденных каталогов и единичных упоминаний.
                        В usedFor перечисли, что источник подтверждает: услуги, цены, контакты, адрес, режим, парковка, отзывы, сотрудники, юридические данные, фото, удобства.
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
                Неподтвержденные и low-confidence сведения из deepResearch можно использовать только как осторожные предупреждения, вопросы для клиента или темы проверки; не превращай их в УТП, рекламное обещание, точную цену, гарантию или окончательный факт.
                Если факта нет, формулируй осторожно: "можно уточнить", "клиент может отметить, если это было в опыте".
                В каждом рекламном тексте, УТП, статье и черновике отзыва используй 2-5 релевантных конкретных фактов из deepResearch. Не пытайся вставить все факты сразу. Выбирай те, которые усиливают конкретный сценарий: адрес и вход - для первого визита; цены и условия - для сравнения; фото/интерьер - для доверия; отзывы - для репутации; сроки и гарантия - для услуг; ассортимент и доставка - для магазина.
                Пиши как лучшие маркетологи: не общими словами, а через инсайт, клиентский сценарий, доказательство, выгоду и честный следующий шаг.
                УТП в поле utp пиши как готовые фразы продавца от лица компании: 1-2 живых предложения, без технических ссылок на источники и без конструкции "источник сообщает".
                Каждый пункт УТП должен раскрывать отдельное достоинство. Не делай семь вариантов одной мысли. Сам выбирай разные смысловые углы из deepResearch: первое обращение, цена, срочная потребность, семейный или корпоративный формат, доставка/самовывоз, запись, консультация, подарок, ремонт, обслуживание, B2B-заказ, гарантия, доверие, логистика - только если это подтверждено или релевантно типу компании.
                Рекламные тексты в поле adTexts пиши как готовые продающие мини-тексты {{adTextRange}} знаков от лица компании. Это не анкета и не таблица: не используй ярлыки "Заголовок:", "Кому:", "Зачем:", "Почему можно доверять:", "Текст:", "Следующий шаг:".
                Рекламные тексты должны отличаться от УТП: это не список преимуществ, а готовые объявления под разные реальные сценарии из deepResearch. Не используй заранее заданные сценарии, если они не подходят бизнесу. Сам выбери 4-8 разных углов: первое обращение, сравнение цены, срочная потребность, семейный/корпоративный формат, доставка/самовывоз, запись, консультация, подарок, ремонт, обслуживание, B2B-заказ, гарантия, доверие, логистика - только если это подтверждено или релевантно типу компании.
                В каждом рекламном тексте естественно объясняй клиенту: чем поможем, для какой ситуации услуга подходит, какую проблему снимаем, почему нам можно доверять и как обратиться. Не повторяй одинаковый первый абзац.
                Пиши живой речью продавца или менеджера этой компании. Не копируй стиль справочника.
                Запрещены пустые формулы вроде "индивидуальный подход", "высокое качество", "лучший сервис", если рядом нет конкретного факта из исследования.
                Запрещено делать utp/adTexts списком ссылок, ИНН/ОГРН, телефонов, названий источников или сухих SEO-фраз. Реквизиты можно упоминать только в safetyNotes или companyProfile, если это важно для доверия.
                Запрещены формулировки "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "в открытых данных указано", "официальный сайт описывает" внутри utp и adTexts. Переписывай такие факты в человеческую пользу без упоминания источника.
                Посты для соцсетей должны быть полноценными готовыми статьями: 900-1600 знаков, с явным заголовком первой строкой, лидом, 3-5 смысловыми абзацами и мягким CTA. Заголовок нужен именно в socialPosts, но не нужен в adTexts.
                Темы и черновики отзывов делай после УТП, рекламы и постов: они должны брать одну конкретную тему из уже собранного AI-пакета и deepResearch, а не превращаться в анкету про впечатления.
                Черновики отзывов должны быть почти готовыми текстами для реальных клиентов: 500-900 знаков, с конкретными деталями компании, мягкой рекламной пользой услуги/товара/УТП и честными местами для личного опыта.
                В черновиках отзывов не используй квадратные скобки, прочерки-заглушки и шаблонные поля. Личную часть клиента обозначай обычной фразой без placeholder-формата.
                Все черновики должны начинаться по-разному и использовать разные индивидуальные факты из deepResearch: парковка, вход, этаж, адрес, путь от центра, режим, цены, условия, возраст, фото, чайная зона, безопасность, отзывы - только если это есть во входных данных.
                Не делай короткие заголовки вместо статей. Не используй общие фразы без фактов, если во входных данных есть конкретика.
                Верни только валидный JSON без markdown.
                """.stripIndent().trim();
    }

    private String contentPackCompactSystemPrompt() {
        return """
                Ты маркетолог и фактчекер. Твоя задача - вернуть короткий, законченный и строго валидный JSON для AI-пакета.
                Главный приоритет - валидность JSON: никаких markdown-блоков, пояснений вокруг объекта и незакрытых строк.
                Не выдумывай факты. Используй deepResearch, CRM-данные и priorityUrls как смысловой контекст.
                Неподтвержденные сведения оставляй только как осторожные предупреждения или вопросы для проверки, не как рекламные факты.
                Лучше вернуть меньше элементов, но каждый должен быть человеческим, понятным и основанным на фактах.
                """.stripIndent().trim();
    }

    private String contentPackUserPrompt() {
        return """
                Подготовь структурированный AI-пакет компании.
                Главный материал - deepResearch: это уже собранное исследование компании. Быстрый snapshot нужен только как дополнительная CRM/поисковая подложка.
                Сделай тексты конкретными, но не перегруженными: в каждом тексте используй 2-5 релевантных фактов из deepResearch, snapshot и sources. Не вставляй город, адрес, рейтинг, цены, интерьер, возраст и контакты одновременно, если это не усиливает конкретный сценарий.
                priorityUrls - это не текст для копирования пользователю, а список источников, из которых нужно понять контекст компании: официальный сайт, услуги, цены, контакты, карточки и документы.
                Если источников или подтверждений мало, явно добавь factualWarnings и safetyNotes, но не усиливай непроверенные утверждения.
                Если deepResearch содержит пометки "Неподтверждено", "Слабый сигнал", "Нужно проверить" или confidence=low, используй это только в factualWarnings/safetyNotes или как осторожную тему для уточнения, не как основу УТП/adTexts.
                Требование к качеству:
                - companyProfile: сжатое позиционирование, продукты, преимущества, репутационные плюсы/минусы и предупреждения;
                - utp: {{utpRange}} сильных УТП. Каждое УТП - отдельное преимущество компании, а не вариация одной мысли. Пиши готовой человеческой фразой от лица компании: что мы делаем, чем это помогает клиенту, почему это удобно/надежно. Не пиши "сайт указывает", "2ГИС указывает", "источник подтверждает";
                - adTexts: готовые продающие мини-тексты {{adTextRange}} знаков от лица компании. Они должны раскрывать разные реальные сценарии покупки из deepResearch и не дублировать УТП слово в слово. Не делай анкету с ярлыками "Заголовок/Кому/Зачем/Почему можно доверять/Текст/Следующий шаг". Не делай сухой набор ссылок, ИНН, ОГРН, телефонов и названий. Пиши живой речью продавца: "поможем", "подскажем", "возьмем на себя", "оставьте заявку";
                - socialPostTopics: темы-планы с аудиторией, сценарием и смыслом поста, не просто заголовки;
                - socialPosts: полноценные статьи по темам socialPostTopics, {{socialPostRange}} знаков каждая, с явным заголовком первой строкой, хорошим лидом и мягким CTA. В постах заголовок обязателен;
                - honestReviewTopics: не вопросы, а неповторяющиеся смысловые темы для честного отзыва. Каждый пункт: услуга/товар/пакет/уникальная особенность или сценарий из deepResearch + какая рекламная польза/УТП подсвечивается + где клиент добавит личный опыт;
                - reviewDraftTemplates: почти готовые отзывы {{reviewDraftRange}} знаков, но без квадратных скобок, прочерков-заглушек и шаблонных полей. Строй каждый черновик на одной теме из УТП, рекламных текстов или socialPosts: 60-75% полезная конкретика компании/услуги, 25-40% личный опыт клиента;
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
                - honestReviewTopics: 5-7 неповторяющихся тем для честного отзыва, не коротких вопросов;
                - reviewDraftTemplates: 4-6 черновиков по 350-650 знаков, каждый с одним УТП/услугой/сценарием, без квадратных скобок, прочерков-заглушек и шаблонных полей;
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
                - в каждом тексте используй 2-5 релевантных фактов из deepResearch, evidenceFacts и trusted/review sources; не пытайся вставить сразу город, адреса, рейтинг, возраст, цены, сайт, карточки, товары и услуги, если это перегружает конкретный сценарий;
                - sources с типами catalog_listing, competitor_listing и unknown_public не используй как факты о компании; это только внешний контекст выдачи;
                - УТП и рекламные карточки должны звучать как готовые тексты от лица самой компании: живо, уверенно, по-человечески, с пользой для клиента;
                - adTexts перепиши как готовые продающие тексты от первого лица множественного числа ("мы", "поможем", "подскажем", "возьмем на себя"), а не как анкету с полями "Заголовок/Кому/Зачем";
                - если в deepResearch или request.publicUrls есть официальный сайт, страницы услуг, прайса, контактов или карточки 2ГИС, используй их как контекст смысла: какие услуги, для кого, зачем, при каких условиях. Не выводи их сухим списком;
                - не пиши в УТП и adTexts фразы вроде "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "по открытым данным"; источники нужны для фактчекинга, а не для рекламного текста;
                - разведи роли блоков: utp = разные короткие преимущества компании; adTexts = разные продающие сценарии/объявления; socialPosts = статьи с заголовками и раскрытием темы;
                - не повторяй один и тот же плюс в каждом УТП и каждом рекламном тексте. Каждый элемент должен иметь отдельную тему, выбранную из реальных сценариев deepResearch: первое обращение, цена, срочная потребность, семейный или корпоративный формат, доставка/самовывоз, запись, консультация, подарок, ремонт, обслуживание, B2B-заказ, гарантия, доверие, логистика - только если это подтверждено или релевантно типу компании;
                - socialPosts должны быть полноценными статьями, а не заголовками и не короткими заметками;
                - honestReviewTopics должны быть неповторяющимися темами на основе deepResearch, УТП, рекламы и постов, а не вопросником про впечатления;
                - reviewDraftTemplates должны быть почти готовыми отзывами с рекламной пользой конкретной услуги/товара/УТП и честными местами для личной проверки клиента, но без квадратных скобок, прочерков-заглушек и шаблонных полей;
                - если факт сомнительный, напиши предупреждение в factualWarnings или safetyNotes, но не теряй остальную конкретику.
                - сведения с пометками "Неподтверждено", "Слабый сигнал", "Нужно проверить" или confidence=low не используй как рекламные обещания; оставляй их в предупреждениях или формулируй как вопросы для уточнения.
                
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
