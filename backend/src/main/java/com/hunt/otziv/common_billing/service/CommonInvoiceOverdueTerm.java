package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.dto.CommonInvoiceOverdueTermResponse;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A manager's extension of one common invoice's payment term: the automatic move to "Не оплачено"
 * then waits the autoresponder's overdue days plus these days, counted from the sending or, for a
 * bank paper invoice, from the delivery of its documents. The card shows the resulting date.
 */
@Service
public class CommonInvoiceOverdueTerm {
    static final int MAX_EXTENSION_DAYS = 60;
    private static final Set<CommonInvoiceStatus> OPEN_STATUSES = Set.of(CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY, CommonInvoiceStatus.INVOICED, CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CommonInvoiceRepository invoices;
    private final CommonInvoiceDeliveryService delivery;
    private final CommonInvoiceOverdueEscalation escalation;
    private final CommonInvoiceOverdueClock clock;

    public CommonInvoiceOverdueTerm(CommonInvoiceRepository invoices, CommonInvoiceDeliveryService delivery,
            CommonInvoiceOverdueEscalation escalation, CommonInvoiceOverdueClock clock) {
        this.invoices = invoices;
        this.delivery = delivery;
        this.escalation = escalation;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CommonInvoiceOverdueTermResponse view(long invoiceId) {
        CommonInvoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        delivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return response(invoice);
    }

    /** Sets (not adds) the extension; 0 removes it. The caller has checked the right to manage the invoice. */
    @Transactional
    public CommonInvoiceOverdueTermResponse extend(long invoiceId, Integer days, Principal actor) {
        if (days == null || days < 0 || days > MAX_EXTENSION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Отсрочка задаётся в днях: от 0 до " + MAX_EXTENSION_DAYS);
        }
        CommonInvoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счёт не найден"));
        delivery.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        if (!OPEN_STATUSES.contains(invoice.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Отсрочку можно задать только для неоплаченного открытого общего счёта");
        }
        if (invoice.getOverdueExtensionDays() != days) {
            invoice.setOverdueExtensionDays(days);
            invoice.setOverdueExtensionBy(actor == null || actor.getName() == null ? null
                    : actor.getName().substring(0, Math.min(150, actor.getName().length())));
            invoice.setOverdueExtensionAt(LocalDateTime.now());
            invoices.save(invoice);
        }
        return response(invoice);
    }

    private CommonInvoiceOverdueTermResponse response(CommonInvoice invoice) {
        int days = escalation.overdueDays();
        boolean paper = CommonInvoiceRouteState.isOwnerPaperInvoice(invoice);
        LocalDateTime start = clock.start(invoice).orElse(null);
        LocalDateTime move = escalation.automaticMoveNotBefore(invoice.getId(), LocalDateTime.now()).orElse(null);
        return new CommonInvoiceOverdueTermResponse(invoice.getId(), days, invoice.getOverdueExtensionDays(),
                invoice.getOverdueExtensionBy(), invoice.getOverdueExtensionAt(), start, paper, move,
                OPEN_STATUSES.contains(invoice.getStatus()), note(invoice, days, paper, start, move));
    }

    private static String note(CommonInvoice invoice, int days, boolean paper, LocalDateTime start, LocalDateTime move) {
        int extension = Math.max(0, invoice.getOverdueExtensionDays());
        String term = days + " дн." + (extension > 0 ? " + отсрочка " + extension + " дн." : "");
        if (!OPEN_STATUSES.contains(invoice.getStatus())) return "Счёт закрыт или уже переведён; срок не применяется.";
        if (start == null) {
            return paper
                    ? "Срок " + term + " начнётся после подтверждённой доставки бумажного счёта и акта."
                    : "Срок " + term + " начнётся после отправки счёта клиенту.";
        }
        String from = (paper ? "от доставки бумажного счёта " : "от отправки ") + DATE.format(start);
        if (move == null) {
            return "Срок " + term + " " + from + ". Автоматически в «Не оплачено» счёт сейчас не переводится: "
                    + "частичная оплата, сообщение клиента об оплате, ошибка счёта или выключен автоответчик.";
        }
        return "Срок " + term + " " + from + ". Если оплаты не будет, автоответчик переведёт счёт в «Не оплачено» не раньше "
                + DATE.format(move) + " — после уведомления менеджера.";
    }
}
