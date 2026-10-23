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
    @ValueSource(strings = {"COMMON_INVOICE", "COMMON_INVOICE_AUTOMATION"})
    void currentInvoiceConflictDoesNotExplainEveryUnreadyPosition(String type) {
        var facts = List.of(f("card", "entity_type", type), f("invoice", "status", "NEEDS_ATTENTION"),
                f("invoice", "last_error", "409 CONFLICT: Текст отзыва уже опубликован ранее"),
                f("invoice", "orderCount", "7"), f("invoice", "readyCount", "4"),
                f("automation", "state_status", "DONE"), f("automation", "current_cycle", "false"));

        assertThat(ManagerProblemBusinessDiagnosis.hasCurrentInvoicePublicationConflict(facts)).isTrue();
        Diagnosis result = ManagerProblemBusinessDiagnosis.fallback(facts);

        assertThat(result.summary()).contains("4 из 7", "В журнале счёта", "одного отзыва", "причины не подтверждены");
        assertThat(result.confirmedFacts()).isEmpty();
        assertThat(result.hypotheses()).isEmpty();
        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().getFirst().text()).contains("текущей ошибке", "не публикуйте отзыв повторно");
        assertThat(result.actions().getFirst().evidenceRefs()).containsExactly("invoice:1:last_error");
        assertThat(result.actions().getLast().text()).contains("по отдельности", "Не переносите причину");
        assertThat(result.actions().getLast().evidenceRefs()).containsExactly("invoice:1:orderCount", "invoice:1:readyCount");
        assertThat(result.verification().getFirst()).contains("подтверждена готовность", "устранены причины", "Одного ответа ответственного");
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
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY", "ESCALATE");
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
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY", "ESCALATE");
        assertThat(result.actions().getFirst().text()).contains("квитанцию", "историей чата", "не повторяйте", "не создавайте новый счёт");
        assertThat(result.actions()).allSatisfy(action -> assertThat(action.evidenceRefs()).containsExactly(marker.reference()));
        assertThat(result.verification().getFirst()).contains("проверяемой записью", "причина замечания по счёту устранена", "сама по себе замечание не закрывает");
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
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY", "ESCALATE");
        assertThat(result.summary()).contains("не подтверждён");
        assertThat(result.verification().getFirst()).contains("сами по себе замечание не закрывают");
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
