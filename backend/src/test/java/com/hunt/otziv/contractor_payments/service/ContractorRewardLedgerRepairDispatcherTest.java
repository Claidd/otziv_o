package com.hunt.otziv.contractor_payments.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ContractorRewardLedgerRepairDispatcherTest {

    @Mock private ZpRepository zpRepository;
    @Mock private ContractorRewardRepairClaimService claimService;
    @Mock private ContractorRewardLedgerService ledgerService;

    @Test
    void poisonSourceIsQuarantinedWithoutBlockingLaterSource() {
        Zp bad = new Zp();
        bad.setId(1L);
        Zp good = new Zp();
        good.setId(2L);
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(), any(Pageable.class)))
                .thenReturn(List.of(bad, good));
        when(claimService.tryClaim(any(), any())).thenReturn(Optional.of("t1"), Optional.of("t2"));
        doThrow(new IllegalStateException("sensitive provider detail"))
                .doNothing()
                .when(ledgerService).synchronizeSourceId(any());
        ContractorRewardLedgerRepairDispatcher dispatcher = new ContractorRewardLedgerRepairDispatcher(
                zpRepository, claimService, ledgerService
        );

        dispatcher.reconcileMissingLedgerEntries();

        InOrder progress = inOrder(ledgerService, claimService);
        progress.verify(ledgerService).synchronizeSourceId(1L);
        progress.verify(claimService).failed(any(), any(), any(), any());
        progress.verify(ledgerService).synchronizeSourceId(2L);
        progress.verify(claimService).succeeded(2L, "t2");
    }
}
