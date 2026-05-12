package com.hunt.otziv.reputationai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.reputationai.api.dto.ReputationContentPackRequest;
import com.hunt.otziv.reputationai.domain.CompanyAiProfile;
import com.hunt.otziv.reputationai.domain.CompanySource;
import com.hunt.otziv.reputationai.domain.DeepCompanyResearchReport;
import com.hunt.otziv.reputationai.domain.ReputationContentPack;
import com.hunt.otziv.reputationai.domain.ResearchSnapshot;
import com.hunt.otziv.reputationai.infrastructure.ai.AiRequest;
import com.hunt.otziv.reputationai.infrastructure.ai.AiResponse;
import com.hunt.otziv.reputationai.infrastructure.ai.openai.OpenAiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiReputationContentFactory {

    private static final Set<String> GENERIC_QUALITY_WORDS = Set.of(
            "компания", "компании", "клиент", "клиента", "клиенты", "отзывы", "отзывов",
            "рейтинг", "выбрать", "сравнить", "описание", "условия", "можно", "нужно",
            "сервис", "услуги", "товары", "городе", "источник", "search", "website",
            "квест", "квесты"
    );
    private static final int MAX_AD_TEXTS_PER_PACK = 8;
    private static final int MAX_SOCIAL_POSTS_PER_PACK = 5;
    private static final int MAX_POSITIVE_REPLIES_PER_PACK = 8;
    private static final int MAX_NEGATIVE_REPLIES_PER_PACK = 6;

    private final OpenAiProvider openAiProvider;
    private final ObjectMapper objectMapper;

    public Optional<ReputationContentPack> create(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        if (!openAiProvider.isAvailable()) {
            return Optional.empty();
        }

        try {
            String prompt = userPrompt(snapshot, deepReport, request);
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack",
                    systemPrompt(),
                    prompt,
                    0.25,
                    true
            ), request.contentPackProfile());

            if (response.text().isBlank()) {
                return Optional.empty();
            }

            ParsedPack parsed = parsePackOrRetryCompact(snapshot, deepReport, request, response.text(), "основной ответ");
            ReputationContentPack pack = parsed.pack();
            if (pack.utp().isEmpty() && pack.adTexts().isEmpty() && pack.reviewDraftTemplates().isEmpty()) {
                return Optional.empty();
            }

            ReputationContentPackRequest qualityRequest = parsed.compact() ? compactRequest(request) : request;
            List<String> qualityIssues = qualityIssues(pack, snapshot, deepReport, qualityRequest);
            if (!qualityIssues.isEmpty()) {
                log.warn("AI content pack quality retry: {}", String.join("; ", qualityIssues));
                Optional<ReputationContentPack> revised = reviseLowQualityPack(snapshot, deepReport, request, response.text(), qualityIssues);
                if (revised.isPresent()) {
                    return revised;
                }
                return Optional.empty();
            }

            return Optional.of(pack);
        } catch (Exception exception) {
            log.warn("AI content pack generation failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private ParsedPack parsePackOrRetryCompact(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String responseText,
            String stage
    ) throws Exception {
        try {
            return new ParsedPack(parsePack(snapshot, responseText), false);
        } catch (Exception exception) {
            log.warn("AI content pack {} parse failed, retrying compact response: {}", stage, exception.getMessage());
            Optional<ReputationContentPack> compact = retryCompactPack(snapshot, deepReport, request, responseText, exception.getMessage());
            if (compact.isPresent()) {
                return new ParsedPack(compact.get(), true);
            }
            throw exception;
        }
    }

    private Optional<ReputationContentPack> retryCompactPack(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            String parseError
    ) {
        try {
            ReputationContentPackRequest compactRequest = compactRequest(request);
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack-compact-retry",
                    compactSystemPrompt(),
                    compactRetryPrompt(snapshot, deepReport, compactRequest, previousResponse, parseError),
                    0.12,
                    true
            ), compactRequest.contentPackProfile());
            if (response.text().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(parsePack(snapshot, response.text()));
        } catch (Exception exception) {
            log.warn("AI content pack compact retry failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReputationContentPack> reviseLowQualityPack(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            List<String> qualityIssues
    ) {
        try {
            AiResponse response = openAiProvider.generateContentPack(new AiRequest(
                    "reputation-content-pack-revision",
                    systemPrompt(),
                    revisionPrompt(snapshot, deepReport, request, previousResponse, qualityIssues),
                    0.15,
                    true
            ), request.contentPackProfile());
            if (response.text().isBlank()) {
                return Optional.empty();
            }

            ParsedPack parsed = parsePackOrRetryCompact(snapshot, deepReport, request, response.text(), "revision");
            ReputationContentPack pack = parsed.pack();
            ReputationContentPackRequest qualityRequest = parsed.compact() ? compactRequest(request) : request;
            List<String> revisedIssues = qualityIssues(pack, snapshot, deepReport, qualityRequest);
            if (!revisedIssues.isEmpty()) {
                log.warn("AI content pack revision still low quality: {}", String.join("; ", revisedIssues));
                return Optional.empty();
            }
            return Optional.of(pack);
        } catch (Exception exception) {
            log.warn("AI content pack revision failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String revisionPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            List<String> qualityIssues
    ) throws Exception {
        return """
                Предыдущий JSON был отклонен контролем качества.
                Причины: %s
                
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
                %s
                
                Отклоненный ответ:
                %s
                """.formatted(
                String.join("; ", qualityIssues),
                userPrompt(snapshot, deepReport, request),
                limit(previousResponse, 6000)
        );
    }

    private String systemPrompt() {
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
                Рекламные тексты в поле adTexts пиши как готовые продающие мини-тексты %s знаков от лица компании. Это не анкета и не таблица: не используй ярлыки "Заголовок:", "Кому:", "Зачем:", "Почему можно доверять:", "Текст:", "Следующий шаг:".
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
                """.formatted("350-700");
    }

    private String compactSystemPrompt() {
        return """
                Ты маркетолог и фактчекер. Твоя задача - вернуть короткий, законченный и строго валидный JSON для AI-пакета.
                Главный приоритет - валидность JSON: никаких markdown-блоков, пояснений вокруг объекта и незакрытых строк.
                Не выдумывай факты. Используй deepResearch, CRM-данные и priorityUrls как смысловой контекст.
                Лучше вернуть меньше элементов, но каждый должен быть человеческим, понятным и основанным на фактах.
                """;
    }

    private String userPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) throws Exception {
        boolean economy = isEconomyProfile(request);
        String utpRange = economy ? "5-7" : "7-10";
        String adTextRange = economy ? "280-500" : "350-700";
        String socialPostRange = economy ? "650-1000" : "900-1600";
        String reviewDraftRange = economy ? "350-650" : "500-900";
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("id", snapshot.companyId());
        company.put("name", snapshot.companyName());
        company.put("city", snapshot.city());
        company.put("website", snapshot.website());
        company.put("category", snapshot.category());
        company.put("subCategory", snapshot.subCategory());
        company.put("notes", snapshot.companyNotes());
        company.put("products", snapshot.products());
        company.put("advantages", snapshot.advantages());
        company.put("positiveTopics", snapshot.commonPositiveTopics());
        company.put("negativeTopics", snapshot.commonNegativeTopics());
        company.put("warnings", snapshot.warnings());
        payload.put("company", company);
        payload.put("search", Map.of(
                "provider", snapshot.searchProvider(),
                "available", snapshot.searchAvailable(),
                "queries", snapshot.searchQueries(),
                "resultsCount", snapshot.searchResultsCount(),
                "websitePagesRead", snapshot.websitePagesRead()
        ));
        payload.put("sources", snapshot.sources().stream()
                .filter(source -> !isLowTrustFactSource(source))
                .limit(12)
                .map(source -> Map.of(
                        "type", source.type(),
                        "title", source.title(),
                        "url", source.url(),
                        "excerpt", limit(source.excerpt(), 1200)
                ))
                .toList());
        payload.put("deepResearch", deepResearchPayload(deepReport));
        payload.put("priorityUrls", priorityUrls(deepReport, request));
        payload.put("evidenceFacts", extractEvidenceFacts(snapshot));
        payload.put("request", Map.of(
                "productOrService", valueOrBlank(request.productOrService()),
                "manualDescription", valueOrBlank(request.manualDescription()),
                "productsOrServices", request.productsOrServices() == null ? List.of() : request.productsOrServices(),
                "publicUrls", request.publicUrls() == null ? List.of() : request.publicUrls(),
                "adTextsCount", boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK),
                "socialPostsCount", boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK),
                "positiveReplyCount", boundedCount(request.positiveReplyCount(), 6, MAX_POSITIVE_REPLIES_PER_PACK),
                "negativeReplyCount", boundedCount(request.negativeReplyCount(), 4, MAX_NEGATIVE_REPLIES_PER_PACK)
        ));
        payload.put("jsonSchema", Map.of(
                "companyProfile", Map.of(
                        "shortDescription", "string",
                        "category", "string",
                        "products", "string[]",
                        "advantages", "string[]",
                        "positiveReviewTopics", "string[]",
                        "negativeReviewTopics", "string[]",
                        "factualWarnings", "string[]"
                ),
                "utp", "string[]",
                "adTexts", "string[]",
                "socialPostTopics", "string[]",
                "socialPosts", "string[]",
                "honestReviewTopics", "string[]",
                "reviewDraftTemplates", "string[]",
                "positiveReviewReplies", "string[]",
                "negativeReviewReplies", "string[]",
                "safetyNotes", "string[]"
        ));

        return """
                Подготовь структурированный AI-пакет компании.
                Главный материал - deepResearch: это уже собранное исследование компании. Быстрый snapshot нужен только как дополнительная CRM/поисковая подложка.
                Сделай тексты конкретными: используй название, город, категорию, найденные товары/услуги, цены, филиалы, интерьер/экстерьер, репутационные темы, доверие, возражения, сценарии и факты из sources.
                priorityUrls - это не текст для копирования пользователю, а список источников, из которых нужно понять контекст компании: официальный сайт, услуги, цены, контакты, карточки и документы.
                Если источников или подтверждений мало, явно добавь factualWarnings и safetyNotes, но не усиливай непроверенные утверждения.
                Требование к качеству:
                - companyProfile: сжатое позиционирование, продукты, преимущества, репутационные плюсы/минусы и предупреждения;
                - utp: %s сильных УТП. Каждое УТП - отдельное преимущество компании, а не вариация одной мысли. Пиши готовой человеческой фразой от лица компании: что мы делаем, чем это помогает клиенту, почему это удобно/надежно. Не пиши "сайт указывает", "2ГИС указывает", "источник подтверждает";
                - adTexts: готовые продающие мини-тексты %s знаков от лица компании. Они должны раскрывать разные сценарии покупки и не дублировать УТП слово в слово. Не делай анкету с ярлыками "Заголовок/Кому/Зачем/Почему можно доверять/Текст/Следующий шаг". Не делай сухой набор ссылок, ИНН, ОГРН, телефонов и названий. Пиши живой речью продавца: "поможем", "подскажем", "возьмем на себя", "оставьте заявку";
                - socialPostTopics: темы-планы с аудиторией, сценарием и смыслом поста, не просто заголовки;
                - socialPosts: полноценные статьи по темам socialPostTopics, %s знаков каждая, с явным заголовком первой строкой, хорошим лидом и мягким CTA. В постах заголовок обязателен;
                - honestReviewTopics: конкретные темы, по которым клиенту легко вспомнить реальный опыт;
                - reviewDraftTemplates: почти готовые отзывы %s знаков, но с короткими местами для проверки клиентом;
                - positiveReviewReplies/negativeReviewReplies: ответы компании с учетом ниши, возражений и репутационного тона.
                Количество элементов в списках ориентируй на request.*Count.
                В utp и adTexts не упоминай источники данных как говорящего. Факты из источников превращай в клиентскую пользу: не "2ГИС указывает круглосуточный прием", а "можно обратиться в любое время, если это подтверждено отчетом".
                
                Входные данные:
                """.formatted(utpRange, adTextRange, socialPostRange, reviewDraftRange)
                + objectMapper.writeValueAsString(payload);
    }

    private String compactRetryPrompt(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request,
            String previousResponse,
            String parseError
    ) throws Exception {
        return """
                Предыдущий ответ OpenAI был невалидным JSON или оборвался по лимиту output tokens.
                Ошибка парсинга: %s

                Верни новый компактный JSON по той же схеме. Требования:
                - companyProfile заполни коротко, но осмысленно;
                - utp: 5-7 пунктов;
                - adTexts: %d готовых продающих мини-текста по 280-500 знаков от лица компании, каждый под отдельный сценарий клиента, без ярлыков "Заголовок/Кому/Зачем/Почему можно доверять/Текст/Следующий шаг";
                - socialPostTopics: 4-5 тем;
                - socialPosts: %d статьи по 650-1000 знаков, каждая с коротким заголовком первой строкой;
                - honestReviewTopics: 5-7 тем;
                - reviewDraftTemplates: 4-6 черновиков по 350-650 знаков;
                - positiveReviewReplies: %d ответов;
                - negativeReviewReplies: %d ответов;
                - safetyNotes: 3-7 предупреждений.
                Не копируй сырой список ссылок или реквизитов. Ссылки используй только для понимания смысла компании.
                В utp и adTexts не пиши "сайт указывает", "2ГИС указывает", "UrbanPlaces указывает", "по открытым данным". Говори как компания: "мы поможем", "подскажем", "оформим", "можно обратиться".
                Обязательно закрой все массивы, строки и объект.

                Сжатый фрагмент предыдущего ответа для понимания намерения:
                %s

                Входные данные:
                %s
                """.formatted(
                valueOrBlank(parseError),
                boundedCount(request.adTextsCount(), 4, 4),
                boundedCount(request.socialPostsCount(), 2, 2),
                boundedCount(request.positiveReplyCount(), 4, 4),
                boundedCount(request.negativeReplyCount(), 2, 2),
                limit(previousResponse, 1800),
                userPrompt(snapshot, deepReport, request)
        );
    }

    private ReputationContentPack parsePack(ResearchSnapshot snapshot, String responseText) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(responseText));
        JsonNode profileNode = root.path("companyProfile");
        CompanyAiProfile profile = new CompanyAiProfile(
                profileNode.path("shortDescription").asText(""),
                profileNode.path("category").asText(""),
                strings(profileNode.path("products")),
                strings(profileNode.path("advantages")),
                strings(profileNode.path("positiveReviewTopics")),
                strings(profileNode.path("negativeReviewTopics")),
                strings(profileNode.path("factualWarnings"))
        );

        return new ReputationContentPack(
                snapshot,
                profile,
                strings(root.path("utp")),
                strings(root.path("adTexts")),
                strings(root.path("socialPostTopics")),
                strings(root.path("socialPosts")),
                strings(root.path("honestReviewTopics")),
                strings(root.path("reviewDraftTemplates")),
                strings(root.path("positiveReviewReplies")),
                strings(root.path("negativeReviewReplies")),
                snapshot.sources().stream().map(CompanySource::url).filter(url -> !url.isBlank()).distinct().toList(),
                strings(root.path("safetyNotes"))
        );
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isValueNode() ? item.asText("") : item.toString();
            if (!value.isBlank() && !result.contains(value.trim())) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String extractJsonObject(String text) {
        String clean = text.trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return clean.substring(start, end + 1);
        }

        return clean;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }

        return value.substring(0, maxLength).trim();
    }

    private List<String> extractEvidenceFacts(ResearchSnapshot snapshot) {
        return snapshot.sources().stream()
                .filter(source -> !isLowTrustFactSource(source))
                .flatMap(source -> List.of(source.title(), source.excerpt()).stream())
                .map(value -> value == null ? "" : value.replaceAll("\\s+", " ").trim())
                .filter(value -> !value.isBlank())
                .filter(value -> containsConcreteFact(value, snapshot))
                .map(value -> limit(value, 360))
                .distinct()
                .limit(25)
                .toList();
    }

    private Map<String, Object> deepResearchPayload(DeepCompanyResearchReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("createdAt", report.createdAt());
        payload.put("model", report.model());
        payload.put("warnings", report.warnings());
        payload.put("sections", report.sections().stream()
                .map(section -> Map.of(
                        "title", section.title(),
                        "body", limit(section.body(), 3200)
                ))
                .toList());
        payload.put("sources", report.sources().stream()
                .limit(30)
                .map(source -> Map.of(
                        "title", source.title(),
                        "url", source.url(),
                        "note", source.note()
                ))
                .toList());
        if (report.sections().isEmpty()) {
            payload.put("reportMarkdown", limit(report.reportMarkdown(), 24000));
        }
        return payload;
    }

    private List<String> priorityUrls(DeepCompanyResearchReport deepReport, ReputationContentPackRequest request) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (request.publicUrls() != null) {
            request.publicUrls().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(urls::add);
        }
        deepReport.sources().stream()
                .map(DeepCompanyResearchReport.Source::url)
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .filter(this::isPriorityUrl)
                .limit(20)
                .forEach(urls::add);
        return urls.stream().toList();
    }

    private boolean isPriorityUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("2gis.")
                || lower.contains("yandex.")
                || lower.contains("google.com/maps")
                || lower.contains("service")
                || lower.contains("uslug")
                || lower.contains("price")
                || lower.contains("prais")
                || lower.contains("цены")
                || lower.contains("услуг")
                || lower.contains("kontakty")
                || lower.contains("contact")
                || lower.endsWith(".pdf")
                || (!lower.contains("://vk.")
                && !lower.contains("://t.me")
                && !lower.contains("facebook.")
                && !lower.contains("instagram."));
    }

    private boolean isLowTrustFactSource(CompanySource source) {
        String type = source.type();
        return type.contains("catalog_listing")
                || type.contains("competitor_listing")
                || type.contains("unknown_public");
    }

    private boolean containsConcreteFact(String value, ResearchSnapshot snapshot) {
        String lower = value.toLowerCase();
        String companyName = snapshot.companyName().toLowerCase();
        String city = snapshot.city().toLowerCase();
        return (!companyName.isBlank() && lower.contains(companyName))
                || (!city.isBlank() && lower.contains(city))
                || lower.matches(".*\\d[\\d\\s\\-()+]{2,}.*")
                || lower.contains("рейтинг")
                || lower.contains("отзыв")
                || lower.contains("цена")
                || lower.contains("адрес")
                || lower.contains("ул.")
                || lower.contains("улица")
                || lower.contains("сайт");
    }

    private List<String> qualityIssues(
            ReputationContentPack pack,
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        List<String> issues = new ArrayList<>();
        boolean economy = isEconomyProfile(request);
        int expectedAds = Math.min(boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK), economy ? 3 : 5);
        int expectedPosts = Math.min(boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK), economy ? 2 : 5);

        if (pack.utp().isEmpty() || pack.adTexts().size() < expectedAds || pack.reviewDraftTemplates().isEmpty()) {
            issues.add("не хватает обязательных блоков");
        }
        if (pack.socialPosts().size() < expectedPosts) {
            issues.add("мало полноценных постов/статей");
        }

        long humanAdCards = pack.adTexts().stream()
                .filter(text -> isHumanAdCard(text, economy))
                .count();
        if (!pack.adTexts().isEmpty() && humanAdCards < Math.min(pack.adTexts().size(), expectedAds)) {
            issues.add("рекламные карточки слишком сухие или короткие");
        }

        String marketingText = normalizeForQuality(String.join(" ", pack.utp()) + " " + String.join(" ", pack.adTexts()));
        if (containsServiceLabels(marketingText)) {
            issues.add("рекламные тексты похожи на анкету с техническими заголовками");
        }
        if (containsSourceVoice(marketingText)) {
            issues.add("рекламные тексты говорят от лица источников, а не компании");
        }
        if (hasLowVariety(pack.utp(), economy ? 4 : 5)) {
            issues.add("УТП слишком похожи друг на друга");
        }
        if (hasLowVariety(pack.adTexts(), economy ? 3 : 4)) {
            issues.add("рекламные тексты повторяют один и тот же сценарий");
        }
        if (hasStrongOverlap(pack.utp(), pack.adTexts())) {
            issues.add("УТП и рекламные тексты дублируют друг друга");
        }

        long longPosts = pack.socialPosts().stream()
                .filter(text -> text.length() >= (economy ? 500 : 700))
                .count();
        if (!pack.socialPosts().isEmpty() && longPosts < Math.min(pack.socialPosts().size(), expectedPosts)) {
            issues.add("посты слишком короткие");
        }
        long titledPosts = pack.socialPosts().stream()
                .filter(this::hasPostTitle)
                .count();
        if (!pack.socialPosts().isEmpty() && titledPosts < Math.min(pack.socialPosts().size(), expectedPosts)) {
            issues.add("постам не хватает заголовков");
        }

        long readyReviews = pack.reviewDraftTemplates().stream()
                .filter(text -> text.length() >= (economy ? 300 : 450))
                .count();
        if (!pack.reviewDraftTemplates().isEmpty() && readyReviews < Math.min(pack.reviewDraftTemplates().size(), economy ? 2 : 3)) {
            issues.add("черновики отзывов слишком короткие");
        }

        String allText = normalizeForQuality(String.join(" ", pack.utp())
                + " " + String.join(" ", pack.adTexts())
                + " " + String.join(" ", pack.socialPosts())
                + " " + String.join(" ", pack.reviewDraftTemplates()));
        if (allText.contains("коротко объясните клиенту") || allText.contains("адаптируйте формулировку")) {
            issues.add("остались шаблонные инструкции вместо готового текста");
        }

        List<String> baseSignals = baseSignals(snapshot, deepReport, request);
        int requiredBaseMatches = economy ? 1 : Math.min(2, baseSignals.size());
        if (!baseSignals.isEmpty() && countSignalMatches(allText, baseSignals) < requiredBaseMatches) {
            issues.add("не используются базовые данные компании");
        }

        List<String> sourceSignals = sourceSignals(snapshot);
        if (sourceSignals.size() >= 4 && countSignalMatches(allText, sourceSignals) < 2) {
            issues.add("не используются факты из публичных источников");
        }

        List<String> deepSignals = deepResearchSignals(deepReport);
        if (deepSignals.size() >= 4 && countSignalMatches(allText, deepSignals) < 3) {
            issues.add("не используются ключевые факты из глубокого отчета");
        }

        return issues;
    }

    private boolean isHumanAdCard(String value, boolean economy) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() < (economy ? 220 : 260)) {
            return false;
        }
        if (containsServiceLabels(normalized) || containsSourceVoice(normalized)) {
            return false;
        }

        int explanatorySignals = 0;
        for (String signal : List.of(
                "мы ", "помож", "подскаж", "оформ", "возьм", "разбер",
                "обрат", "остав", "заяв", "клиент", "водител", "удоб",
                "без лиш", "спокой", "довер", "стоимост", "срок"
        )) {
            if (normalized.contains(signal)) {
                explanatorySignals++;
            }
        }
        return explanatorySignals >= (economy ? 1 : 2);
    }

    private boolean containsServiceLabels(String normalized) {
        return normalized.contains("заголовок:")
                || normalized.contains("кому:")
                || normalized.contains("зачем:")
                || normalized.contains("почему можно доверять:")
                || normalized.contains("текст:")
                || normalized.contains("следующий шаг:")
                || normalized.contains("условия/сроки:")
                || normalized.contains("источник/уверенность:");
    }

    private boolean containsSourceVoice(String normalized) {
        return normalized.contains("сайт указывает")
                || normalized.contains("сайт заявляет")
                || normalized.contains("сайт описывает")
                || normalized.contains("официальный сайт указывает")
                || normalized.contains("официальный сайт описывает")
                || normalized.contains("2гис указывает")
                || normalized.contains("2гис показывает")
                || normalized.contains("urbanplaces указывает")
                || normalized.contains("по открытым данным")
                || normalized.contains("в открытых данных")
                || normalized.contains("источник подтверждает")
                || normalized.contains("по данным источников");
    }

    private boolean hasPostTitle(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return false;
        }
        String[] lines = text.split("\\R+");
        String firstLine = lines[0].trim();
        return firstLine.length() >= 12
                && firstLine.length() <= 120
                && lines.length >= 2
                && !firstLine.endsWith(".");
    }

    private boolean hasLowVariety(List<String> values, int minDistinctAngles) {
        if (values.size() < minDistinctAngles) {
            return false;
        }
        long distinctAngles = values.stream()
                .map(this::contentAngle)
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        return distinctAngles < minDistinctAngles;
    }

    private boolean hasStrongOverlap(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> leftAngles = left.stream()
                .map(this::contentAngle)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        long overlapping = right.stream()
                .map(this::contentAngle)
                .filter(leftAngles::contains)
                .count();
        return overlapping >= Math.min(3, right.size());
    }

    private String contentAngle(String value) {
        String normalized = normalizeForQuality(value);
        if (normalized.contains("протез") || normalized.contains("брекет") || normalized.contains("лечен")) {
            return "treatment";
        }
        if (normalized.contains("маршрут") || normalized.contains("логист") || normalized.contains("поезд")) {
            return "route";
        }
        if (normalized.contains("стоим") || normalized.contains("эконом") || normalized.contains("доступн") || normalized.contains("переплач")) {
            return "price";
        }
        if (normalized.contains("офис") || normalized.contains("благовещ") || normalized.contains("адрес")) {
            return "office";
        }
        if (normalized.contains("консультац") || normalized.contains("вопрос") || normalized.contains("обсуд")) {
            return "consultation";
        }
        if (normalized.contains("сопровожд") || normalized.contains("поддерж") || normalized.contains("связ")) {
            return "support";
        }
        if (normalized.contains("документ") || normalized.contains("запис") || normalized.contains("подготов")) {
            return "preparation";
        }
        if (normalized.contains("спокой") || normalized.contains("тревог") || normalized.contains("ясност") || normalized.contains("понят")) {
            return "clarity";
        }
        List<String> tokens = java.util.Arrays.stream(normalized.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 5)
                .filter(token -> !GENERIC_QUALITY_WORDS.contains(token))
                .limit(4)
                .toList();
        return String.join("-", tokens);
    }

    private List<String> deepResearchSignals(DeepCompanyResearchReport report) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        for (DeepCompanyResearchReport.Section section : report.sections()) {
            String normalizedTitle = normalizeForQuality(section.title());
            boolean important = normalizedTitle.contains("цен")
                    || normalizedTitle.contains("филиал")
                    || normalizedTitle.contains("клиент")
                    || normalizedTitle.contains("репутац")
                    || normalizedTitle.contains("довер")
                    || normalizedTitle.contains("сценари")
                    || normalizedTitle.contains("утп")
                    || normalizedTitle.contains("риск");
            if (!important) {
                continue;
            }
            for (String token : normalizeForQuality(section.body()).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 5 && !GENERIC_QUALITY_WORDS.contains(token)) {
                    signals.add(token);
                }
                if (signals.size() >= 30) {
                    return signals.stream().toList();
                }
            }
        }
        return signals.stream().toList();
    }

    private List<String> baseSignals(
            ResearchSnapshot snapshot,
            DeepCompanyResearchReport deepReport,
            ReputationContentPackRequest request
    ) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        addSignalAliases(signals, snapshot.companyName());
        addSignalAliases(signals, deepReport.companyName());
        if (shouldRequireSnapshotCity(snapshot, deepReport)) {
            addSignalAliases(signals, snapshot.city());
        }
        if (shouldRequireDeepReportCity(deepReport)) {
            addSignalAliases(signals, deepReport.city());
        }
        addSignalAliases(signals, request.productOrService());
        snapshot.products().stream().limit(3).forEach(value -> addSignalAliases(signals, value));
        return signals.stream().toList();
    }

    private boolean shouldRequireDeepReportCity(DeepCompanyResearchReport deepReport) {
        return !normalizeForQuality(deepReport.city()).isBlank();
    }

    private boolean shouldRequireSnapshotCity(ResearchSnapshot snapshot, DeepCompanyResearchReport deepReport) {
        String city = normalizeForQuality(snapshot.city());
        if (city.isBlank()) {
            return false;
        }

        List<String> deepParts = new ArrayList<>(deepReport.warnings());
        deepParts.addAll(deepReport.sections().stream()
                .map(section -> section.title() + " " + section.body())
                .toList());
        String deepText = normalizeForQuality(String.join(" ", deepParts));
        boolean cityIsDisputed = deepText.contains(city)
                && (deepText.contains("crm-город") || deepText.contains("crm город"))
                && (deepText.contains("не подтверж") || deepText.contains("противореч") || deepText.contains("спорн"));
        return !cityIsDisputed;
    }

    private List<String> sourceSignals(ResearchSnapshot snapshot) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        LinkedHashSet<String> excluded = new LinkedHashSet<>();
        addSignalAliases(excluded, snapshot.companyName());
        addSignalAliases(excluded, snapshot.city());
        snapshot.products().forEach(value -> addSignalAliases(excluded, value));
        for (String fact : extractEvidenceFacts(snapshot)) {
            for (String token : normalizeForQuality(fact).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 5 && !GENERIC_QUALITY_WORDS.contains(token) && !excluded.contains(token)) {
                    signals.add(token);
                }
                if (signals.size() >= 20) {
                    return signals.stream().toList();
                }
            }
        }
        return signals.stream().toList();
    }

    private void addSignal(LinkedHashSet<String> signals, String value) {
        String normalized = normalizeForQuality(value);
        if (normalized.length() >= 3) {
            signals.add(normalized);
        }
    }

    private void addSignalAliases(LinkedHashSet<String> signals, String value) {
        String normalized = normalizeForQuality(value);
        addSignal(signals, normalized);
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && !GENERIC_QUALITY_WORDS.contains(token)) {
                signals.add(token);
            }
        }
    }

    private long countSignalMatches(String text, List<String> signals) {
        return signals.stream()
                .filter(signal -> !signal.isBlank() && text.contains(signal))
                .count();
    }

    private String normalizeForQuality(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private int count(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private int boundedCount(Integer value, int fallback, int max) {
        return Math.min(count(value, fallback), max);
    }

    private boolean isEconomyProfile(ReputationContentPackRequest request) {
        return "economy".equalsIgnoreCase(valueOrBlank(request.contentPackProfile()));
    }

    private ReputationContentPackRequest compactRequest(ReputationContentPackRequest request) {
        return new ReputationContentPackRequest(
                request.productOrService(),
                request.manualDescription(),
                request.productsOrServices(),
                request.publicUrls(),
                request.includeCompanyWebsite(),
                Math.min(boundedCount(request.adTextsCount(), 6, MAX_AD_TEXTS_PER_PACK), 4),
                Math.min(boundedCount(request.socialPostsCount(), 4, MAX_SOCIAL_POSTS_PER_PACK), 2),
                Math.min(boundedCount(request.positiveReplyCount(), 6, MAX_POSITIVE_REPLIES_PER_PACK), 4),
                Math.min(boundedCount(request.negativeReplyCount(), 4, MAX_NEGATIVE_REPLIES_PER_PACK), 2),
                request.contentPackProfile()
        );
    }

    private record ParsedPack(ReputationContentPack pack, boolean compact) {
    }
}
