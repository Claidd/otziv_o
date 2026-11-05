package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ManualDispatchJournal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes operator messages into the per-account ledger read by {@link SenderAccountPacing}. */
@Component
public class ManualSenderDispatchJournal implements ManualDispatchJournal {

    private final SenderAccountPacing pacing;
    private final ClientMessageTime time;

    public ManualSenderDispatchJournal(SenderAccountPacing pacing, ClientMessageTime time) {
        this.pacing = pacing;
        this.time = time;
    }

    /** Committed at once, so a background decision taken while the message is in flight already sees it. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordManualDispatch(Sender sender) {
        if (sender == null) {
            return;
        }
        pacing.recordManual(SenderAccountPacing.account(sender.platform(), sender.clientId()), time.nowStorage());
    }
}
