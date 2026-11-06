package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices.Approaching;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Manager-group text before the autoresponder moves unpaid common invoices to "Не оплачено". */
final class CommonInvoiceOverdueNoticeText {

    static final int MAX_LISTED = 15;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private CommonInvoiceOverdueNoticeText() {
    }

    static String manager(List<Approaching> invoices) {
        StringBuilder text = new StringBuilder()
                .append("⏳ <b>Общие счета скоро перейдут в «Не оплачено»</b>\n\n")
                .append("Клиенты давно не оплачивают эти общие счета. Если оплаты не будет, автоответчик ")
                .append("переведёт их в «Не оплачено» и создаст плохие задачи:\n\n");
        int listed = 0;
        for (Approaching invoice : invoices) {
            if (listed == MAX_LISTED) {
                break;
            }
            text.append("• <b>").append(escape(invoice.accountName())).append("</b> — ")
                    .append(rubles(invoice.remainingKopecks()))
                    .append(", выставлен ").append(DATE.format(invoice.sentAt()))
                    .append(" (").append(invoice.unpaidDays()).append(" дн. без оплаты)")
                    .append(", перевод не раньше ").append(DATE.format(invoice.moveNotBefore()))
                    .append('\n');
            listed++;
        }
        if (invoices.size() > listed) {
            text.append("• и ещё ").append(invoices.size() - listed).append('\n');
        }
        return text.append("\nСвяжитесь с клиентом. Если оплата уже пришла, отметьте её в счёте — тогда перевода не будет. ")
                .append("Эти счета также в «Контроле».")
                .toString();
    }

    static String rubles(long kopecks) {
        long rubles = kopecks / 100;
        long rest = Math.abs(kopecks % 100);
        String whole = String.format(Locale.ROOT, "%,d", rubles).replace(',', ' ');
        return (rest == 0 ? whole : whole + "," + String.format(Locale.ROOT, "%02d", rest)) + " ₽";
    }

    private static String escape(String value) {
        String safe = value == null || value.isBlank() ? "Общий счёт" : value.trim();
        return safe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
