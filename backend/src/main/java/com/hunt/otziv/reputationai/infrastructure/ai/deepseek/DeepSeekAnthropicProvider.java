package com.hunt.otziv.reputationai.infrastructure.ai.deepseek;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeepSeekAnthropicProvider {

    private static final int SEARCH_STAGE_MAX_TOKENS = 5000;
    private static final int STAGE_REPAIR_MAX_TOKENS = 3200;
    private static final int SEARCH_INPUT_MAX_CHARS = 60000;
    private static final int EVIDENCE_MAX_CHARS_PER_STAGE = 16000;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>()\\\"']+");

    private static final List<SearchStage> COMPREHENSIVE_STAGES = List.of(
            new SearchStage("identity", """
                    Установи цифровую идентичность именно этой компании. Выполни отдельные точные запросы:
                    1) название + город + полный адрес; 2) site:2gis.ru; 3) site:yandex.ru/maps;
                    4) site:google.com/maps или maps.google.com; 5) site:zoon.ru;
                    6) официальный сайт и официальные страницы VK/Telegram/соцсетей.
                    Сопоставляй название, город, адрес, телефон и категорию. Одно совпадение только по названию недостаточно.
                    Для каждой площадки отметь: найдена точная карточка, найден сомнительный кандидат либо ничего не найдено.
                    """),
            new SearchStage("offers", """
                    Исследуй официальный профиль и клиентские условия: реальные услуги/товары, цены, сроки, режим работы,
                    способы записи и оплаты, доставку/самовывоз, гарантии, парковку и доступность. Приоритет — официальный сайт,
                    официальные соцсети и точные карточки компании. Не переноси средние отраслевые цены и сведения конкурентов.
                    Каждый факт должен содержать прямой URL и отметку совпадения идентичности компании.
                    """),
            new SearchStage("reputation", """
                    Исследуй репутацию именно этой компании: рейтинг, количество оценок, повторяющиеся темы отзывов,
                    жалобы, сильные стороны, упомянутых сотрудников и давность присутствия. Отдельно ищи точные карточки
                    2ГИС, Яндекс.Карт, Google Maps, Zoon, Flamp/Otzovik и других доступных площадок.
                    Не сочиняй тексты отзывов и не считай сниппет доказательством, если адрес/город/категория не совпадают.
                    """),
            new SearchStage("verification", """
                    Проведи контрольный поиск пробелов и противоречий из предыдущих этапов. Перепроверь спорные адреса,
                    телефоны, услуги, цены, режим, рейтинги и прямые URL дополнительными точными запросами.
                    Ищи альтернативное подтверждение важных фактов. Если два источника противоречат друг другу,
                    не выбирай удобный вариант: зафиксируй конфликт и понизь уверенность.
                    """)
    );

    private final ReputationAiProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    public boolean isAvailable() {
        ReputationAiProperties.DeepSeek deepSeek = properties.getDeepseek();
        return deepSeek.isAnthropicWebSearchEnabled()
                && !deepSeek.getApiKey().isBlank()
                && !deepSeek.getAnthropicBaseUrl().isBlank()
                && !deepSeek.getModel().isBlank();
    }

    public DeepSeekAnthropicResult research(
            String task,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            Duration timeout
    ) {
        if (!isAvailable()) {
            return error("DeepSeek Anthropic Web Search не настроен.");
        }

        ReputationAiProperties.DeepSeek deepSeek = properties.getDeepseek();
        if (deepSeek.isAnthropicDeepSearchEnabled() && isComprehensiveTask(task)) {
            return comprehensiveResearch(task, systemPrompt, userPrompt, maxTokens, timeout, deepSeek);
        }
        return executeRequest(task, researchSystemPrompt(systemPrompt), userPrompt, maxTokens, timeout, true, deepSeek);
    }

    private DeepSeekAnthropicResult comprehensiveResearch(
            String task,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            Duration timeout,
            ReputationAiProperties.DeepSeek deepSeek
    ) {
        int requestedPasses = Math.min(deepSeek.getAnthropicDeepSearchPasses(), COMPREHENSIVE_STAGES.size());
        List<StageEvidence> evidence = new ArrayList<>();
        List<String> stageWarnings = new ArrayList<>();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalWebSearchRequests = 0;
        int totalWebSources = 0;
        Set<String> allowedSourceUrls = new LinkedHashSet<>();

        String researchInput = limitRaw(userPrompt, SEARCH_INPUT_MAX_CHARS);
        allowedSourceUrls.addAll(extractUrls(researchInput));
        for (int index = 0; index < requestedPasses; index++) {
            SearchStage stage = COMPREHENSIVE_STAGES.get(index);
            String previousEvidence = "verification".equals(stage.key())
                    ? evidencePrompt(evidence)
                    : "-";
            DeepSeekAnthropicResult stageResult = executeRequest(
                    task + "-" + stage.key(),
                    searchStageSystemPrompt(),
                    searchStagePrompt(stage, researchInput, previousEvidence),
                    Math.min(SEARCH_STAGE_MAX_TOKENS, Math.max(1200, maxTokens)),
                    timeout,
                    true,
                    deepSeek
            );
            totalInputTokens += stageResult.inputTokens();
            totalOutputTokens += stageResult.outputTokens();
            totalWebSearchRequests += stageResult.webSearchRequests();
            totalWebSources += stageResult.webSources();
            allowedSourceUrls.addAll(stageResult.webSourceUrls());

            String stageJson = jsonObjectText(stageResult.text());
            boolean repaired = false;
            if (stageJson.isBlank() && !stageResult.text().isBlank()) {
                DeepSeekAnthropicResult repairResult = executeRequest(
                        task + "-" + stage.key() + "-repair",
                        stageRepairSystemPrompt(),
                        stageRepairPrompt(stage, stageResult),
                        STAGE_REPAIR_MAX_TOKENS,
                        timeout,
                        false,
                        deepSeek
                );
                totalInputTokens += repairResult.inputTokens();
                totalOutputTokens += repairResult.outputTokens();
                stageJson = jsonObjectText(repairResult.text());
                repaired = !stageJson.isBlank();
            }
            if (!stageJson.isBlank()) {
                evidence.add(new StageEvidence(
                        stage.key(),
                        limitRaw(stageJson, EVIDENCE_MAX_CHARS_PER_STAGE),
                        stageResult.webSourceUrls()
                ));
                log.info("DEEPSEEK_ANTHROPIC_STAGE task={} stage={} status={} webSources={}",
                        task, stage.key(), repaired ? "repaired" : "accepted", stageResult.webSources());
            } else {
                String detail = stageResult.errorMessage().isBlank()
                        ? "этап не вернул валидный JSON"
                        : stageResult.errorMessage();
                stageWarnings.add(stage.key() + ": " + detail);
                log.warn("DEEPSEEK_ANTHROPIC_STAGE task={} stage={} status=rejected webSources={} detail={}",
                        task, stage.key(), stageResult.webSources(), limit(detail, 240));
            }
        }

        if (evidence.isEmpty()) {
            return new DeepSeekAnthropicResult(
                    "", "", totalInputTokens, totalOutputTokens, totalWebSearchRequests, totalWebSources,
                    List.copyOf(allowedSourceUrls),
                    "Все этапы DeepSeek Web Search завершились без валидных результатов: " + String.join("; ", stageWarnings)
            );
        }

        DeepSeekAnthropicResult synthesis = executeRequest(
                task + "-synthesis",
                synthesisSystemPrompt(systemPrompt),
                synthesisPrompt(researchInput, evidence, stageWarnings, allowedSourceUrls),
                maxTokens,
                timeout,
                false,
                deepSeek
        );
        totalInputTokens += synthesis.inputTokens();
        totalOutputTokens += synthesis.outputTokens();
        totalWebSearchRequests += synthesis.webSearchRequests();
        totalWebSources += synthesis.webSources();

        log.info("DEEPSEEK_ANTHROPIC_DEEP_RESEARCH task={} stagesRequested={} stagesCompleted={} webSearchRequests={} webSources={} inputTokens={} outputTokens={}",
                task, requestedPasses, evidence.size(), totalWebSearchRequests, totalWebSources, totalInputTokens, totalOutputTokens);
        String sanitizedText = sanitizeSynthesisSources(synthesis.text(), allowedSourceUrls);
        return new DeepSeekAnthropicResult(
                synthesis.responseId(),
                sanitizedText,
                totalInputTokens,
                totalOutputTokens,
                totalWebSearchRequests,
                totalWebSources,
                List.copyOf(allowedSourceUrls),
                synthesis.errorMessage()
        );
    }

    private DeepSeekAnthropicResult executeRequest(
            String task,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            Duration timeout,
            boolean webSearch,
            ReputationAiProperties.DeepSeek deepSeek
    ) {

        try {
            String requestJson = objectMapper.writeValueAsString(buildRequestBody(
                    task,
                    systemPrompt,
                    userPrompt,
                    maxTokens,
                    webSearch,
                    deepSeek
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(deepSeek.getAnthropicBaseUrl() + "/v1/messages"))
                    .timeout(timeout == null ? deepSeek.getTimeout() : timeout)
                    .header("x-api-key", deepSeek.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = deepSeekError(response.body());
                log.warn("DeepSeek Anthropic Web Search returned HTTP {} task={}: {}",
                        response.statusCode(), task, limit(message, 300));
                return error("DeepSeek Anthropic Web Search вернул HTTP "
                        + response.statusCode() + ": " + limit(message, 700));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0)
                    + usage.path("cache_creation_input_tokens").asInt(0)
                    + usage.path("cache_read_input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            int webSearchRequests = usage.path("server_tool_use").path("web_search_requests").asInt(0);
            List<String> webSourceUrls = webSourceUrls(root.path("content"));
            int webSources = webSourceUrls.size();
            String text = extractText(root.path("content"));

            log.info("DEEPSEEK_ANTHROPIC_RESEARCH task={} webSearch={} responseId={} webSearchRequests={} webSources={} inputTokens={} outputTokens={}",
                    task,
                    webSearch,
                    root.path("id").asText(""),
                    webSearchRequests,
                    webSources,
                    inputTokens,
                    outputTokens);

            if (text.isBlank()) {
                return new DeepSeekAnthropicResult(
                        root.path("id").asText(""),
                        "",
                        inputTokens,
                        outputTokens,
                        webSearchRequests,
                        webSources,
                        webSourceUrls,
                        "DeepSeek Anthropic Web Search вернул пустой итоговый текст."
                );
            }
            return new DeepSeekAnthropicResult(
                    root.path("id").asText(""),
                    text,
                    inputTokens,
                    outputTokens,
                    webSearchRequests,
                    webSources,
                    webSourceUrls,
                    ""
            );
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return error("Запрос DeepSeek Anthropic Web Search был прерван.");
        } catch (Exception exception) {
            log.warn("DeepSeek Anthropic Web Search failed task={}: {}", task, exception.getMessage());
            return error("Запрос DeepSeek Anthropic Web Search не выполнен: " + exception.getMessage());
        }
    }

    private Map<String, Object> buildRequestBody(
            String task,
            String systemPrompt,
            String userPrompt,
            int maxTokens,
            boolean webSearch,
            ReputationAiProperties.DeepSeek deepSeek
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deepSeek.getModel());
        body.put("max_tokens", Math.max(1000, Math.min(maxTokens, deepSeek.getMaxTokens())));
        body.put("system", systemPrompt == null ? "" : systemPrompt.trim());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", userPrompt))
        )));
        if (webSearch) {
            body.put("tools", List.of(Map.of(
                    "type", "web_search_20250305",
                    "name", "web_search",
                    "max_uses", deepSeek.getAnthropicWebSearchMaxUses()
            )));
        }
        body.put("metadata", Map.of("user_id", safeUserId(task)));
        if (deepSeek.isThinkingEnabled()) {
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", 4096));
            body.put("output_config", Map.of("effort", deepSeek.getReasoningEffort()));
        }
        return body;
    }

    private String researchSystemPrompt(String systemPrompt) {
        return (systemPrompt == null ? "" : systemPrompt.trim()) + "\n\n"
                + "Используй встроенный web_search для актуального публичного исследования. "
                + "Сначала ищи точное сочетание названия, города и адреса; не смешивай одноимённые компании и другие отрасли. "
                + "Факт можно считать подтверждённым только если найденный источник относится к этой компании. "
                + "Не переноси цены, услуги, сотрудников, сроки или отзывы от конкурентов и отраслевых справочников. "
                + "В sources указывай прямые URL реально использованных результатов web_search. "
                + "Если релевантный источник не найден, явно укажи это в warnings вместо догадки. "
                + "Верни только один валидный JSON-объект без markdown-обёртки и пояснений до или после JSON.";
    }

    private boolean isComprehensiveTask(String task) {
        return "company-deep-research-report".equals(task)
                || "company-research-report".equals(task);
    }

    private String searchStageSystemPrompt() {
        return """
                Ты выполняешь один этап публичного исследования компании через встроенный web_search.
                Не составляй финальный маркетинговый отчёт. Собери структурированные доказательства для следующего этапа.

                Критические правила идентичности:
                - точное совпадение названия без города, адреса, телефона или категории не подтверждает компанию;
                - не смешивай филиалы, одноимённые организации, конкурентов и отраслевые справочники;
                - отсутствие информации не является доказательством отсутствия услуги;
                - прямой URL можно указывать только если он реально присутствовал в результате web_search;
                - сниппет поисковой выдачи помечай как snippet, а не как содержимое открытой страницы;
                - точные цены, рейтинг, количество отзывов, режим и телефон сохраняй только вместе с URL и датой проверки;
                - найденное противоречие сохраняй явно, не выбирая один вариант без основания.

                Верни только JSON-объект:
                {"stage":"ключ этапа","identity":{"name":"","city":"","address":"","phone":"","category":""},
                "facts":[{"claim":"","value":"","sourceUrl":"","sourceTitle":"","platform":"",
                "identityMatch":"exact|strong|partial|conflict","confidence":"high|medium|low","evidenceType":"page|snippet","evidence":""}],
                "candidateSources":[{"url":"","title":"","platform":"","identityMatch":"exact|strong|partial|conflict","reason":""}],
                "unresolved":[""],"conflicts":[""],"warnings":[""]}.
                Не добавляй markdown и поля вне этой схемы.
                """;
    }

    private String searchStagePrompt(SearchStage stage, String researchInput, String previousEvidence) {
        return """
                ЭТАП: %s

                ЦЕЛЬ И ОБЯЗАТЕЛЬНЫЙ ПЛАН ПОИСКА:
                %s

                CRM, ручные данные и исходная задача:
                %s

                Доказательства предыдущих этапов для контрольной перепроверки:
                %s

                Выполни максимально полезные отдельные запросы в пределах доступного лимита web_search.
                Не подменяй не найденные данные общими сведениями об отрасли.
                """.formatted(stage.key(), stage.objective(), researchInput, previousEvidence);
    }

    private String synthesisSystemPrompt(String originalSystemPrompt) {
        return (originalSystemPrompt == null ? "" : originalSystemPrompt.trim()) + "\n\n" + """
                Публичный поиск уже выполнен отдельными этапами. В этом финальном проходе web_search не подключён.
                Собери ответ строго в исходной JSON-схеме задачи, используя CRM и только переданный пакет доказательств.
                URL в sources копируй дословно из stage evidence; не создавай, не исправляй и не достраивай ссылки.
                Исключай факты с identityMatch=conflict. Факты с partial используй только как неподтверждённые наблюдения в warnings.
                Высокую уверенность давай при точном совпадении идентичности и прямом источнике либо при двух независимых подтверждениях.
                Не превращай отсутствие найденных данных в утверждение, что услуга, сотрудник или условие отсутствует.
                Не переноси цены, сроки, услуги, сотрудников и отзывы от конкурентов или похожих компаний.
                Сохрани существенные конфликты и пробелы в warnings. Верни только один валидный JSON-объект без markdown.
                """;
    }

    private String synthesisPrompt(
            String researchInput,
            List<StageEvidence> evidence,
            List<String> stageWarnings,
            Set<String> allowedSourceUrls
    ) {
        return """
                Исходная задача, CRM и ручные данные:
                %s

                Результаты управляемых поисковых этапов:
                %s

                Технические предупреждения этапов:
                %s

                Все URL, реально возвращённые Web Search (включая этапы, JSON которых не удалось восстановить):
                %s

                URL из последнего списка являются только кандидатами. Не считай сам URL доказанным фактом,
                если соответствующего утверждения нет в валидном JSON этапа. Но можешь сохранить такой URL
                в sourceReviews/candidate sources как требующий ручной проверки.

                Выполни финальную дедупликацию источников, проверку идентичности и сформируй JSON исходной задачи.
                """.formatted(
                researchInput,
                evidencePrompt(evidence),
                stageWarnings.isEmpty() ? "-" : String.join("\n", stageWarnings),
                allowedSourceUrls == null || allowedSourceUrls.isEmpty() ? "-" : String.join("\n", allowedSourceUrls)
        );
    }

    private String stageRepairSystemPrompt() {
        return """
                Ты восстанавливаешь структуру уже полученного результата исследования. Web Search недоступен.
                Преобразуй переданный текст в один валидный JSON строго по схеме этапа. Не добавляй фактов,
                источников, URL и выводов, которых нет во входе. Неполные сведения оставляй в unresolved/warnings.
                URL разрешено копировать только из исходного текста или списка реально возвращённых Web Search URL.
                Верни только JSON-объект без markdown и пояснений.
                """ + searchStageSystemPrompt();
    }

    private String stageRepairPrompt(SearchStage stage, DeepSeekAnthropicResult stageResult) {
        return """
                Ключ этапа: %s

                URL, реально возвращённые Web Search:
                %s

                Исходный текст этапа для структурного восстановления:
                %s
                """.formatted(
                stage.key(),
                stageResult.webSourceUrls().isEmpty() ? "-" : String.join("\n", stageResult.webSourceUrls()),
                limitRaw(stageResult.text(), EVIDENCE_MAX_CHARS_PER_STAGE)
        );
    }

    private String evidencePrompt(List<StageEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "-";
        }
        return evidence.stream()
                .map(item -> "=== " + item.stage() + " ===\n"
                        + "URL, реально возвращённые Web Search и разрешённые для этого этапа:\n"
                        + (item.allowedUrls().isEmpty() ? "-" : String.join("\n", item.allowedUrls()))
                        + "\nJSON доказательств:\n" + item.json())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("-");
    }

    private String jsonObjectText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> candidates = balancedJsonObjects(value);
        for (int index = candidates.size() - 1; index >= 0; index--) {
            String candidate = candidates.get(index);
            try {
                if (objectMapper.readTree(candidate).isObject()) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // Try the previous balanced object. Models sometimes emit a short example before the real result.
            }
        }
        return "";
    }

    private List<String> balancedJsonObjects(String value) {
        List<String> candidates = new ArrayList<>();
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (start < 0) {
                if (current == '{') {
                    start = index;
                    depth = 1;
                    inString = false;
                    escaped = false;
                }
                continue;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    candidates.add(value.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return candidates;
    }

    private String limitRaw(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String clean = value.trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    private List<String> extractUrls(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = URL_PATTERN.matcher(value);
        while (matcher.find()) {
            String url = matcher.group();
            while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";") || url.endsWith(":")) {
                url = url.substring(0, url.length() - 1);
            }
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return List.copyOf(urls);
    }

    private String sanitizeSynthesisSources(String value, Set<String> allowedUrls) {
        String json = jsonObjectText(value);
        if (json.isBlank()) {
            return value == null ? "" : value;
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (!(parsed instanceof ObjectNode root) || !(root.path("sources") instanceof ArrayNode sources)) {
                return json;
            }
            ArrayNode filtered = objectMapper.createArrayNode();
            int removed = 0;
            for (JsonNode source : sources) {
                String url = source.path("url").asText("").trim();
                if (url.isBlank() || allowedUrls.contains(url)) {
                    filtered.add(source);
                } else {
                    removed++;
                }
            }
            root.set("sources", filtered);
            if (removed > 0) {
                ArrayNode warnings;
                if (root.path("warnings") instanceof ArrayNode existingWarnings) {
                    warnings = existingWarnings;
                } else {
                    warnings = objectMapper.createArrayNode();
                    root.set("warnings", warnings);
                }
                warnings.add("Удалено источников с URL, которых не было в результатах Web Search или исходных ручных данных: " + removed + ".");
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            log.warn("DeepSeek synthesis source validation failed: {}", exception.getMessage());
            return json;
        }
    }

    private String extractText(JsonNode content) {
        StringBuilder result = new StringBuilder();
        if (content == null || !content.isArray()) {
            return "";
        }
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText(""))) {
                continue;
            }
            String text = block.path("text").asText("");
            if (!text.isBlank()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(text);
            }
        }
        return result.toString().trim();
    }

    private List<String> webSourceUrls(JsonNode content) {
        if (content == null || !content.isArray()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        for (JsonNode block : content) {
            if (!"web_search_tool_result".equals(block.path("type").asText(""))) {
                continue;
            }
            JsonNode results = block.path("content");
            if (!results.isArray()) {
                continue;
            }
            for (JsonNode result : results) {
                if ("web_search_result".equals(result.path("type").asText(""))
                        && !result.path("url").asText("").isBlank()) {
                    urls.add(result.path("url").asText("").trim());
                }
            }
        }
        return List.copyOf(urls);
    }

    private String safeUserId(String task) {
        String clean = task == null ? "reputation-research" : task.replaceAll("[^a-zA-Z0-9_-]", "-");
        return clean.isBlank() ? "reputation-research" : clean.substring(0, Math.min(clean.length(), 120));
    }

    private DeepSeekAnthropicResult error(String message) {
        return new DeepSeekAnthropicResult("", "", 0, 0, 0, 0, List.of(), message);
    }

    private String deepSeekError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("error").path("message").asText("");
            return message.isBlank() ? responseBody : message;
        } catch (Exception ignored) {
            return responseBody;
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "пустой ответ";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength).trim();
    }

    private record SearchStage(String key, String objective) {
    }

    private record StageEvidence(String stage, String json, List<String> allowedUrls) {
    }
}
