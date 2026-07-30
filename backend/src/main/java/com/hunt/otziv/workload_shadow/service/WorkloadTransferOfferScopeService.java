package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadTransferOfferScopeService {

    static final String OUTSIDE_SCOPE_REASON =
            "Менеджер исключён из текущего боевого контура до доставки предложения";

    private final WorkloadTransferOfferRepository repository;

    @Transactional
    public int cancelClaimedOutsideScope(
            String processingToken,
            List<Long> offerIds
    ) {
        if (processingToken == null
                || processingToken.isBlank()
                || offerIds == null
                || offerIds.isEmpty()) {
            return 0;
        }
        return repository.cancelClaimedOffersOutsideScope(
                processingToken,
                List.copyOf(offerIds),
                OUTSIDE_SCOPE_REASON
        );
    }
}
