package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentLinkReturnOutboxService {

    private static final Set<PaymentLinkStatus> RETURN_STATUSES = EnumSet.of(
            PaymentLinkStatus.CANCELED,
            PaymentLinkStatus.REVERSED,
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.REFUNDED,
            PaymentLinkStatus.PARTIAL_REFUNDED
    );

    private final PaymentLinkReturnOutboxRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PaymentLink link) {
        if (link == null || link.getId() == null || !RETURN_STATUSES.contains(link.getStatus())) {
            return;
        }
        long persistedVersion = link.getRowVersion() == null ? 0L : link.getRowVersion();
        repository.enqueue(link.getId(), persistedVersion, link.getStatus().name());
    }
}
