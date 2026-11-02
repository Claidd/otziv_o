package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.c_companies.api.CompanyChatLinkChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class CompanyChatLinkChangedListener {
    private final WhatsAppGroupLinkSyncService whatsapp;

    // Suspend the committed transaction's resources before network lookup and conditional repair.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void updateBinding(CompanyChatLinkChanged event) {
        if (event == null || event.companyId() == null || event.urlChat() == null) return;
        try {
            if (WhatsAppGroupCompanyLinker.whatsAppInviteCode(event.urlChat()).isPresent()) {
                whatsapp.repairChangedCompanyLink(event.companyId(), event.urlChat());
            }
        } catch (RuntimeException unavailable) {
            log.warn("Changed company chat awaits binding verification: companyId={}, errorType={}",
                    event.companyId(), unavailable.getClass().getSimpleName());
        }
    }
}
