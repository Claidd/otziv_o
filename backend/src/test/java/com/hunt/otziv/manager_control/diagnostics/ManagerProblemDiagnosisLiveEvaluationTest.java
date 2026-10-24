package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.config.settings.api.ManagerProblemDiagnosticSettings;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.config.settings.service.ManagerProblemDiagnosticSettingsAdapter;
import com.hunt.otziv.reputationai.api.ProblemDiagnosticModel;
import com.hunt.otziv.reputationai.application.service.DeepSeekProblemDiagnosticModel;
import com.hunt.otziv.reputationai.config.ReputationAiProperties;
import com.hunt.otziv.reputationai.infrastructure.ai.deepseek.service.DeepSeekProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

/** Opt-in paid synthetic evaluation through the production worker, prompt, adapter and validator. No production data/DB. */
class ManagerProblemDiagnosisLiveEvaluationTest {
    @Test
    void historicalClaimGatePreservesDottedDatesAndRejectsUnqualifiedCurrentClaims() {
        assertThatCode(() -> assertHistoricalClaimsAreQualified(
                "Карточка заведена 17.07 из-за того, что клиент 12 дней не проверял шаблоны; напоминание отмечено отправленным. "
                        + "Сейчас заказ оплачен и не ожидает клиента."))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> assertHistoricalClaimsAreQualified(
                "Клиент написал 33 минуты назад и остался без ответа; исходная переписка недоступна."))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertHistoricalClaimsAreQualified(
                "Клиент не проверил шаблоны, задача остаётся в контроле. Оплата заказа не заменяет проверку шаблонов."))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MANAGER_AUDIT_LIVE_EVAL", matches = "1")
    void currentModelProducesValidEvidenceBoundDiagnosesAndPreservesDeliverySafety() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("Provide DEEPSEEK_API_KEY securely to the evaluation process").isNotBlank();
        ObjectMapper mapper = new ObjectMapper();
        ReputationAiProperties properties = new ReputationAiProperties();
        properties.getDeepseek().setApiKey(apiKey);
        String configuredModel = System.getenv("DEEPSEEK_MODEL");
        if (configuredModel != null && !configuredModel.isBlank()) properties.getDeepseek().setModel(configuredModel);
        var model = spy(new DeepSeekProblemDiagnosticModel(new DeepSeekProvider(properties, mapper), properties));
        List<Map<String, Object>> providerCalls = new ArrayList<>();
        doAnswer(call -> {
            ProblemDiagnosticModel.Input input = call.getArgument(0);
            ProblemDiagnosticModel.Result response = (ProblemDiagnosticModel.Result) call.callRealMethod();
            boolean validCompletion = !response.text().isBlank() && response.errorMessage().isBlank();
            providerCalls.add(Map.of(
                    "case", caseName(mapper.readTree(input.evidenceJson()).path("caseId").asLong()),
                    "maxOutputTokens", input.maxOutputTokens(), "inputTokens", response.inputTokens(),
                    "outputTokens", response.outputTokens(), "validCompletion", validCompletion,
                    "failureMetadata", validCompletion ? "" : ManagerProblemDiagnosisWorker.modelFailure(response)));
            return response;
        }).when(model).analyze(any());
        var storedSettings = mock(AppSettingService.class);
        when(storedSettings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1, Integer.class));
        int maxOutputTokens = new ManagerProblemDiagnosticSettingsAdapter(storedSettings).maxOutputTokens();
        var settings = mock(ManagerProblemDiagnosticSettings.class);
        when(settings.maxRequestsPerHour()).thenReturn(60);
        when(settings.maxOutputTokens()).thenReturn(maxOutputTokens);
        when(settings.timeoutSeconds()).thenReturn(45);
        when(settings.thinkingEnabled()).thenReturn(true);
        var diagnostics = mock(ManagerProblemDiagnosticsService.class);
        when(diagnostics.mode()).thenReturn("SHADOW");
        var store = mock(ManagerProblemDiagnosisStore.class);
        var metrics = new SimpleMeterRegistry();
        var worker = new ManagerProblemDiagnosisWorker(diagnostics, mock(ManagerProblemEvidenceCollector.class), store,
                new ManagerProblemDiagnosisValidator(mapper), model, settings, mapper, metrics);
        var invoice = job(1, List.of(
                new Fact("card:9000:entity_type", "card", "entity_type", "COMMON_INVOICE"),
                new Fact("invoice:9001:status", "invoice", "status", "COLLECTING"),
                new Fact("invoice:9001:orderCount", "invoice", "orderCount", "2"),
                new Fact("invoice:9001:readyCount", "invoice", "readyCount", "1"),
                new Fact("invoice:9001:archivedActiveCount", "invoice", "archivedActiveCount", "0"),
                new Fact("invoice:9001:orderIds", "invoice", "orderIds", "90011,90012"),
                new Fact("order:90011:ready", "invoice_order", "ready", "true"),
                new Fact("order:90012:ready", "invoice_order", "ready", "false")));
        var uncertainDelivery = job(2, List.of(
                new Fact("automation:9002:state_status", "automation", "state_status", "ACTIVE"),
                new Fact("automation:9002:delivery_status", "automation", "delivery_status", "UNCERTAIN"),
                new Fact("automation:9002:last_error_code", "automation", "last_error_code", "transaction_outcome_uncertain"),
                new Fact("automation:9002:consecutive_failures", "automation", "consecutive_failures", "3"),
                new Fact("automation:9002:last_error_message", "automation", "last_error_message", "Внешняя отправка началась, подтверждение доставки не получено")));
        var largeInvoice = job(3, largeSyntheticInvoice());
        assertThat(largeInvoice.facts()).hasSize(433); // Original 432 source facts + production aggregate money display.
        var historicalChat = job(4, historicalUnavailableChat());
        var historicalReview = job(5, historicalReviewForCurrentlyPaidOrder());
        boolean invoiceOnly = "1".equals(System.getenv("RUN_MANAGER_DIAG_INVOICE_ONLY"));
        var fixtures = invoiceOnly ? List.of(invoice, largeInvoice) : List.of(invoice, uncertainDelivery, largeInvoice, historicalChat, historicalReview);
        var pending = new java.util.ArrayDeque<>(fixtures);
        when(store.claim(anyBoolean(), anyLong())).thenAnswer(ignored -> pending.removeFirst());
        List<Map<String, Object>> results = new ArrayList<>();
        doAnswer(call -> {
            ManagerProblemDiagnosisStore.Stored source = call.getArgument(0);
            Diagnosis diagnosis = call.getArgument(1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("case", caseName(source.caseId()));
            result.put("validated", true);
            result.put("inputFactCount", source.facts().size());
            result.put("summaryLength", diagnosis.summary().length());
            result.put("summary", diagnosis.summary());
            result.put("confirmedFacts", diagnosis.confirmedFacts());
            result.put("hypotheses", diagnosis.hypotheses());
            result.put("actions", diagnosis.actions());
            result.put("verification", diagnosis.verification());
            result.put("missingEvidence", diagnosis.missingEvidence());
            Diagnosis view = ManagerProblemBusinessDiagnosis.withClosurePolicy(source.facts(), diagnosis);
            result.put("readSideActions", view.actions());
            result.put("readSideVerification", view.verification());
            result.put("humanReviewRubric", humanReviewRubric(source.caseId()));
            result.put("model", call.getArgument(2));
            result.put("inputTokens", call.getArgument(3));
            result.put("outputTokens", call.getArgument(4));
            results.add(result);
            return true;
        }).when(store).complete(any(), any(), anyString(), anyInt(), anyInt());
        doAnswer(call -> {
            ManagerProblemDiagnosisStore.Stored source = call.getArgument(0);
            results.add(Map.of("case", caseName(source.caseId()), "inputFactCount", source.facts().size(),
                    "validated", false, "error", call.getArgument(1, String.class)));
            return null;
        }).when(store).retry(any(), anyString());
        long started = System.nanoTime();
        fixtures.forEach(ignored -> worker.processNext());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "manager-diagnosis-live-evaluation-v1");
        report.put("evaluatedAt", Instant.now().toString());
        report.put("configuredModel", properties.getDeepseek().getModel());
        report.put("promptVersion", PROMPT_VERSION);
        report.put("fixtureRevision", "typed-invoice-and-production-derived-facts-v2");
        report.put("invoiceOnly", invoiceOnly);
        report.put("maxOutputTokens", maxOutputTokens);
        report.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        report.put("inputTokens", metrics.counter("manager.problem.diagnostics.tokens", "direction", "input").count());
        report.put("outputTokens", metrics.counter("manager.problem.diagnostics.tokens", "direction", "output").count());
        report.put("providerCalls", providerCalls);
        report.put("results", results);
        Path destination = Path.of("target", "manager-diagnosis-evaluation", "report.json");
        Files.createDirectories(destination.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), report);
        assertThat(results).as("All selected live synthetic diagnoses must complete; see %s", destination).hasSize(fixtures.size());
        assertThat(results).allSatisfy(result -> assertThat(result.get("validated")).as(result.get("case").toString()).isEqualTo(true));
        verify(store, never()).retry(any(), anyString());
        var output = org.mockito.ArgumentCaptor.forClass(Diagnosis.class);
        verify(store, times(fixtures.size())).complete(any(), output.capture(), anyString(), anyInt(), anyInt());
        assertThat(providerCalls).allSatisfy(call -> {
            assertThat(call.get("validCompletion")).as("Complete provider response: %s", call).isEqualTo(true);
            assertThat(((Number) call.get("outputTokens")).intValue()).isBetween(1, ((Number) call.get("maxOutputTokens")).intValue());
        });
        assertThat(output.getAllValues()).allSatisfy(diagnosis ->
                assertThat(diagnosis.actions()).allSatisfy(action -> assertThat((action.text() + " " + action.verification()).toLowerCase(java.util.Locale.ROOT))
                        .doesNotContain("отгруз", "перевозчик", "посылк", "трек-номер")));
        assertThat(output.getAllValues().getFirst().confirmedFacts()).isNotEmpty();
        if (!invoiceOnly) {
            assertThat(output.getAllValues().get(1).actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY");
            assertThat(output.getAllValues().get(1).actions().getFirst().text()).contains("Повторное сообщение для проверки не отправляется");
        }
        int largeIndex = invoiceOnly ? 1 : 2;
        Diagnosis largeDiagnosis = output.getAllValues().get(largeIndex);
        assertThat(largeDiagnosis.confirmedFacts()).hasSizeBetween(1, 10);
        assertThat(largeDiagnosis.summary()).hasSizeLessThanOrEqualTo(400);
        assertThat(largeDiagnosis.missingEvidence()).isNotEmpty();
        assertThat(largeDiagnosis.verification()).isNotEmpty();
        var largeCalls = providerCalls.stream().filter(call -> caseName(3).equals(call.get("case"))).toList();
        assertThat(largeCalls).hasSizeBetween(1, 2);
        assertThat(((Number) results.get(largeIndex).get("outputTokens")).intValue())
                .as("Every large-case call obeys its own budget; reported cost includes a bounded schema retry")
                .isEqualTo(largeCalls.stream().mapToInt(call -> ((Number) call.get("outputTokens")).intValue()).sum())
                .isBetween(1, maxOutputTokens * largeCalls.size());

        if (invoiceOnly) return;

        Diagnosis chatDiagnosis = output.getAllValues().get(3);
        assertThat(ManagerProblemBusinessDiagnosis.readable(chatDiagnosis)).as("The historical chat result must be usable without a readability fallback").isTrue();
        assertThat(groundedReferences(chatDiagnosis)).containsAnyOf("card:9400:control_date", "chat:9401:availability");
        assertThat(chatDiagnosis.missingEvidence()).as("Unavailable conversation cannot prove an unanswered current message").isNotEmpty();
        assertThat(mentionsAny(normalize(chatDiagnosis.summary()), "истор", "стар", "преж", "июл", "актуаль", "недоступ", "не подтверж", "неизвест"))
                .as("The summary must disclose historical or unavailable current context: %s", chatDiagnosis.summary()).isTrue();
        assertHistoricalClaimsAreQualified(chatDiagnosis);

        Diagnosis paidDiagnosis = output.getAllValues().get(4);
        assertThat(ManagerProblemBusinessDiagnosis.readable(paidDiagnosis)).as("The current paid-order result must be usable without a readability fallback").isTrue();
        assertThat(groundedReferences(paidDiagnosis)).as("Current authoritative order state must ground the diagnosis")
                .contains("order:9501:status");
        assertThat(mentionsAny(normalize(paidDiagnosis.summary()), "оплач", "оплат", "платеж подтверж"))
                .as("The summary must account for the current payment state: %s", paidDiagnosis.summary()).isTrue();
        assertHistoricalClaimsAreQualified(paidDiagnosis);
    }

    private static String caseName(long id) {
        return switch ((int) id) {
            case 1 -> "invoice_publication_pending";
            case 2 -> "uncertain_delivery";
            case 3 -> "large_invoice_with_bounded_history";
            case 4 -> "historical_relative_age_with_unavailable_chat";
            case 5 -> "historical_review_reason_with_current_paid_order";
            default -> throw new IllegalArgumentException("Unknown synthetic case");
        };
    }

    private static List<String> humanReviewRubric(long id) {
        return switch ((int) id) {
            case 4 -> List.of("The July note does not establish that the client wrote 33 minutes before this analysis.",
                    "Unavailable chat means the current question, reply and problem are unconfirmed; no invented current deadline.");
            case 5 -> List.of("Current paid status and no client waiting take precedence over the July review reminder.",
                    "Do not assert that templates remain unchecked; reconcile the old remark with current source facts without a new send or payment.");
            default -> List.of("Review the synthetic summary, advice and verification against the supplied facts; schema validity alone does not prove semantic correctness.");
        };
    }

    /** Flexible regression checks, not an exact expected answer; the full synthetic output is retained for human review. */
    private static void assertHistoricalClaimsAreQualified(Diagnosis diagnosis) {
        assertHistoricalClaimsAreQualified(diagnosis.summary());
    }

    private static void assertHistoricalClaimsAreQualified(String summary) {
        var sentences = java.text.BreakIterator.getSentenceInstance(java.util.Locale.forLanguageTag("ru"));
        sentences.setText(summary);
        for (int start = sentences.first(), end = sentences.next(); end != java.text.BreakIterator.DONE;
                start = end, end = sentences.next()) {
            String sentence = summary.substring(start, end);
            String text = normalize(sentence);
            boolean relativeAge = text.contains("33") && text.contains("минут");
            boolean stillWaitingForReview = text.contains("шаблон")
                    && mentionsAny(text, "не провер", "непровер", "ожида", "ждет");
            if (relativeAge || stillWaitingForReview) {
                assertThat(mentionsAny(text, "карточк", "запис", "указ", "отмеч", "истор", "стар", "преж", "июл",
                        "ранее", "тогда", "не подтверж", "неизвест", "нельзя", "не означает", "не доказывает"))
                        .as("A historical allegation must be attributed or explicitly unconfirmed, not asserted as current: %s", sentence)
                        .isTrue();
            }
        }
    }

    private static List<String> groundedReferences(Diagnosis diagnosis) {
        List<String> references = new ArrayList<>();
        diagnosis.confirmedFacts().forEach(finding -> references.addAll(finding.evidenceRefs()));
        diagnosis.actions().forEach(action -> references.addAll(action.evidenceRefs()));
        return references;
    }

    private static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
    }

    private static boolean mentionsAny(String text, String... concepts) {
        return java.util.Arrays.stream(concepts).anyMatch(text::contains);
    }

    private static List<Fact> historicalUnavailableChat() {
        List<Fact> facts = new ArrayList<>();
        row(facts, "card:9400", "card", "control_date", "2026-07-16", "entity_type", "CLIENT_CHAT_UNANSWERED",
                "entity_id", "9401", "item_status", "OPEN", "title", "Синтетическая переписка",
                "reason", "Клиент написал 33 минуты назад и остался без ответа.",
                "last_manual_touch_at", "неизвестно", "follow_up_at", "неизвестно");
        facts.add(new Fact("chat:9401:availability", "coverage", "availability", "Исходная переписка недоступна"));
        return List.copyOf(facts);
    }

    private static List<Fact> historicalReviewForCurrentlyPaidOrder() {
        List<Fact> facts = new ArrayList<>();
        row(facts, "card:9500", "card", "control_date", "2026-07-17", "entity_type", "ORDER",
                "entity_id", "9501", "item_status", "ACTION_TAKEN", "title", "Синтетический заказ",
                "reason", "Клиент не проверил шаблоны 12 дней.", "comment", "Напоминание было отмечено как отправленное.",
                "last_manual_touch_at", "2026-07-17T10:00:00", "follow_up_at", "2026-07-19T10:00:00");
        row(facts, "order:9501", "order", "order_id", "9501", "status", "Оплачено",
                "order_waiting_for_client", "false", "client_message_generation", "1",
                "cycle_started_at", "2026-08-22T10:00:00", "waiting_cycle_started_at", "2026-08-22T10:00:00");
        facts.add(new Fact("order:9501:resolution_coverage", "coverage", "business_resolution",
                "Доставка напоминания не подтверждает проверку клиентом, оплату или завершение заказа; результат проверяется отдельно"));
        return List.copyOf(facts);
    }

    /** 432 synthetic fields mirror a seven-position invoice, 14 states and bounded attempt history. */
    private static List<Fact> largeSyntheticInvoice() {
        List<Fact> facts = new ArrayList<>();
        String unknown = "неизвестно";
        String observed = "2026-10-20 10:00:00.000000";
        row(facts, "card:9900", "card", "title", "Синтетический счёт для проверки объёма",
                "entity_type", "COMMON_INVOICE", "entity_id", "9901", "item_status", "DEFERRED",
                "reason", "Проверить готовность результатов", "comment", "Синтетический комментарий",
                "follow_up_at", unknown, "last_manual_touch_at", unknown, "resolved_at", unknown);
        row(facts, "invoice:9901", "invoice", "invoice_id", "9901", "status", "NEEDS_ATTENTION",
                "amount_kopecks", "140000", "paid_kopecks", "0", "last_error", "Не все результаты публикации подтверждены",
                "sent_at", unknown, "next_reminder_at", unknown, "payment_success_notification_error", unknown,
                "orderCount", "7", "readyCount", "3", "orderIds", "99011,99012,99013,99014,99015,99016,99017");
        for (int position = 0; position < 7; position++) {
            long orderId = 99011 + position;
            boolean ready = position == 2 || position == 3 || position == 5;
            boolean active = !ready && position != 6;
            row(facts, "order:" + orderId, "invoice_order", "order_id", Long.toString(orderId),
                    "ready", Boolean.toString(ready), "paid", "false", "active_membership", "true",
                    "publication_blocker_since", active ? observed : unknown, "paid_at", unknown);
            for (int kind = 0; kind < 2; kind++) {
                long stateId = 990100 + position * 2L + kind;
                row(facts, "automation:" + stateId, "automation", "state_id", Long.toString(stateId),
                        "scenario", kind == 0 ? "REVIEW_CHECK_REMINDER" : "REVIEW_CHECK_AUTO_ARCHIVE",
                        "order_id", Long.toString(orderId), "state_status", active ? "ACTIVE" : "DONE",
                        "last_error_code", unknown, "last_error_message", unknown, "consecutive_failures", "0",
                        "next_attempt_at", active ? "2026-10-25 10:00:00.000000" : unknown,
                        "last_attempt_at", kind == 0 || !active ? observed : unknown,
                        "last_success_at", kind == 0 ? observed : unknown,
                        "delivery_status", unknown, "delivery_recovery_checked_at", unknown);
                int attempts = kind == 0 ? 5 : active ? 0 : 1;
                for (int attempt = 0; attempt < attempts; attempt++) {
                    long attemptId = 991000 + position * 20L + kind * 10L + attempt;
                    boolean skipped = !active && attempt == 0;
                    row(facts, "attempt:" + attemptId, "automation_attempt", "attempt_id", Long.toString(attemptId),
                            "attempt_status", skipped ? "SKIPPED" : "SENT",
                            "error_code", skipped ? "order_status_synchronized" : unknown,
                            "error_message", skipped ? "Синтетическая служебная запись: состояние заказа изменилось" : unknown,
                            "attempted_at", observed);
                }
                if (kind == 0) facts.add(new Fact("automation:" + stateId + ":history_coverage", "history_coverage",
                        "attempt_history_truncated", "Показаны только 5 последних попыток; наличие более ранних действий этим срезом не исключено"));
            }
        }
        return List.copyOf(facts);
    }

    private static void row(List<Fact> facts, String prefix, String subject, String... fields) {
        for (int i = 0; i < fields.length; i += 2) facts.add(new Fact(prefix + ":" + fields[i], subject, fields[i], fields[i + 1]));
    }

    private static ManagerProblemDiagnosisStore.Stored job(long id, List<Fact> facts) {
        var derived = new ArrayList<>(facts);
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(derived);
        ManagerProblemEvidenceCollector.appendInvoiceArchiveChronology(derived);
        return new ManagerProblemDiagnosisStore.Stored(id, 1, 1, 2, "RUNNING", LocalDateTime.now(), List.copyOf(derived),
                null, null, PROMPT_VERSION, "synthetic-lease-" + id, 1);
    }
}
