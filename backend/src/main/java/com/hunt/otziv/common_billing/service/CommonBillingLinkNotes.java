package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonBillingAccountCompany;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the company editor tells a manager about a company of a common billing link: why its orders
 * are not being collected, and which orders stay on their own invoices. Plain texts for people; the
 * stored reconcile error keeps its technical prefix for the log and the database.
 */
final class CommonBillingLinkNotes {

    static final String FAILED_FINAL_PREFIX = "company_reconcile_failed_final:";
    static final String FAILED_PREFIX = "company_reconcile_failed:";
    static final String DEFERRED_FROZEN_ROUTE_PREFIX = "company_reconcile_deferred_frozen_route:";
    /** A long list would push the explanation out of the editor: the rest is counted. */
    static final int LISTED_ORDERS = 8;
    /** How a refusal of the application is stored: {@code 409 CONFLICT "text"}; the stored text may be cut at 512 characters. */
    private static final Pattern STATUS_WRAPPER = Pattern.compile("^\\d{3} [A-Z_ ]+ \"(.*?)\"?$", Pattern.DOTALL);

    private CommonBillingLinkNotes() {
    }

    /** {@code null} when the link works, or when a person excluded the company on purpose. */
    static String problem(CommonBillingAccountCompany link) {
        String error = link == null || link.getReconcileLastError() == null ? "" : link.getReconcileLastError().trim();
        if (error.isEmpty()) {
            return null;
        }
        if (!link.isEnabled()) {
            // Only the system writes the final prefix: it switched the link off after the last attempt.
            return error.startsWith(FAILED_FINAL_PREFIX)
                    ? "Система выключила связь: заказы компании не удалось собрать в общий счёт. Причина: "
                    + reason(error.substring(FAILED_FINAL_PREFIX.length())) + " Устраните причину и включите общий счёт снова."
                    : null;
        }
        if (!link.isReconcilePending()) {
            return null;
        }
        if (error.startsWith(DEFERRED_FROZEN_ROUTE_PREFIX)) {
            return "Заказы компании пока не собраны: у собирающегося общего счёта уже выбран получатель оплаты, "
                    + "поэтому его состав сейчас менять нельзя. Система повторит попытку сама.";
        }
        if (error.startsWith(FAILED_PREFIX)) {
            return "Заказы компании пока не собраны в общий счёт. Причина: " + reason(error.substring(FAILED_PREFIX.length()))
                    + " Система повторит попытку сама.";
        }
        return null;
    }

    /** {@code null} when every unfinished order of the company may enter the common invoice. */
    static String separateOrders(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return null;
        }
        boolean one = orderIds.size() == 1;
        int more = orderIds.size() - LISTED_ORDERS;
        String listed = orderIds.stream().limit(LISTED_ORDERS).map(id -> "№" + id).collect(Collectors.joining(", "));
        return (one ? "Отдельно остаётся заказ " : "Отдельно остаются заказы ") + listed + (more > 0 ? " и ещё " + more : "")
                + (one ? ": клиенту уже выставлен по нему отдельный счёт, и система не может сама убедиться, что оплаты по нему не было. "
                : ": клиенту уже выставлены по ним отдельные счета, и система не может сама убедиться, что оплат по ним не было. ")
                + (one ? "Такой заказ оплачивается по своему счёту и в общий счёт не входит. "
                : "Такие заказы оплачиваются по своим счетам и в общий счёт не входят. ")
                + "Если клиент точно не платил, владелец или администратор закрывает отдельный счёт кнопкой «Платежа нет» "
                + "в разделе «Банк», и после этого заказ возвращают в общий счёт кнопкой «Вернуть заказ в счёт».";
    }

    static String reason(String stored) {
        String text = stored == null ? "" : stored.trim();
        Matcher wrapped = STATUS_WRAPPER.matcher(text);
        if (wrapped.matches()) {
            text = wrapped.group(1).trim();
        }
        if (text.isEmpty()) {
            return "не записана.";
        }
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' ? text : text + ".";
    }
}
