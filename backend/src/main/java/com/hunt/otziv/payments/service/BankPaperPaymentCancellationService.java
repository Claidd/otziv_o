package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.api.BankPaperPaymentCancellation;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service @RequiredArgsConstructor
public class BankPaperPaymentCancellationService implements BankPaperPaymentCancellation {
    private final PaymentLinkRepository links;
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancel(long linkId,String documentId) {
        var link=links.findByIdForUpdate(linkId).orElseThrow();
        if(!documentId.equals(link.getPaperBankDocumentId()) || link.getPaidAt()!=null || link.getConfirmedAmountKopecks()!=null
                || !java.util.Set.of(PaymentLinkStatus.CREATED,PaymentLinkStatus.WAITING_MANUAL_PAYMENT).contains(link.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Платёжная запись изменилась или уже оплачена");
        link.setStatus(PaymentLinkStatus.CANCELED);link.setPaperBankDocumentId(null);
        link.setLastError("Счёт и акт отменены в Точке; история сохранена в банковском комплекте");links.saveAndFlush(link);
    }
}
