package com.hunt.otziv.p_products.next_order.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.repository.NextOrderWorkerRoutingRepository;
import com.hunt.otziv.p_products.next_order.repository.NextOrderWorkerRoutingRepository.Membership;
import com.hunt.otziv.p_products.next_order.repository.NextOrderWorkerRoutingRepository.Transfer;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Worker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NextOrderWorkerRoutingServiceTest {
    @Mock NextOrderWorkerRoutingRepository repository;
    NextOrderWorkerRoutingService service;
    Order source;

    @BeforeEach void setup() {
        service = new NextOrderWorkerRoutingService(repository);
        Company company = new Company(); company.setId(748L);
        Manager manager = new Manager(); manager.setId(2L);
        Worker worker = new Worker(); worker.setId(13L);
        source = Order.builder().id(19403L).company(company).manager(manager).worker(worker).build();
        when(repository.lockCompanyManager(748L)).thenReturn(2L);
    }

    @Test void keepsSourceForIntentionallySharedCompany() {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(member(6L), member(13L)));
        assertEquals(13L, service.resolveWorkerId(source));
        verify(repository, never()).lockAppliedTransfers(anyLong());
    }

    @Test void routesRemovedSourceToConfirmedRecipient() {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(member(6L), member(7L)));
        when(repository.lockAppliedTransfers(748L)).thenReturn(List.of(new Transfer(13L, 6L)));
        assertEquals(6L, service.resolveWorkerId(source));
        assertEquals(13L, source.getWorker().getId());
    }

    @Test void followsSuccessiveTransfersAndUsesLatestEdge() {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(member(7L)));
        when(repository.lockAppliedTransfers(748L)).thenReturn(List.of(
                new Transfer(6L, 7L), new Transfer(13L, 6L), new Transfer(13L, 9L)));
        assertEquals(7L, service.resolveWorkerId(source));
    }

    @Test void neverGuessesFromSoleCurrentMemberWithoutTransferEvidence() {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(member(6L)));
        when(repository.lockAppliedTransfers(748L)).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.resolveWorkerId(source));
    }

    @Test void cycleCannotAssignAnUnlinkedSpecialist() {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(member(7L)));
        when(repository.lockAppliedTransfers(748L)).thenReturn(List.of(new Transfer(13L, 6L), new Transfer(6L, 13L)));
        assertThrows(IllegalStateException.class, () -> service.resolveWorkerId(source));
    }

    @Test void inactiveRecipientCannotReceiveOrder() {
        invalidMember(new Membership(6L, false, true));
    }

    @Test void recipientWithDifferentOrMultipleManagersCannotReceiveOrder() {
        invalidMember(new Membership(6L, true, false));
    }

    @Test void changedCompanyManagerStopsHistoricalOrder() {
        when(repository.lockCompanyManager(748L)).thenReturn(3L);
        assertThrows(IllegalStateException.class, () -> service.resolveWorkerId(source));
        verify(repository, never()).lockMemberships(anyLong(), anyLong());
    }

    private void invalidMember(Membership membership) {
        when(repository.lockMemberships(748L, 2L)).thenReturn(List.of(membership));
        when(repository.lockAppliedTransfers(748L)).thenReturn(List.of(new Transfer(13L, 6L)));
        assertThrows(IllegalStateException.class, () -> service.resolveWorkerId(source));
    }

    private Membership member(long id) { return new Membership(id, true, true); }
}
