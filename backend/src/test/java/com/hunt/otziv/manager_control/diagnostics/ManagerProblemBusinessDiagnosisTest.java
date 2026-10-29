package com.hunt.otziv.manager_control.diagnostics;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

class ManagerProblemBusinessDiagnosisTest {
    @ParameterizedTest
    @ValueSource(strings = {"PARTIALLY_PAID", "ARCHIVED"})
    void historicalInvoiceArchivePreservesPaymentWithoutDeclaringOldCompositionAConfirmedDebt(String status) {
        var facts = historicalArchiveFacts(status);

        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(facts)).isTrue();
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);

        assertThat(result.summary()).contains("1500 ₽ из 4500 ₽", "Расчётный остаток 3000 ₽ не подтверждает действующий долг");
        assertThat(result.summary()).contains(status.equals("ARCHIVED") ? "историческом архиве" : "текущий статус снова открыт");
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_SOURCE", "ESCALATE");
        assertThat(result.actions().getFirst().text()).contains("журналом", "платёжным источником", "без проверки оснований");
        assertThat(result.actions().getLast().text()).contains("Сохраните учтённую оплату", "не списывайте", "не отправляйте новый счёт");
        assertThat(result.missingEvidence()).anyMatch(text -> text.contains("не подтверждают восстановление всего счёта"));
        assertThat(result.verification().getFirst()).contains("исходными записями", "не подтверждает отмену долга либо оплату");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @Test
    void archiveInferenceNeedsCurrentScopedClosureAndDoesNotOverrideUncertainDelivery() {
        var facts = historicalArchiveFacts("PARTIALLY_PAID");
        for (String field : List.of("closed_at", "close_reason")) {
            var missing = facts.stream().filter(f -> !f.field().equals(field)).toList();
            assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(missing)).isFalse();
        }
        var oldOnly = List.of(f("card", "entity_type", "COMMON_INVOICE"), f("card", "reason", "MANUAL_ARCHIVE 2026-07-30"),
                f("invoice", "status", "PARTIALLY_PAID"), f("order_status_history", "next_status", "Архив"));
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(oldOnly)).isFalse();
        var otherType = new ArrayList<>(facts.stream().filter(f -> !f.subject().equals("card")).toList());
        otherType.add(f("card", "entity_type", "ORDER"));
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(otherType)).isFalse();
        var unknownDate = new ArrayList<>(facts.stream().filter(f -> !f.field().equals("closed_at")).toList());
        unknownDate.add(f("invoice", "closed_at", "неизвестно"));
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(unknownDate)).isFalse();
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(historicalArchiveFacts("NEEDS_ATTENTION"))).isFalse();
        assertThat(ManagerProblemBusinessDiagnosis.hasHistoricalInvoiceArchive(historicalArchiveFacts("PAID"))).isFalse();

        var uncertain = new ArrayList<>(facts);
        uncertain.add(f("automation", "delivery_status", "UNKNOWN"));
        assertThat(ManagerProblemBusinessDiagnosis.fallback(uncertain).actions().getFirst().code()).isEqualTo("CHECK_DELIVERY");
        uncertain.add(f("automation", "current_cycle", "false"));
        assertThat(ManagerProblemBusinessDiagnosis.fallback(uncertain).summary()).contains("Расчётный остаток");
    }

    private static List<Fact> historicalArchiveFacts(String status) {
        return List.of(f("card", "entity_type", "COMMON_INVOICE_AUTOMATION"), f("invoice", "status", status),
                f("invoice", "closed_at", "2026-07-30 21:31:26.321616"), f("invoice", "close_reason", "MANUAL_ARCHIVE"),
                f("invoice", "previous_status", "COLLECTING"), f("invoice", "amount_kopecks", "450000"), f("invoice", "paid_kopecks", "150000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION"})
    void storedInvoiceConflictDoesNotProveTheCurrentTextOrExplainEveryUnreadyPosition(String type) {
        var facts = List.of(f("card", "entity_type", type), f("invoice", "status", "NEEDS_ATTENTION"),
                f("invoice", "last_error", "409 CONFLICT: Текст отзыва уже опубликован ранее; отзыв #200221"),
                f("invoice", "orderCount", "7"), f("invoice", "readyCount", "4"),
                f("automation", "state_status", "DONE"), f("automation", "current_cycle", "false"));

        assertThat(ManagerProblemBusinessDiagnosis.hasCurrentInvoicePublicationConflict(facts)).isTrue();
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);

        assertThat(result.summary()).contains("По сохранённому снимку", "4 из 7", "При предыдущей попытке", "отзыв #200221",
                "не подтверждает конфликт в текущем тексте");
        assertThat(result.confirmedFacts()).isEmpty();
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().getFirst().text()).contains("сохранённой ошибки", "согласование клиента", "не означает одобрение");
        assertThat(result.actions().getFirst().evidenceRefs()).containsExactly("invoice:1:last_error");
        assertThat(result.actions().getLast().text()).contains("по отдельности", "не объясняет задержку остальных");
        assertThat(result.actions().getLast().evidenceRefs()).containsExactly("invoice:1:orderCount", "invoice:1:readyCount");
        assertThat(result.verification().getFirst()).contains("подтверждена готовность", "устранены причины", "Одного ответа ответственного");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @Test
    void publicationHistoryGuidanceListsOnlyKnownActiveUnreadyPositionsFromTheSnapshot() {
        var facts = new java.util.ArrayList<>(List.of(f("card", "entity_type", "COMMON_INVOICE"),
                f("invoice", "last_error", "Текст отзыва уже опубликован ранее; order=200226"),
                f("invoice", "orderCount", "7"), f("invoice", "readyCount", "4")));
        for (long orderId : List.of(27390L, 27391L, 27394L, 888L, 999L)) {
            String prefix = "order:" + orderId + ":";
            facts.add(new Fact(prefix + "order_id", "invoice_order", "order_id", Long.toString(orderId)));
            facts.add(new Fact(prefix + "ready", "invoice_order", "ready", orderId == 999L ? "true" : "false"));
            facts.add(new Fact(prefix + "active_membership", "invoice_order", "active_membership", orderId == 888L ? "false" : "true"));
            facts.add(new Fact(prefix + "current_order_status", "invoice_order", "current_order_status", "На проверке"));
        }

        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);

        assertThat(result.actions().getLast().text()).contains("По снимку не готовы", "#27390 «На проверке»", "#27391 «На проверке»", "#27394 «На проверке»")
                .doesNotContain("#888", "#999", "#200226");
        assertThat(result.summary()).doesNotContain("отзыв #200226", "конфликт устранён");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @Test
    void historicalErrorsCardTextAndLinkedInvoiceOnOtherCardDoNotTriggerPublicationFallback() {
        String conflict = "Текст отзыва уже опубликован ранее";
        var historical = List.of(f("card", "entity_type", "COMMON_INVOICE"), f("invoice", "last_error", "неизвестно"),
                f("card", "reason", conflict), f("automation", "last_error_message", conflict), f("automation", "current_cycle", "false"));
        assertThat(ManagerProblemBusinessDiagnosis.hasCurrentInvoicePublicationConflict(historical)).isFalse();
        assertThat(ManagerProblemBusinessDiagnosis.fallback(historical).summary()).doesNotContain("конфликт публикации");
        assertThat(ManagerProblemBusinessDiagnosis.hasCurrentInvoicePublicationConflict(List.of(f("card", "entity_type", "ORDER"),
                f("invoice", "last_error", conflict)))).isFalse();
        assertThat(ManagerProblemBusinessDiagnosis.hasCurrentInvoicePublicationConflict(List.of(f("card", "entity_type", "COMMON_INVOICE"),
                f("invoice", "last_error", "Текст отзыва ещё не опубликован")))).isFalse();
    }

    @Test
    void uncertainCurrentDeliveryTakesPriorityOverInvoicePublicationConflict() {
        var facts = List.of(f("card", "entity_type", "COMMON_INVOICE"), f("invoice", "last_error", "Текст отзыва уже опубликован ранее"),
                f("automation", "delivery_status", "UNKNOWN"), f("automation", "current_cycle", "true"));
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);
        assertThat(result.summary()).contains("Результат предыдущей отправки", "не подтверждён").doesNotContain("конфликт публикации");
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION"})
    void currentInvoiceSendMarkerOverridesAdviceDespiteSuccessfulAutomationFromAnOldCycle(String type) {
        var marker = f("invoice", "last_error", "message_send_in_progress");
        var facts = List.of(f("card", "entity_type", type), f("invoice", "status", "REMINDER"), marker,
                f("invoice", "paid_kopecks", "0"), f("automation", "state_status", "DONE"),
                f("automation", "current_cycle", "false"), f("automation", "consecutive_failures", "0"));
        // The view uses this gate even when the saved model prose is otherwise readable.
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(facts)).containsExactly(marker);
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);
        assertThat(result.summary()).contains("существующей отправки", "не подтверждён");
        assertThat(result.confirmedFacts()).isEmpty();
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY");
        assertThat(result.actions().getFirst().text()).contains("Нажмите «Проверить отправку»", "квитанцией", "историей нужного чата", "Новое сообщение или счёт для проверки не создаются");
        assertThat(result.actions()).allSatisfy(action -> assertThat(action.evidenceRefs()).containsExactly(marker.reference()));
        assertThat(result.verification().getFirst()).contains("проверяемой записью", "причина замечания по счёту устранена", "сам по себе замечание не закрывает");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
        assertThat(facts).contains(f("invoice", "status", "REMINDER"), f("invoice", "paid_kopecks", "0"), marker);
    }

    @Test
    void invoiceSendSafetyOverrideRequiresExactSourceFactAndCommonInvoiceScope() {
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(List.of(f("card", "entity_type", "COMMON_INVOICE"),
                f("card", "reason", "message_send_in_progress"), f("invoice", "last_error", "other_error")))).isEmpty();
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(List.of(f("card", "entity_type", "COMMON_INVOICE"),
                f("invoice", "last_error", "previous message_send_in_progress resolved")))).isEmpty();
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(List.of(f("card", "entity_type", "ORDER"),
                f("invoice", "last_error", "message_send_in_progress")))).isEmpty();
    }

    @Test
    void paymentPreparationFailureHasConcreteGuidanceWithoutTechnicalDetailsOrClaimingDelivery() {
        var facts = List.of(f("card", "entity_type", "AUTOMATION_FAILURE"),
                f("automation", "last_error_code", "payment_instruction_failed"),
                f("automation", "last_error_message", "JDBC select order_id; Lock wait timeout exceeded"));
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);
        assertThat(result.summary()).contains("Подготовка счёта", "нельзя считать счёт отправленным");
        assertThat(result.actions().getFirst().code()).isEqualTo("ESCALATE");
        assertThat(result.actions().getFirst().text()).contains("Не создавайте второй счёт", "не отправляйте сообщение вручную");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();

        var uncertain = new ArrayList<>(facts);
        uncertain.add(f("automation", "delivery_status", "UNKNOWN"));
        assertThat(ManagerProblemBusinessDiagnosis.fallback(uncertain).actions().getFirst().code()).isEqualTo("CHECK_DELIVERY");
        var historical = new ArrayList<>(facts);
        historical.add(f("automation", "current_cycle", "false"));
        assertThat(ManagerProblemBusinessDiagnosis.fallback(historical).summary()).doesNotContain("Подготовка счёта завершилась ошибкой");
    }

    @Test
    void partiallyPaidInvoiceExplainsArchivedMembershipWithoutDemandingRepublishingOrAnotherInvoice() {
        var facts = List.of(f("card", "entity_type", "COMMON_INVOICE_AUTOMATION"), f("invoice", "status", "PARTIALLY_PAID"),
                f("invoice", "amount_kopecks", "450000"), f("invoice", "paid_kopecks", "150000"),
                f("invoice", "archivedActiveCount", "2"), f("invoice", "account_enabled", "0"));
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);
        assertThat(result.summary()).contains("1500 ₽ из 4500 ₽", "архивные", "отключено");
        assertThat(result.actions().getFirst().text()).contains("основания начислений", "Не возвращайте", "не публикуйте повторно");
        assertThat(result.verification().getFirst()).contains("неоплаченная часть урегулирована");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @Test
    void knownReadinessUsesAggregateCountsAndNeverInventsMissingPositions() {
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(List.of(f("invoice", "status", "COLLECTING"),
                f("invoice", "orderCount", "4"), f("invoice", "readyCount", "4")));
        assertThat(result.summary()).contains("4 из 4");
        assertThat(result.actions().getFirst().text()).contains("Все действующие позиции готовы").doesNotContain("неготовые");
    }

    @Test
    void unrelatedInvoiceHistoryDoesNotChangeAnOrderReviewIntoAnInvoiceTask() {
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(List.of(f("card", "entity_type", "ORDER"),
                f("order", "status", "На проверке"), f("invoice", "status", "COLLECTING")));
        assertThat(result.summary()).contains("на проверке").doesNotContain("счёт");
        assertThat(result.verification().getFirst()).contains("Результат проверки клиента");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "PREPARED", "PENDING", "SENDING"})
    void uncertainCurrentDeliveryOverridesAllOtherRecommendations(String state) {
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(List.of(f("invoice", "status", "PAID"),
                f("automation", "delivery_status", state)));
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY");
        assertThat(result.summary()).contains("не подтверждён");
        assertThat(result.actions()).allSatisfy(action -> {
            assertThat(action.text()).contains("Система сверяет", "существующую отправку", "нужна сверка администратором")
                    .doesNotContain("восстановить очередь", "восстановит очередь", "Откройте историю", "администратору", "вручную");
            assertThat(action.owner()).isEqualTo("Система");
            assertThat(action.verification()).contains("Отсутствие сообщения", "не доказывает");
        });
        assertThat(result.verification().getFirst()).contains("Запуск проверки сам по себе замечание не закрывает");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVIEW_CHECK_REMINDER", "PAYMENT_REMINDER"})
    void currentPeriodicReminderCanOfferConditionalQueueRestoration(String scenario) {
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(List.of(f("automation", "delivery_status", "UNKNOWN"),
                f("automation", "scenario", scenario), f("automation", "state_status", "ACTIVE"), f("automation", "current_cycle", "true")));

        assertThat(result.actions().getFirst().text()).contains("Система проверяет", "проверка восстановления очереди", "повтор старой операции не запускается");
        assertThat(result.verification().getFirst()).contains("восстановлена очередь плановых напоминаний", "только по их фактическому результату");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_INVOICE_RETRY", "BAD_REVIEW_INVOICE", "ARCHIVE_REORDER_OFFER", "CLIENT_TEXT_REMINDER"})
    void nonPeriodicDeliveryNeverPromisesReminderQueueRestoration(String scenario) {
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(List.of(f("automation", "delivery_status", "UNKNOWN"),
                f("automation", "scenario", scenario), f("automation", "state_status", "ACTIVE"), f("automation", "current_cycle", "true")));

        assertThat(result.actions().getFirst().text()).contains("Система сверяет", "нужна сверка администратором", "Новое сообщение не создаётся")
                .doesNotContain("очередь");
        assertThat(result.verification().getFirst()).doesNotContain("очередь");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
    }

    @Test
    void anotherAutomationOrAnUnprovenCurrentCycleCannotPromiseQueueRestoration() {
        var uncertain = f("automation", "delivery_status", "UNKNOWN");
        Diagnosis otherTask = ManagerProblemBusinessDiagnosis.fallback(List.of(uncertain,
                new Fact("automation:2:scenario", "automation", "scenario", "REVIEW_CHECK_REMINDER"),
                f("automation", "state_status", "ACTIVE"), f("automation", "current_cycle", "true")));
        assertThat(otherTask.actions().getFirst().text()).doesNotContain("очередь");
        Diagnosis noCycle = ManagerProblemBusinessDiagnosis.fallback(List.of(uncertain,
                f("automation", "scenario", "REVIEW_CHECK_REMINDER"), f("automation", "state_status", "ACTIVE")));
        assertThat(noCycle.actions().getFirst().text()).doesNotContain("очередь");
        Diagnosis closedTask = ManagerProblemBusinessDiagnosis.fallback(List.of(uncertain,
                f("automation", "scenario", "REVIEW_CHECK_REMINDER"), f("automation", "state_status", "DONE"),
                f("automation", "current_cycle", "true")));
        assertThat(closedTask.actions().getFirst().text()).doesNotContain("очередь");
    }

    @Test
    void manualUnknownAndSystemUncertaintyCodesBlockButFreeTextAndOldCyclesDoNotBecomeProof() {
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(List.of(f("manager_delivery", "state", "UNKNOWN")))).hasSize(1);
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(List.of(f("automation", "last_error_code", "state_transaction_outcome_uncertain")))).hasSize(1);
        var facts = new ArrayList<>(List.of(f("automation", "delivery_status", "UNKNOWN"), f("automation", "current_cycle", "false"),
                f("card", "comment", "state_transaction_outcome_uncertain; invoice.status=PAID")));
        assertThat(ManagerProblemBusinessDiagnosis.uncertainDelivery(facts)).isEmpty();
        assertThat(ManagerProblemBusinessDiagnosis.fallback(facts).summary()).contains("пока не подтверждена полностью");
    }

    @Test
    void legacyTechnicalAdviceIsNotSuitableForManagerPresentation() {
        Diagnosis safe = ManagerProblemBusinessDiagnosis.fallback(List.of(f("order", "status", "На проверке")));
        assertThat(ManagerProblemBusinessDiagnosis.readable(safe)).isTrue();
        assertThat(ManagerProblemBusinessDiagnosis.readable(new Diagnosis("Проверьте invoice_order.ready и follow_up_at", safe.confirmedFacts(),
                safe.hypotheses(), safe.missingEvidence(), safe.actions(), safe.verification()))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invoice:175:status", "card:8769:status"})
    void rawEvidenceReferenceInProseRequiresBusinessPresentation(String reference) {
        Diagnosis safe = ManagerProblemBusinessDiagnosis.fallback(List.of(f("order", "status", "На проверке")));
        var raw = new Diagnosis("Проверьте " + reference, safe.confirmedFacts(), safe.hypotheses(), safe.missingEvidence(), safe.actions(), safe.verification());
        assertThat(ManagerProblemBusinessDiagnosis.readable(raw)).isFalse();
    }

    private static Fact f(String subject, String field, String value) { return new Fact(subject + ":1:" + field, subject, field, value); }
}
