package com.hunt.otziv.paper_billing;

import com.hunt.otziv.payments.model.InvoicePaymentMode;

import static com.hunt.otziv.paper_billing.PaperBillingModels.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Order mutex -> document. Durable cancellation fences settlement; no network under SQL locks. */
@Service @RequiredArgsConstructor @Slf4j
public class PaperBillingCancellation {
    private final PaperBillingStore store;
    private final PaperBillingService service;
    private final PaperBillingTargets targets;
    private final PaperBillingBankClient bank;
    private final PaperBillingSettings settings;
    private final PaperBillingSettlement settlement;
    private final PaperBillingDelivery delivery;
    private final PlatformTransactionManager transactions;
    private static final Set<String> STEPS=Set.of("CHECK_BEFORE","DELETE_ACT","DELETE_INVOICE","CHECK_AFTER","FINALIZE");

    private TransactionTemplate tx() {
        var tx=new TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return tx;
    }
    private Target target(Map<String,Object> row) {return new Target((String)row.get("target_kind"),((Number)row.get("target_id")).longValue());}
    private Map<String,Object> locked(String id) {
        targets.lockDelivery(target(store.row(id)));
        return store.jdbc().queryForMap("SELECT * FROM paper_invoice_document WHERE id=? FOR UPDATE",id);
    }
    private long receipts(String id) {return store.jdbc().queryForObject("SELECT COUNT(*) FROM paper_invoice_bank_receipt WHERE document_id=?",Long.class,id);}
    public Document request(String id,CancelRequest request,Authentication actor) {
        if(!settings.isEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) throw problem("Отмена документов Точки выключена");
        if(request==null || !request.confirmed() || clean(request.reason()).isBlank() || clean(request.reason()).length()>500)
            throw problem("Укажите причину и подтвердите отмену счёта и акта");
        var before=service.authorized(id,actor);
        targets.authorizeCancellation(target(before),before.get("payment_link_id") instanceof Number n?n.longValue():null);
        return tx().execute(status -> {
            var row=locked(id);
            if(row.get("cancel_requested_at")!=null) {
                String savedMode = Objects.toString(row.get("cancel_replacement_mode"), "AUTO_ROUTING");
                String requestedMode = request.replacementMode() == null ? "AUTO_ROUTING" : request.replacementMode().name();
                if (!savedMode.equals(requestedMode) || request.replaceWithOrdinary() != (row.get("cancel_replacement_state") != null))
                    throw problem("Отмена уже начата с другим способом оплаты");
                return store.document(row);
            }
            if(row.get("paid_at")!=null || receipts(id)!=0) throw problem("По счёту уже есть поступление. Отмена запрещена; сначала нужна сверка");
            targets.requireUnpaidForCancellation(target(row));
            String stage=(String)row.get("stage");
            if(stage.contains("CREATING") || "PAID".equals(stage)
                    || "SENDING".equals(row.get("paper_reminder_state"))
                    || List.of("message_delivery","invoice_delivery","act_delivery").stream().anyMatch(c -> "SENDING".equals(row.get(c))))
                throw problem("Создание или отправка документа ещё не завершены. Обновите состояние перед отменой; неизвестный результат требует сверки");
            if(!Set.of("PENDING","INVOICE_CREATED","ACT_CREATED","FILES_READY","WAITING_PAYMENT","NEEDS_REVIEW").contains(stage))
                throw problem("Состояние банковского комплекта требует сверки перед отменой");
            store.jdbc().update("""
                UPDATE paper_invoice_document SET cancel_requested_at=NOW(6),cancel_requested_by=?,cancel_reason=?,cancel_manager_ids_json=?,
                cancel_step='CHECK_BEFORE',stage='CANCEL_REQUESTED',cancel_scan_date=DATE_SUB(DATE(created_at),INTERVAL 1 DAY),
                cancel_next_attempt_at=NOW(6),cancel_replacement_state=?,error_text=NULL,updated_at=NOW(6) WHERE id=?
                """,actor.getName(),clean(request.reason()),targets.cancellationManagers(target(row)).toString(),request.replaceWithOrdinary()?"PENDING":null,id);
            if (request.replacementMode() != null) {
                store.jdbc().update("UPDATE paper_invoice_document SET cancel_replacement_mode=? WHERE id=?", request.replacementMode().name(), id);
            }
            targets.holdAutomation(target(row),true);
            return store.document(store.row(id));
        });
    }
    @Scheduled(fixedDelay=15000,initialDelay=25000)
    public void tick() {
        if(!settings.isEnabled() || !settings.isBankOperationsEnabled() || !bank.configured()) return;
        for(String id:store.jdbc().queryForList("""
            SELECT id FROM paper_invoice_document WHERE cancel_step IN ('CHECK_BEFORE','DELETE_ACT','DELETE_INVOICE','CHECK_AFTER','FINALIZE')
            AND cancel_next_attempt_at<=NOW(6) AND (cancel_token IS NULL OR cancel_attempt_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE))
            ORDER BY cancel_next_attempt_at,id LIMIT 20
            """,String.class)) {
            try {process(id);} catch(Exception failure) {log.warn("Paper cancellation {} failed ({})",id,failure.getClass().getSimpleName());}
        }
        if(settings.isDeliveryEnabled()) for(String id:store.jdbc().queryForList("""
            SELECT id FROM paper_invoice_document WHERE cancelled_at IS NOT NULL AND cancel_message_delivery='PENDING'
            AND cancel_next_attempt_at<=NOW(6) ORDER BY cancelled_at LIMIT 20
            """,String.class)) sendCancellation(id);
        store.jdbc().update("UPDATE paper_invoice_document SET cancel_message_delivery='UNKNOWN' WHERE cancel_message_delivery='SENDING' AND cancel_attempt_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE)");
        for(String id:store.jdbc().queryForList("""
            SELECT id FROM paper_invoice_document WHERE cancelled_at IS NOT NULL AND cancel_replacement_state='PENDING'
            AND cancel_message_delivery IN ('SENT','SKIPPED') AND cancel_next_attempt_at<=NOW(6)
            ORDER BY cancel_next_attempt_at,id LIMIT 20
            """,String.class)) {
            try {resumeOrdinaryInvoice(id);} catch(Exception failure) {
                store.jdbc().update("UPDATE paper_invoice_document SET cancel_replacement_error='Не удалось подготовить обычный счёт. Автоответчик остаётся остановлен; попытка будет повторена',cancel_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE) WHERE id=? AND cancel_replacement_state='PENDING'",id);
                log.warn("Paper cancellation handoff {} failed ({})",id,failure.getClass().getSimpleName());
            }
        }
    }
    /** Same order mutex as receipts. Handoff and the durable ordinary-message command commit together. */
    public void resumeOrdinaryInvoice(String id) {
        tx().executeWithoutResult(status -> {
            var row=locked(id);
            if(row.get("cancelled_at")==null || !"PENDING".equals(row.get("cancel_replacement_state"))
                    || !Set.of("SENT","SKIPPED").contains(Objects.toString(row.get("cancel_message_delivery"),""))) return;
            if(receipts(id)!=0 || store.hasUnresolvedReceipt(target(row),null) || store.target(target(row)).isPresent()) {
                targets.holdForPaymentReview(target(row));
                review(id,"Обычный счёт не выставлен: есть поступление или другой банковский комплект. Нужна ручная сверка");return;
            }
            try {targets.requireUnpaidForCancellation(target(row));}
            catch(org.springframework.web.server.ResponseStatusException paid) {
                targets.holdForPaymentReview(target(row));review(id,"Заказ уже оплачен или изменился после отмены. Нужна сверка до обычного счёта");return;
            }
            String mode = (String) row.get("cancel_replacement_mode");
            if (mode == null) targets.resumeOrdinaryInvoice(target(row));
            else targets.resumeOrdinaryInvoice(target(row), InvoicePaymentMode.valueOf(mode));
            store.jdbc().update("UPDATE paper_invoice_document SET cancel_replacement_state='QUEUED',cancel_replacement_error=NULL,cancel_replacement_queued_at=NOW(6),updated_at=NOW(6) WHERE id=?",id);
            store.event(id,"ORDINARY_QUEUED","Счёт № "+row.get("number")+" и акт отменены. Обычный счёт передан в штатную очередь: получатель выбирается заново по доступным резервам. Подтверждение отправки отслеживается автоответчиком.");
        });
    }
    public void process(String id) {
        String token=UUID.randomUUID().toString();
        var row=tx().execute(status -> {
            var current=locked(id);
            if(!STEPS.contains(Objects.toString(current.get("cancel_step"),"")) || current.get("cancelled_at")!=null) return null;
            if(receipts(id)!=0 || current.get("paid_at")!=null) {review(id,"Обнаружено поступление во время отмены. Нужна ручная сверка");return null;}
            try {targets.requireUnpaidForCancellation(target(current));}
            catch(org.springframework.web.server.ResponseStatusException paid) {review(id,"Заказ изменился или уже оплачен. Отмена остановлена; нужна сверка");return null;}
            int claimed=store.jdbc().update("UPDATE paper_invoice_document SET cancel_token=?,cancel_attempt_at=NOW(6),cancel_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 15 SECOND) WHERE id=? AND (cancel_token IS NULL OR cancel_attempt_at<DATE_SUB(NOW(6),INTERVAL 5 MINUTE))",token,id);
            return claimed==1?store.row(id):null;
        });
        if(row==null) return;
        try {
            String step=(String)row.get("cancel_step");
            switch(step) {
                case "CHECK_BEFORE" -> {
                    if(row.get("bank_invoice_id")==null || scan(row,token)) advance(id,token,"DELETE_ACT",false);
                }
                case "DELETE_ACT" -> {
                    if(row.get("bank_act_id")!=null) bank.deleteDocument(row,true);
                    acknowledgeDelete(id,token,true,row.get("bank_act_id")!=null);
                }
                case "DELETE_INVOICE" -> {
                    if(row.get("bank_invoice_id")!=null) {
                        // Recheck immediately before deleting the invoice, after act deletion.
                        try {requireUnpaid(row);} catch(org.springframework.web.client.HttpClientErrorException.NotFound absent) {
                            // An earlier exact DELETE may have completed before the process stopped.
                        }
                        bank.deleteDocument(row,false);
                    }
                    acknowledgeDelete(id,token,false,row.get("bank_invoice_id")!=null);
                }
                case "CHECK_AFTER" -> {if(row.get("bank_invoice_id")==null || scan(row,token)) advance(id,token,"FINALIZE",false);}
                case "FINALIZE" -> finish(id,token);
                default -> throw new IllegalStateException();
            }
        } catch(PaymentFound payment) {
            tx().executeWithoutResult(status -> {locked(id);review(id,payment.getMessage());});
        } catch(Exception failure) {
            store.jdbc().update("UPDATE paper_invoice_document SET error_text='Не удалось подтвердить отмену в банке. Связи сохранены; проверка будет повторена',cancel_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE) WHERE id=? AND cancel_token=?",id,token);
        } finally {store.jdbc().update("UPDATE paper_invoice_document SET cancel_token=NULL WHERE id=? AND cancel_token=?",id,token);}
    }
    private void requireUnpaid(Map<String,Object> row) {
        String state;
        try {state=bank.invoicePaymentStatus(row);}
        catch(org.springframework.web.client.HttpClientErrorException.NotFound alreadyAbsent) {return;}
        if("payment_paid".equals(state)) throw new PaymentFound("Банк сообщает об оплате счёта. Отмена остановлена; нужна сверка поступления");
        if(!Set.of("payment_waiting","payment_expired").contains(state)) throw problem("Неизвестный статус оплаты в банке");
    }
    /** Persist each requested statement so an asynchronous bank response is polled, not recreated. */
    private boolean scan(Map<String,Object> row,String token) {
        String id=(String)row.get("id");
        if("CHECK_BEFORE".equals(row.get("cancel_step"))) requireUnpaid(row);
        LocalDate today=LocalDate.now(ZoneId.of("Europe/Moscow"));
        LocalDate start=date(row.get("cancel_scan_date"));if(start.isAfter(today)) return true;
        String statement=(String)row.get("cancel_statement_id");
        if(statement==null) {
            LocalDate end=start.plusDays(6).isAfter(today)?today:start.plusDays(6);
            var result=bank.call("/open-banking/v1.0/statements",HttpMethod.POST,Map.of("Data",Map.of("Statement",Map.of(
                    "accountId",row.get("supplier_account"),"startDateTime",start.toString(),"endDateTime",end.toString()))));
            var data=result.path("Data").path("Statement");if(data.isArray()) data=data.path(0);
            statement=data.path("statementId").asText();
            if(!statement.matches("[A-Za-z0-9-]{1,96}")) throw problem("Банк не подтвердил свежую выписку");
            store.jdbc().update("UPDATE paper_invoice_document SET cancel_statement_id=?,cancel_window_end=? WHERE id=? AND cancel_token=?",statement,end,id,token);
            return false;
        }
        var result=bank.call("/open-banking/v1.0/accounts/"+row.get("supplier_account")+"/statements/"+statement,HttpMethod.GET,null);
        if(result.path("Meta").path("totalPages").asInt(1)>1) throw problem("Отмена ожидает полной сверки многостраничной выписки");
        boolean ready=false;
        for(var s:result.path("Data").path("Statement")) {
            if(!statement.equals(s.path("statementId").asText()) || !row.get("supplier_account").equals(s.path("accountId").asText())) throw problem("Банк вернул другую выписку");
            if(!"Ready".equals(s.path("status").asText())) continue;ready=true;
            var number=java.util.regex.Pattern.compile("(?<![A-Z0-9-])"+java.util.regex.Pattern.quote((String)row.get("number"))+"(?![A-Z0-9-])",java.util.regex.Pattern.CASE_INSENSITIVE);
            for(var transfer:s.path("Transaction")) if("Credit".equals(transfer.path("creditDebitIndicator").asText()) && number.matcher(transfer.path("description").asText()).find()) {
                var receipt=PaperBillingReconciliation.fromStatement(transfer,(String)row.get("customer_code"),(String)row.get("supplier_account"));
                if(receipt!=null) settlement.accept(receipt);
                throw new PaymentFound("В свежей выписке найден перевод по счёту. Отмена остановлена; нужна ручная сверка");
            }
        }
        if(!ready) return false;
        LocalDate end=date(row.get("cancel_window_end"));
        store.jdbc().update("UPDATE paper_invoice_document SET cancel_scan_date=?,cancel_statement_id=NULL,cancel_window_end=NULL WHERE id=? AND cancel_token=?",end.plusDays(1),id,token);
        return !end.isBefore(today);
    }
    private static LocalDate date(Object value) {return value instanceof java.sql.Date d?d.toLocalDate():(LocalDate)value;}
    private void advance(String id,String token,String step,boolean resetScan) {
        store.jdbc().update("""
            UPDATE paper_invoice_document SET cancel_step=?,cancel_statement_id=NULL,cancel_window_end=NULL,
            cancel_scan_date=IF(?,DATE_SUB(DATE(created_at),INTERVAL 1 DAY),cancel_scan_date),error_text=NULL
            WHERE id=? AND cancel_token=? AND cancel_step<>'REVIEW'
            """,step,resetScan,id,token);
    }
    private void acknowledgeDelete(String id,String token,boolean act,boolean existed) {
        tx().executeWithoutResult(status -> {
            var row=locked(id);
            if(!token.equals(row.get("cancel_token"))) return;
            // A late transfer must not erase the confirmed deletion evidence.
            if(existed) store.jdbc().update("UPDATE paper_invoice_document SET "+(act?"act_deleted_at":"invoice_deleted_at")+"=NOW(6) WHERE id=?",id);
            if(!"REVIEW".equals(row.get("cancel_step"))) advance(id,token,act?"DELETE_INVOICE":"CHECK_AFTER",!act);
        });
    }
    private void finish(String id,String token) {
        tx().executeWithoutResult(status -> {
            var row=locked(id);
            if(!token.equals(row.get("cancel_token")) || !"FINALIZE".equals(row.get("cancel_step"))) return;
            if(receipts(id)!=0 || row.get("paid_at")!=null) {review(id,"При завершении отмены обнаружено поступление; нужна сверка");return;}
            if((row.get("bank_invoice_id")!=null && row.get("invoice_deleted_at")==null) || (row.get("bank_act_id")!=null && row.get("act_deleted_at")==null)) throw problem("Удаление обоих документов ещё не подтверждено");
            targets.cancelled(target(row),id,row.get("payment_link_id") instanceof Number n?n.longValue():null);
            boolean neverSent=List.of("message_delivery","invoice_delivery","act_delivery").stream().allMatch(c -> "PENDING".equals(row.get(c)));
            store.jdbc().update("UPDATE paper_invoice_document SET stage='CANCELLED',cancelled_at=NOW(6),cancel_step='DONE',cancel_message_delivery=?,error_text=NULL,updated_at=NOW(6) WHERE id=?",neverSent?"SKIPPED":"PENDING",id);
            store.event(id,"CANCELLED","Счёт № "+row.get("number")+" и акт отменены в Точке.\nПричина: "+row.get("cancel_reason")+"\n"
                    +("PENDING".equals(row.get("cancel_replacement_state"))?"После уведомления об отмене будет подготовлен обычный счёт по текущим резервам.":"Автоответчик остановлен.")+" Поздние переводы требуют ручной сверки.");
        });
    }
    private void review(String id,String reason) {
        store.jdbc().update("UPDATE paper_invoice_document SET stage=IF(cancelled_at IS NULL,'CANCEL_REVIEW',stage),cancel_step=IF(cancelled_at IS NULL,'REVIEW',cancel_step),cancel_replacement_state=IF(cancel_replacement_state IS NULL,NULL,'REVIEW'),cancel_replacement_error=IF(cancel_replacement_state IS NULL,NULL,?),error_text=?,updated_at=NOW(6) WHERE id=?",reason,reason,id);
        store.event(id,"PAYMENT_REVIEW","Отмена счёта № "+store.row(id).get("number")+" требует внимания.\n"+reason);
    }
    public void sendCancellation(String id) {
        int claimed=store.jdbc().update("UPDATE paper_invoice_document SET cancel_message_delivery='SENDING',cancel_attempt_at=NOW(6) WHERE id=? AND cancelled_at IS NOT NULL AND cancel_message_delivery='PENDING'",id);
        if(claimed==0) return;
        try {
            var result=delivery.sendCancellation(store.row(id));
            String state=result.sent() && result.messageId()!=null?"SENT":com.hunt.otziv.client_messages.api.ClientMessageDelivery.isKnownUnsent(result)?"PENDING":"UNKNOWN";
            store.jdbc().update("UPDATE paper_invoice_document SET cancel_message_delivery=?,cancel_message_id=?,cancel_next_attempt_at=DATE_ADD(NOW(6),INTERVAL 1 MINUTE) WHERE id=? AND cancel_message_delivery='SENDING'",state,result.messageId(),id);
        } catch(Exception failure) {store.jdbc().update("UPDATE paper_invoice_document SET cancel_message_delivery='UNKNOWN' WHERE id=? AND cancel_message_delivery='SENDING'",id);}
    }
    private static class PaymentFound extends RuntimeException {PaymentFound(String message) {super(message);}}
}
