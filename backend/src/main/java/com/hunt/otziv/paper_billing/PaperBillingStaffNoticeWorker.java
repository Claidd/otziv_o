package com.hunt.otziv.paper_billing;

import com.hunt.otziv.client_messages.api.PaperInvoiceAutomation;
import com.hunt.otziv.common_billing.api.BankPaperInvoices;
import com.hunt.otziv.p_products.api.PaperInvoiceOrderData;
import com.hunt.otziv.t_telegrambot.api.TelegramNotifications;
import com.hunt.otziv.u_users.api.PaymentNoticeRecipients;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Paid document is the committed durable intent. Network calls run after short fenced claims. */
@Service @RequiredArgsConstructor @Slf4j
public class PaperBillingStaffNoticeWorker {
    private final PaperBillingSettings settings;
    private final PaperBillingStore documents;
    private final PaperBillingNoticeStore notices;
    private final PaperInvoiceOrderData orders;
    private final BankPaperInvoices common;
    private final PaperInvoiceAutomation automation;
    private final PaymentNoticeRecipients recipients;
    private final TelegramNotifications telegram;
    private final PaperBillingBankClient bank;
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public void tick() {
        if(!settings.isEnabled() || !settings.isStaffNotificationsEnabled()) return;
        for(String id:notices.unprepared()) {
            try {prepare(id);} catch(Exception failure) {notices.postponePreparation(id);log.warn("Paper staff notice preparation {} failed ({})",id,failure.getClass().getSimpleName());}
        }
        for(long id:notices.due()) {
            try {deliver(id);} catch(Exception failure) {log.warn("Paper staff notice {} failed ({})",id,failure.getClass().getSimpleName());}
        }
    }
    public void prepare(String id) {
        var row=documents.row(id);if(row.get("paid_at")==null) return;
        long target=((Number)row.get("target_id")).longValue();
        List<Long> managers;List<Long> orderIds;String title;
        if(row.get("target_kind").equals("ORDER")) {
            var source=orders.staffContext(target);managers=source.managerId()==null?List.of():List.of(source.managerId());orderIds=List.of(target);
            title=source.company()+(source.filial()==null || source.filial().isBlank()?"":" — "+source.filial());
        } else {
            var source=common.staffContext(target);managers=source.managerIds();orderIds=source.orderIds();title=source.title();
        }
        // Also repairs a payment applied by an older process after this version was installed.
        orderIds.forEach(automation::synchronizeOrder);
        String amount=PaperBillingDelivery.amountText(((Number)row.get("amount_kopecks")).longValue());
        String body="Оплата по банковскому счёту получена.\n\n"+title+"\n"
                +(row.get("target_kind").equals("ORDER")?"Заказ №":"Общий счёт №")+target
                +"\nСчёт № "+row.get("number")+"\nСумма: "+amount+"\nСтатус: Оплачено.";
        notices.prepare(id,recipients.forManagers(managers),body);
    }
    public void deliver(long id) {
        if(!settings.isEnabled() || !settings.isStaffNotificationsEnabled()) return;
        var row=notices.row(id);
        if(!"PENDING".equals(row.get("state"))) return;
        Long manager=row.get("manager_id") instanceof Number n?n.longValue():null;
        Long current=recipients.currentChat(((Number)row.get("recipient_user_id")).longValue(),manager);
        if(current==null) {notices.postpone(id,"Не привязан активный Telegram-чат получателя");return;}
        if(row.get("chat_id") instanceof Number pinned && pinned.longValue()!=current) {notices.postpone(id,"Чат получателя изменился; нужна сверка уведомления");return;}
        if(row.get("chat_id")==null && !notices.resolveChat(id,current)) return;
        boolean fixture=bank.fixture();
        if(!fixture && !telegram.canSendTexts()) {notices.postpone(id,"Отправка Telegram выключена");return;}
        String token=UUID.randomUUID().toString();var claim=notices.claim(id,token);if(claim.isEmpty()) return;
        row=claim.get();
        try {
            String receipt;
            if(fixture) receipt=bank.fixtureCall("/delivery",Map.of("operationId","paper-staff:"+id,"groupId","STAFF:"+row.get("chat_id"),"caption",row.get("body"))).path("messageId").asText();
            else receipt=telegram.sendTextOnceMessageId(((Number)row.get("chat_id")).longValue(),(String)row.get("body")).map(Object::toString).orElse("");
            notices.finish(id,token,receipt.isBlank()?"UNKNOWN":"SENT",receipt.isBlank()?null:receipt,receipt.isBlank()?"Telegram не подтвердил результат; нужна сверка":null);
        } catch(Exception failure) {notices.finish(id,token,"UNKNOWN",null,"Результат отправки неизвестен; нужна сверка");}
    }
}
