package com.hunt.otziv.manager_control.diagnostics;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.Fact;

class ManagerProblemInvoiceChronologyTest {
    @Test
    void recordedKopecksBecomeRublesWithoutTurningTheDifferenceIntoDebt() {
        var facts = facts("450000", "150000");
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(facts);
        assertThat(display(facts)).isEqualTo("Учтённая оплата: 1500 ₽; сумма счёта: 4500 ₽; "
                + "арифметическая разница: 3000 ₽. Суммы переведены из копеек делением на 100. Сама разница не подтверждает действующий долг.");
        assertThat(facts).contains(new Fact("invoice:1:paid_kopecks", "invoice", "paid_kopecks", "150000"));
    }

    @Test
    void decimalConversionPreservesKopecksAndAnOverpaymentDifferenceWithoutFloatingPoint() {
        var facts = facts("9007199254740993", "9007199254741094");
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(facts);
        assertThat(display(facts)).contains("90071992547410,94 ₽", "90071992547409,93 ₽", "-1,01 ₽");
    }

    @Test
    void unknownMissingNegativeOrMalformedAmountsNeverBecomeZeroOrAConfidentDisplay() {
        for (String value : List.of("неизвестно", "", "-1", "150000.5", "1e5", "private", "12345678901234567890")) {
            for (var facts : List.of(facts(value, "150000"), facts("450000", value))) {
                ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(facts);
                assertThat(display(facts)).isNull();
            }
        }
        var missing = facts("450000", "150000");
        missing.removeIf(f -> f.field().equals("paid_kopecks"));
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(missing);
        assertThat(display(missing)).isNull();
    }

    @Test
    void OrdinaryInvoiceDoesNotGainArchiveInstructionsAndExistingRefundEvidenceIsPreserved() {
        var ordinary = facts("450000", "150000");
        ordinary.removeIf(f -> f.field().equals("close_reason"));
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(ordinary);
        ManagerProblemEvidenceCollector.appendInvoiceArchiveChronology(ordinary);
        assertThat(display(ordinary)).contains("1500 ₽", "4500 ₽");
        assertThat(ordinary).noneMatch(f -> f.subject().equals("invoice_chronology"));
        var archived = facts("450000", "150000");
        var reversal = new Fact("invoice:1:payment_reversal", "invoice", "payment_reversal", "CONFIRMED");
        archived.add(reversal);
        ManagerProblemEvidenceCollector.appendInvoiceMonetaryDisplay(archived);
        ManagerProblemEvidenceCollector.appendInvoiceArchiveChronology(archived);
        assertThat(archived).contains(reversal);
    }

    private static List<Fact> facts(String amount, String paid) {
        return new ArrayList<>(List.of(
                new Fact("card:1:entity_type", "card", "entity_type", "COMMON_INVOICE"),
                new Fact("invoice:1:status", "invoice", "status", "PARTIALLY_PAID"),
                new Fact("invoice:1:closed_at", "invoice", "closed_at", "2026-07-30T21:31:26"),
                new Fact("invoice:1:close_reason", "invoice", "close_reason", "MANUAL_ARCHIVE"),
                new Fact("invoice:1:amount_kopecks", "invoice", "amount_kopecks", amount),
                new Fact("invoice:1:paid_kopecks", "invoice", "paid_kopecks", paid)));
    }

    private static String display(List<Fact> facts) {
        return facts.stream().filter(f -> f.subject().equals("invoice_money") && f.field().equals("money_display")).map(Fact::value).findFirst().orElse(null);
    }
}
