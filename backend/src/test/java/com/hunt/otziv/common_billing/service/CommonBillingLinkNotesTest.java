package com.hunt.otziv.common_billing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CommonBillingLinkNotesTest {

    private static final String REFUSAL = "409 CONFLICT \"Заказ #16655 нельзя включить в общий счет: отдельный способ оплаты "
            + "(ссылка #8899, статус WAITING_MANUAL_PAYMENT) не закрыт безопасно. Проверьте T-Bank либо поступление по ручным "
            + "реквизитам, затем явно отмените/закройте отдельный платеж.\"";

    @Test
    void linkSwitchedOffByTheSystemTellsItsReasonWithoutTechnicalWrapping() {
        String problem = CommonBillingLinkNotes.problem(link(false, false, "company_reconcile_failed_final: " + REFUSAL));

        assertTrue(problem.startsWith("Система выключила связь"));
        assertTrue(problem.contains("Причина: Заказ #16655 нельзя включить в общий счет"));
        assertTrue(problem.endsWith("отдельный платеж. Устраните причину и включите общий счёт снова."));
        assertFalse(problem.contains("409"));
        assertFalse(problem.contains("company_reconcile"));
        assertFalse(problem.contains("\""));
    }

    @Test
    void failingCollectionOfAnEnabledLinkSaysThatItWillBeRetried() {
        String problem = CommonBillingLinkNotes.problem(link(true, true, "company_reconcile_failed: IllegalStateException"));

        assertEquals("Заказы компании пока не собраны в общий счёт. Причина: IllegalStateException. Система повторит попытку сама.", problem);
        assertTrue(CommonBillingLinkNotes.problem(link(true, true, "company_reconcile_deferred_frozen_route: invoices=[10]"))
                .contains("уже выбран получатель оплаты"));
    }

    @Test
    void workingLinkAndCompanyExcludedByAPersonNeedNoExplanation() {
        assertNull(CommonBillingLinkNotes.problem(null));
        assertNull(CommonBillingLinkNotes.problem(link(true, false, null)));
        assertNull(CommonBillingLinkNotes.problem(link(true, false, "company_reconcile_failed: устарело")));
        assertNull(CommonBillingLinkNotes.problem(link(false, false, "company_reconcile_failed: устарело")));
        assertNull(CommonBillingLinkNotes.problem(link(false, false, "  ")));
    }

    @Test
    void storedReasonCutAtTheColumnLimitStillReadsAsASentence() {
        assertEquals("Заказ #1 нельзя включить.", CommonBillingLinkNotes.reason(" 409 CONFLICT \"Заказ #1 нельзя включить"));
        assertEquals("Заказ #1 нельзя включить!", CommonBillingLinkNotes.reason("409 CONFLICT \"Заказ #1 нельзя включить!\""));
        assertEquals("исчерпаны попытки.", CommonBillingLinkNotes.reason(" исчерпаны попытки"));
        assertEquals("не записана.", CommonBillingLinkNotes.reason("  "));
    }

    @Test
    void separateOrdersAreNamedWithWhatToDoAndALongListIsCounted() {
        assertNull(CommonBillingLinkNotes.separateOrders(List.of()));
        assertNull(CommonBillingLinkNotes.separateOrders(null));

        String one = CommonBillingLinkNotes.separateOrders(List.of(16655L));
        assertTrue(one.startsWith("Отдельно остаётся заказ №16655: клиенту уже выставлен по нему отдельный счёт"));
        assertTrue(one.contains("«Платежа нет»") && one.contains("«Вернуть заказ в счёт»"));

        String three = CommonBillingLinkNotes.separateOrders(List.of(16654L, 16655L, 27684L));
        assertTrue(three.startsWith("Отдельно остаются заказы №16654, №16655, №27684: клиенту уже выставлены по ним отдельные счета, "
                + "и система не может сама убедиться, что оплат по ним не было."));

        String many = CommonBillingLinkNotes.separateOrders(LongStream.rangeClosed(1, 11).boxed().toList());
        assertTrue(many.contains("№8 и ещё 3:"));
        assertFalse(many.contains("№9"));
    }

    private static CommonBillingAccountCompany link(boolean enabled, boolean pending, String error) {
        CommonBillingAccountCompany link = new CommonBillingAccountCompany();
        link.setEnabled(enabled);
        link.setReconcilePending(pending);
        link.setReconcileLastError(error);
        return link;
    }
}
