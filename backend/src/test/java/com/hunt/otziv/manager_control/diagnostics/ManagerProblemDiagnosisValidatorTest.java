package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

class ManagerProblemDiagnosisValidatorTest {
    private final ManagerProblemDiagnosisValidator validator = new ManagerProblemDiagnosisValidator(new ObjectMapper());
    private final Fact status = new Fact("invoice:189:status", "invoice", "status", "COLLECTING");

    @Test
    void acceptsEvidenceBoundFactAndExplicitHypothesis() {
        Diagnosis result = validator.validate(diagnosis("invoice.status=COLLECTING", "invoice:189:status", "CHECK_SOURCE"), List.of(status));
        assertThat(result.confirmedFacts()).containsExactly(new Finding("invoice.status=COLLECTING", List.of("invoice:189:status")));
        assertThat(result.hypotheses()).hasSize(1);
    }

    @Test
    void legitimateReferenceCannotLaunderFalseStatus() {
        assertThatThrownBy(() -> validator.validate(diagnosis("invoice.status=REMINDER", "invoice:189:status", "CHECK_SOURCE"), List.of(status)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly quote");
    }

    @Test
    void rejectsFabricatedEvidenceAndExecutableAction() {
        assertThatThrownBy(() -> validator.validate(diagnosis("invoice.status=COLLECTING", "invoice:999:status", "CHECK_SOURCE"), List.of(status)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(diagnosis("invoice.status=COLLECTING", "invoice:189:status", "RUN_SQL"), List.of(status)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordedManagerClaimCannotBecomeConfirmedFact() {
        Fact comment = new Fact("card:1:comment", "card", "comment", "Все исправлено");
        assertThatThrownBy(() -> validator.validate(diagnosis("card.comment=Все исправлено", "card:1:comment", "CHECK_SOURCE"), List.of(comment)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncertainDeliveryForcesReceiptCheckAndNoDuplicate() {
        Fact delivery = new Fact("automation:3:delivery_status", "automation", "delivery_status", "UNCERTAIN");
        Diagnosis result = validator.validate(diagnosis("invoice.status=COLLECTING", "invoice:189:status", "CONTACT_RESPONSIBLE"), List.of(status, delivery));
        assertThat(result.actions()).extracting(Action::code).containsExactly("CHECK_DELIVERY", "ESCALATE");
        assertThat(result.actions().getFirst().text()).contains("повторная отправка запрещена");
    }

    @Test
    void missingSourceCoverageCannotBeHiddenByModel() {
        Fact missing = new Fact("source:coverage", "coverage", "live_context", "недоступен");
        Diagnosis result = validator.validate(diagnosis("invoice.status=COLLECTING", "invoice:189:status", "CHECK_SOURCE"), List.of(status, missing));
        assertThat(result.missingEvidence()).isNotEmpty();
    }

    @Test
    void redactionPreservesDatesAndIdsButRemovesSecretsAndContacts() {
        String clean = DiagnosticRedactor.redact("2026-10-22T11:22:33 invoice=189 api_key=superSecret bearer abc123 https://bank.test/pay?token=hidden mail@test.org +7 (999) 123-45-67");
        assertThat(clean).contains("2026-10-22T11:22:33", "invoice=189").doesNotContain("superSecret", "abc123", "bank.test", "mail@test.org", "999");
    }

    @Test
    void authorizationHeadersAndJsonSecretsAreRemovedCompletely() {
        assertThat(DiagnosticRedactor.redact("Authorization: Bearer topSecret\n{\"api_key\":\"jsonSecret\"}"))
                .doesNotContain("topSecret", "jsonSecret");
    }

    @Test
    void longRedactedQuoteCanBeAttributedExactlyWithoutLosingItsPrefixOrTruncationMarker() {
        String excerpt = DiagnosticRedactor.redact("Подробный текст сообщения. ".repeat(150));
        assertThat(excerpt).hasSize(2001).endsWith("…");
        Fact quote = new Fact("message:70:recorded_text", "recorded_chat_message", "recorded_text", excerpt);
        Fact coverage = new Fact("message:70:recorded_text:coverage", "history_coverage", "recorded_text_truncated", "Сообщение показано частично");
        String canonical = ManagerProblemDiagnosisValidator.canonical(quote);
        Diagnosis result = validator.validate(diagnosis(canonical, quote.reference(), "CHECK_SOURCE"), List.of(quote, coverage));
        assertThat(result.confirmedFacts().getFirst().text()).isEqualTo(canonical);
        assertThat(result.missingEvidence()).isNotEmpty();
        assertThatThrownBy(() -> validator.validate(diagnosis("x".repeat(4097), quote.reference(), "CHECK_SOURCE"), List.of(quote)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
    }

    private static String diagnosis(String text, String ref, String code) {
        return """
                {"summary":"Нужно уточнить состояние", "confirmedFacts":[{"text":"%s","evidenceRefs":["%s"]}],
                "hypotheses":[{"text":"Возможно, ожидается публикация; требуется проверка","evidenceRefs":["%s"]}],
                "missingEvidence":[],"actions":[{"code":"%s","text":"Проверить исходное состояние","owner":"Менеджер","verification":"Записать подтвержденное состояние","evidenceRefs":["%s"]}],
                "verification":["Проверить актуальный статус"]}
                """.formatted(text, ref, ref, code, ref);
    }
}
