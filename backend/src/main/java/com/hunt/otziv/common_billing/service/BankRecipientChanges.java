package com.hunt.otziv.common_billing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.common_billing.dto.*;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoicePaymentRef;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.contractor_payments.api.PaymentReviewTargetAuthorization;
import com.hunt.otziv.payments.dto.*;
import com.hunt.otziv.payments.api.OrderBankRecipientChange;
import com.hunt.otziv.payments.api.OrderRecipientRoutes;
import com.hunt.otziv.payments.api.OrderRecipientRoutes.SourceChanged;
import com.hunt.otziv.payments.api.UnpaidBankObservation;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.hunt.otziv.payments.api.UnpaidBankObservation.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BankRecipientChanges implements OrderBankRecipientChange {
    private static final String WAIT = "Запрос принят. Ожидаем ответ банка. Система продолжит смену получателя автоматически.";
    private static final String TBANK_WAIT = "Запрос принят. Ссылка Т-Банка ещё действует. После её закрытия без оплаты система сама выдаст новые реквизиты.";
    private static final String TOCHKA_WAIT = "Запрос принят. Ссылка Точки ещё действует. После её истечения система сама выдаст новые реквизиты, если клиент не оплатит.";
    private record Job(long id, Source source, Actor actor, String closedStatus, boolean capacityChecked, String action) {}
    private record Claim(Job job, String lease) {}
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DeferredUserAuthority authority;
    private final OrderRecipientRoutes orderRoutes;
    private final CommonInvoicePaymentRouteWorkflow invoices;
    private final CommonInvoicePaymentRefRepository refs;
    private final PaymentReviewTargetAuthorization invoiceAccess;
    private final UnpaidBankObservation bank;
    private final CommonInvoiceSettlementService settlement;
    private final com.hunt.otziv.security.credentials.CredentialCipher cipher;
    private final org.springframework.beans.factory.ObjectProvider<com.hunt.otziv.common_billing.api.BankPaperInvoices.DocumentPreparation> paperDocuments;

    @Override
    public PaymentRouteChangeResponse order(long id, PaymentRouteChangeRequest request, Authentication actor) {
        if (!request.confirmedUnpaid() || request.target() != PaymentRouteChangeTarget.EMPLOYEE_REQUISITES)
            throw conflict("Подтвердите смену получателя неоплаченного заказа");
        Long job = tx.execute(status -> {
            var source = orderRoutes.lockedSource(id, request.expectedPaymentLinkId(), actor);
            return source == null ? null : enqueue(source, actor, "RECIPIENT");
        });
        if (job == null) return orderRoutes.changeNow(id, request, actor);
        var result = process(job);
        if (result instanceof PaymentRouteChangeResponse response) return response;
        if ("DONE".equals(jdbc.queryForObject("SELECT state FROM bank_recipient_change WHERE id=?",String.class,job))) {
            Long newLink = jdbc.queryForObject("SELECT replacement_id FROM bank_recipient_change WHERE id=?",Long.class,job);
            return new PaymentRouteChangeResponse(request.expectedPaymentLinkId(),newLink,request.target(),true,null);
        }
        throw statusOf(job);
    }

    public CommonInvoiceDetailsResponse invoice(long id, CommonInvoicePaymentRouteChangeRequest request, Authentication actor) {
        if (!request.confirmedUnpaid() || request.target() != CommonInvoicePaymentRouteChangeTarget.EMPLOYEE_REQUISITES)
            throw conflict("Подтвердите смену получателя общего счёта");
        Long job = tx.execute(status -> {
            var invoice = lockedInvoice(id);
            var history = refs.findByInvoiceIdForUpdate(id);
            if (!Objects.equals(request.expectedPaymentEvidenceToken(), invoices.commonInvoiceRouteChangeToken(invoice,history)))
                throw conflict("Состояние общего счёта изменилось. Обновите карточку");
            var source = invoiceSource(invoice, history);
            return source == null ? null : enqueue(source, actor, "RECIPIENT");
        });
        if (job == null) return invoices.changeCommonInvoicePaymentRoute(id, request, actor, true);
        process(job);
        var state = jdbc.queryForObject("SELECT state FROM bank_recipient_change WHERE id=?", String.class, job);
        if ("DONE".equals(state)) return tx.execute(status -> invoices.invoiceAfterOrderPrelude(id));
        throw statusOf(job);
    }

    /** Returns false when the invoice has no bank operation and can switch in the caller. */
    public boolean paper(long invoiceId, Authentication actor, String documentRequest) {
        Long job = tx.execute(status -> {
            var invoice = lockedInvoice(invoiceId);
            var source = invoiceSource(invoice, refs.findByInvoiceIdForUpdate(invoiceId));
            if (source == null) return null;
            long jobId = enqueue(source, actor, "PAPER");
            if (documentRequest != null) {
                var row = jdbc.queryForMap("SELECT actor_json,paper_request FROM bank_recipient_change WHERE id=? FOR UPDATE",jobId);
                if (!decode(row.get("actor_json").toString(), Actor.class).equals(authority.capture(actor)))
                    throw conflict("Запрос по этой ссылке уже оформил другой пользователь");
                if (row.get("paper_request") != null && !documentRequest.equals(cipher.decrypt(row.get("paper_request").toString())))
                    throw conflict("Для этой ссылки уже ожидает выпуска другой комплект документов");
                if (row.get("paper_request") == null) {
                    if (!cipher.isEnabled()) throw conflict("Сохранить реквизиты для отложенного выпуска сейчас невозможно");
                    jdbc.update("UPDATE bank_recipient_change SET paper_request=? WHERE id=?",cipher.encrypt(documentRequest),jobId);
                }
            }
            return jobId;
        });
        if (job == null) return false;
        process(job);
        if ("DONE".equals(jdbc.queryForObject("SELECT state FROM bank_recipient_change WHERE id=?", String.class, job))) return true;
        throw statusOf(job);
    }

    private CommonInvoice lockedInvoice(long id) {
        var invoice = invoices.lockedInvoice(id).orElseThrow(() -> conflict("Общий счёт не найден"));
        invoiceAccess.requireCanManageCommonInvoice(id);
        invoices.ensureCommonInvoiceVisibleForCurrentUser(invoice);
        return invoice;
    }

    private Source invoiceSource(CommonInvoice invoice, List<CommonInvoicePaymentRef> history) {
        var ref = invoices.bankRecipientRef(invoice, history);
        if (ref == null) return null;
        return new Source("INVOICE", invoice.getId(), ref.getId(), ref.getPaymentProfileId(), invoices.normalizedPaymentProvider(ref),
                invoices.providerPaymentId(ref), invoices.providerOrderId(ref), invoices.providerMerchantId(ref),
                ref.getAmountKopecks(), ref.getProviderPaymentMode(), ref.getProviderTestMode(),
                invoices.bankRecipientRouteVersion(invoice));
    }

    private long enqueue(Source source, Authentication actor, String action) {
        if (source.merchant() == null || source.merchant().isBlank() || source.order() == null || source.order().isBlank())
            throw conflict("Банк ещё создаёт ссылку. Обновите карточку после её создания");
        jdbc.update("""
                INSERT INTO bank_recipient_change(target_kind,target_id,source_id,snapshot_json,actor_json,message,requested_action)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,source.kind(), source.targetId(),source.sourceId(),encode(source),encode(authority.capture(actor)),WAIT,action);
        var job = jdbc.queryForMap("SELECT id,snapshot_json,state,requested_action FROM bank_recipient_change WHERE target_kind=? AND target_id=? AND source_id=? FOR UPDATE",
                source.kind(),source.targetId(),source.sourceId());
        Source saved = decode(job.get("snapshot_json").toString(),Source.class);
        if (!saved.equals(source) && !(saved.operation().isBlank() && withOperation(saved,source.operation(),source.modes()).equals(source)))
            throw conflict("Банковская ссылка изменилась");
        long id = ((Number)job.get("id")).longValue();
        if (!action.equals(job.get("requested_action")) && !"STOPPED".equals(job.get("state")))
            throw conflict("Для этой ссылки уже запрошен другой способ оплаты");
        if ("STOPPED".equals(job.get("state"))) {
            jdbc.update("UPDATE bank_recipient_change SET state='WAITING',paper_request=NULL,closed_status=NULL,actor_json=?,requested_action=?,next_check=NOW(6),message=? WHERE id=?",
                    encode(authority.capture(actor)), action, WAIT, id);
        }
        return id;
    }

    @Scheduled(initialDelay=20000,fixedDelay=15000)
    public void resume() {
        for (long id : jdbc.queryForList("""
                SELECT id FROM bank_recipient_change WHERE state IN ('WAITING','POLLING') AND next_check<=NOW(6)
                AND (lease_until IS NULL OR lease_until<NOW(6)) ORDER BY next_check,id LIMIT 15
                """,Long.class)) {
            try { process(id); }
            catch (RuntimeException error) { log.warn("Recipient change {} deferred ({})",id,error.getClass().getSimpleName()); }
        }
    }

    private Object process(long id) {
        Claim claim = tx.execute(status -> {
            String lease = UUID.randomUUID().toString();
            if (jdbc.update("""
                    UPDATE bank_recipient_change SET lease_token=?,lease_until=DATE_ADD(NOW(6),INTERVAL 3 MINUTE)
                    WHERE id=? AND state IN ('WAITING','POLLING') AND (lease_until IS NULL OR lease_until<NOW(6)) AND next_check<=NOW(6)
                    """,lease,id) != 1) return null;
            var job = jdbc.queryForObject("SELECT snapshot_json,actor_json,closed_status,state,requested_action FROM bank_recipient_change WHERE id=?",
                    (rs,n) -> new Job(id,decode(rs.getString(1),Source.class),decode(rs.getString(2),Actor.class),rs.getString(3),"POLLING".equals(rs.getString(4)),rs.getString(5)),id);
            return new Claim(job,lease);
        });
        if (claim == null) return null;
        Job job = claim.job();
        var original = SecurityContextHolder.getContext();
        boolean bankClosed = job.closedStatus() != null;
        try {
            var actor = authority.revalidate(job.actor());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(actor);
            SecurityContextHolder.setContext(context);
            Source resolved = job.source();
            if (resolved.operation().isBlank()) {
                Source initial = resolved;
                resolved = tx.execute(status -> resolveInitialization(initial, actor));
                if (resolved.operation().isBlank()) {
                    update(claim,"WAITING","Ожидаем завершения создания ссылки в банке. Запрос на смену получателя сохранён.");
                    return null;
                }
                if (jdbc.update("UPDATE bank_recipient_change SET snapshot_json=? WHERE id=? AND lease_token=?",
                        encode(resolved), id, claim.lease()) != 1) return null;
            }
            Source source = resolved;
            String closed = job.closedStatus();
            if (closed == null) {
                // Exercise the real reservation rules without publishing instructions or retaining a reserve
                // while a still-payable bank link is outstanding. Recheck under locks at final handover.
                if (!job.capacityChecked()) {
                    if (!"PAPER".equals(job.action())) checkCapacity(source, actor);
                    if (jdbc.update("UPDATE bank_recipient_change SET state='POLLING' WHERE id=? AND lease_token=?",
                            id,claim.lease()) != 1) return null;
                } else {
                    tx.executeWithoutResult(status -> {
                        if (!source.equals(resolveInitialization(source, actor)))
                            throw new SourceChanged("Банковская ссылка изменилась");
                    });
                }
                Result result;
                try {
                    result = bank.observe(source);
                } catch (RuntimeException unavailable) {
                    update(claim, "POLLING", WAIT);
                    return null;
                }
                if (result.outcome() != Outcome.CLOSED) {
                    boolean financial = result.outcome() == Outcome.FINANCIAL;
                    String message = financial ? "Банк сообщил о движении денег. Смена получателя остановлена."
                            : switch (Objects.toString(result.status(), "")) {
                                case "CREATED" -> TOCHKA_WAIT;
                                case "NEW", "FORM_SHOWED" -> TBANK_WAIT;
                                default -> WAIT;
                            };
                    if (!financial && "PAPER".equals(job.action()))
                        message = "Ожидаем закрытия банковской ссылки без оплаты. Система сама включит бумажный счёт.";
                    final String resultMessage = message;
                    if (financial && "INVOICE".equals(source.kind())) {
                        tx.executeWithoutResult(status -> {
                            var invoice = lockedInvoice(source.targetId());
                            var history = refs.findByInvoiceIdForUpdate(invoice.getId());
                            if (source.equals(invoiceSource(invoice,history))) {
                                var ref = invoices.bankRecipientRef(invoice,history);
                                settlement.applyRecipientChangeReceipt(source,result,invoice,ref);
                            }
                            update(claim,"STOPPED",resultMessage);
                        });
                    } else {
                        if (financial) orderRoutes.reconcile(source.sourceId());
                        update(claim, financial ? "STOPPED" : "POLLING",message);
                    }
                    return null;
                }
                closed = result.status();
                bankClosed = true;
                if (jdbc.update("UPDATE bank_recipient_change SET closed_status=? WHERE id=? AND lease_token=?",closed,id,claim.lease()) != 1)
                    return null;
            }
            String verified = closed;
            Object response = tx.execute(status -> {
                if ("PAPER".equals(job.action())) {
                    var invoice = lockedInvoice(source.targetId());
                    var history = refs.findByInvoiceIdForUpdate(invoice.getId());
                    if (!source.equals(invoiceSource(invoice,history))) throw new SourceChanged("Состав или ссылка счёта изменились");
                    if (!update(claim,"DONE","Включён бумажный счёт")) throw conflict("Запрос уже обрабатывается");
                    invoices.switchClosedBankToPaper(invoice, history, source.sourceId(), verified, actor.getName());
                    var request = jdbc.queryForObject("SELECT paper_request FROM bank_recipient_change WHERE id=?", String.class, id);
                    if (request != null) {
                        paperDocuments.getObject().prepareAfterBankClosure(source.targetId(), cipher.decrypt(request), actor);
                        jdbc.update("UPDATE bank_recipient_change SET paper_request=NULL WHERE id=?", id);
                    }
                    return null;
                }
                var result = replace(source,actor,verified,true);
                if (result instanceof PaymentRouteChangeResponse order) {
                    jdbc.update("UPDATE bank_recipient_change SET replacement_id=? WHERE id=? AND lease_token=?",order.paymentLinkId(),id,claim.lease());
                }
                if (!update(claim,"DONE","Получатель изменён")) throw conflict("Запрос уже обрабатывается");
                return result;
            });
            if ("INVOICE".equals(source.kind()) && !"PAPER".equals(job.action())) invoices.sendInvoice(source.targetId(), true,true);
            return response;
        } catch (AccessDeniedException denied) {
            update(claim,"STOPPED","Доступ к заказу изменился");
        } catch (ResponseStatusException failure) {
            // A bank response is not authoritative until the bound provider has returned a final status.
            boolean retry = failure.getStatusCode().is5xxServerError() || (bankClosed
                    && !"PAPER".equals(job.action()) && !(failure instanceof SourceChanged) && failure.getStatusCode() == HttpStatus.CONFLICT);
            String reason = Objects.toString(failure.getReason(), WAIT);
            if (bankClosed && retry)
                reason = "Банк закрыл ссылку. " + reason + ". Система повторит подбор получателя автоматически.";
            update(claim, retry ? "WAITING" : "STOPPED", reason);
        } catch (RuntimeException failure) {
            update(claim,"WAITING",WAIT);
            log.warn("Recipient change {} awaiting bank ({})",id,failure.getClass().getSimpleName());
        } finally { SecurityContextHolder.setContext(original); }
        return null;
    }

    private void checkCapacity(Source source, Authentication actor) {
        tx.executeWithoutResult(status -> {
            replace(source,actor,"CANCELED",false);
            status.setRollbackOnly();
        });
    }

    private static Source withOperation(Source s, String operation, String modes) {
        return new Source(s.kind(),s.targetId(),s.sourceId(),s.profileId(),s.provider(),operation,s.order(),s.merchant(),
                s.amount(),modes,s.test(),s.routeVersion());
    }
    private Source resolveInitialization(Source initial, Authentication actor) {
        Source current;
        if ("ORDER".equals(initial.kind())) current = orderRoutes.lockedSource(initial.targetId(),initial.sourceId(),actor);
        else {
            var invoice = lockedInvoice(initial.targetId());
            current = invoiceSource(invoice,refs.findByInvoiceIdForUpdate(invoice.getId()));
        }
        if (current == null || !withOperation(initial,current.operation(),current.modes()).equals(current))
            throw new SourceChanged("За время создания ссылки банковская операция изменилась");
        return current;
    }

    private Object replace(Source source, Authentication actor, String closed, boolean publish) {
        if ("ORDER".equals(source.kind())) {
            return orderRoutes.replace(source, closed, publish, actor);
        }
        var invoice = lockedInvoice(source.targetId());
        var history = refs.findByInvoiceIdForUpdate(invoice.getId());
        if (!source.equals(invoiceSource(invoice,history))) throw new SourceChanged("Маршрут или состав общего счёта изменился");
        invoices.replaceClosedBankRecipient(invoice, history, source.sourceId(),closed,actor.getName());
        return null;
    }

    private boolean update(Claim claim, String state, String message) {
        return jdbc.update("""
                UPDATE bank_recipient_change SET state=?,message=?,next_check=DATE_ADD(NOW(6),INTERVAL 1 MINUTE),
                lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?
                """,state,message.substring(0,Math.min(500,message.length())),claim.job().id(),claim.lease()) == 1;
    }
    private ResponseStatusException statusOf(long id) {
        var row = jdbc.queryForMap("SELECT state,message FROM bank_recipient_change WHERE id=?",id);
        return new ResponseStatusException(java.util.Set.of("WAITING","POLLING").contains(row.get("state")) ? HttpStatus.LOCKED : HttpStatus.CONFLICT,
                row.get("message").toString());
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch (JsonProcessingException e) {throw new IllegalStateException(e);}
    }
    private <T> T decode(String value, Class<T> type) {
        try {return json.readValue(value,type);} catch (JsonProcessingException e) {throw new IllegalStateException(e);}
    }

    private static ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
