package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

class ManagerProblemInvoiceReadinessPolicyTest {
    @Test
    void bothClosureAndActionChecksRejectDeadlineWhilePreservingModelAdviceAndStoredJson() throws Exception {
        var original = advice();
        String stored = new ObjectMapper().writeValueAsString(original);
        var result = ManagerProblemBusinessDiagnosis.withClosurePolicy(facts(), original);
        assertThat(result.summary()).isEqualTo(original.summary());
        for (int i = 0; i < original.actions().size(); i++) {
            var before = original.actions().get(i);
            var after = result.actions().get(i);
            assertThat(after.code()).isEqualTo(before.code());
            assertThat(after.text()).isEqualTo(before.text());
            assertThat(after.owner()).isEqualTo(before.owner());
            assertThat(after.evidenceRefs()).isEqualTo(before.evidenceRefs());
        }
        assertThat(result.actions().getFirst().verification()).contains("записью исходного", "остаётся открытым").doesNotContain("готовность либо срок");
        assertThat(result.actions().getLast().verification()).contains("отложено", "остаётся открытым");
        assertThat(result.verification().getFirst()).contains("подтверждённо готовы", "ненужность или исключение", "причина замечания устранена", "сама причина отсутствует или больше не актуальна");
        assertThat(result.verification().getLast()).contains("лишь отложить", "не закрывают");
        assertThat(ManagerProblemBusinessDiagnosis.readable(result)).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(original)).isEqualTo(stored);
    }

    @Test
    void unknownCountsAndOtherBusinessStagesNeverBecomeReadinessOrOverrideTheirPolicy() {
        List<List<Fact>> excluded = new ArrayList<>();
        for (String status : List.of("PARTIALLY_PAID", "INVOICED", "NEEDS_ATTENTION", "ARCHIVED", "PAID"))
            excluded.add(replace("invoice", "status", status));
        for (String total : List.of("0", "-1", "неизвестно", "1.5", "")) excluded.add(replace("invoice", "orderCount", total));
        for (String ready : List.of("2", "3", "-1", "неизвестно", "")) excluded.add(replace("invoice", "readyCount", ready));
        excluded.add(replace("card", "entity_type", "ORDER"));
        excluded.add(replace("invoice", "archivedActiveCount", "1"));
        excluded.add(replace("invoice", "archivedActiveCount", "неизвестно"));
        var archiveCountMissing = facts(); archiveCountMissing.removeIf(f -> f.field().equals("archivedActiveCount")); excluded.add(archiveCountMissing);
        var uncertain = facts();
        uncertain.add(new Fact("automation:1:delivery_status", "automation", "delivery_status", "UNKNOWN"));
        excluded.add(uncertain);
        var archive = facts();
        archive.add(new Fact("invoice:1:close_reason", "invoice", "close_reason", "MANUAL_ARCHIVE"));
        archive.add(new Fact("invoice:1:closed_at", "invoice", "closed_at", "2026-07-30T21:31:26"));
        excluded.add(archive);
        var missing = facts(); missing.removeIf(f -> f.field().equals("orderCount")); excluded.add(missing);
        var original = advice();
        assertThat(excluded).allSatisfy(facts -> {
            assertThat(ManagerProblemBusinessDiagnosis.ordinaryInvoiceReadiness(facts)).isFalse();
            assertThat(ManagerProblemBusinessDiagnosis.withClosurePolicy(facts, original)).isSameAs(original);
        });
        assertThat(ManagerProblemBusinessDiagnosis.ordinaryInvoiceReadiness(replace("card", "entity_type", "COMMON_INVOICE_AUTOMATION"))).isTrue();
    }

    @Test
    void archivePromptBytesFromTheAcceptedRealEvaluationRemainIdentical() throws Exception {
        var archive = replace("invoice", "status", "PARTIALLY_PAID");
        archive.add(new Fact("invoice:1:money_display", "invoice_money", "money_display", "1500 ₽"));
        archive.add(new Fact("invoice:1:archive_chronology", "invoice_chronology", "historical_archive_and_current_status", "historical archive"));
        String prompt = ManagerProblemDiagnosisWorker.promptFor(archive);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("670058648c6b8a92cae664326841697835f6dbf98e2b0f68a7a2d3f607bcc8a7");
    }

    @Test
    void onlyCurrentTypedReadinessGetsTheAdditionalPromptAndWaitingIsNotClosure() {
        assertThat(ManagerProblemDiagnosisWorker.promptFor(facts())).contains("только отложить проверку", "при открытом замечании");
        assertThat(ManagerProblemDiagnosisWorker.promptFor(replace("invoice", "status", "NEEDS_ATTENTION")))
                .doesNotContain("Это текущая сборка общего счёта");
    }

    static List<Fact> facts() {
        return new ArrayList<>(List.of(new Fact("card:1:entity_type", "card", "entity_type", "COMMON_INVOICE"),
                new Fact("invoice:1:status", "invoice", "status", "COLLECTING"),
                new Fact("invoice:1:orderCount", "invoice", "orderCount", "2"),
                new Fact("invoice:1:readyCount", "invoice", "readyCount", "1"),
                new Fact("invoice:1:archivedActiveCount", "invoice", "archivedActiveCount", "0")));
    }

    private static List<Fact> replace(String subject, String field, String value) {
        var result = facts();
        result.replaceAll(f -> f.subject().equals(subject) && f.field().equals(field) ? new Fact(f.reference(), subject, field, value) : f);
        return result;
    }

    static Diagnosis advice() {
        return new Diagnosis("Одна из двух позиций не готова к счёту.", List.of(), List.of(), List.of(),
                List.of(new Action("CHECK_SOURCE", "Сверьте исходный заказ.", "Менеджер", "Подтверждена готовность либо срок.", List.of("invoice:1:readyCount")),
                        new Action("WAIT_FOR_DEPENDENCY", "Зафиксируйте срок повторной проверки.", "Менеджер", "Согласованный срок ещё не истёк.", List.of("invoice:1:readyCount"))),
                List.of("Обе позиции готовы либо подтверждённый срок ещё не истёк."));
    }
}
