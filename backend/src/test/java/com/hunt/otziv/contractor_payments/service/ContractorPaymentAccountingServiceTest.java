package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentAccountingServiceTest {

    @Mock
    private ContractorPaymentAllocationEventRepository eventRepository;

    private ContractorPaymentAccountingService service;

    @BeforeEach
    void setUp() {
        service = new ContractorPaymentAccountingService(eventRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void partialReturnPreservesGrossConfirmationAndOnlySubtractsReturnedPart() {
        ContractorPaymentAllocation allocation = allocation(100_000L, ContractorAllocationStatus.RESERVED);
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 10, 15);

        service.recordConfirmation(
                allocation, 100_000L, paidAt, "confirmed", "CONFIRM:1", true, false
        );
        service.recordReturnTotal(
                allocation, 35_000L, paidAt.plusHours(1), "partial", "RETURN:35000"
        );

        assertThat(allocation.getConfirmedKopecks()).isEqualTo(100_000L);
        assertThat(allocation.getReturnedKopecks()).isEqualTo(35_000L);
        assertThat(allocation.getConfirmedKopecks() - allocation.getReturnedKopecks())
                .isEqualTo(65_000L);
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.PARTIALLY_RETURNED);
        assertThat(allocation.getConfirmedAt()).isEqualTo(paidAt);
    }

    @Test
    void unknownPartialReturnIsFailClosedAndDoesNotInventReturnedAmount() {
        ContractorPaymentAllocation allocation = allocation(100_000L, ContractorAllocationStatus.SIMULATED_PAID);
        allocation.setConfirmedKopecks(100_000L);

        service.recordReturnAmountPending(
                allocation,
                LocalDateTime.of(2026, 8, 7, 12, 0),
                "provider omitted amount",
                "RETURN:PENDING"
        );

        assertThat(allocation.getReturnedKopecks()).isZero();
        assertThat(allocation.isNeedsReturnAmount()).isTrue();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RETURN_AMOUNT_PENDING);
    }

    @Test
    void lateConfirmationCanSubsequentlyBeFullyReturned() {
        ContractorPaymentAllocation allocation = allocation(75_000L, ContractorAllocationStatus.CANCELED);

        service.recordConfirmation(
                allocation, 75_000L, LocalDateTime.of(2026, 8, 7, 13, 0),
                "late", "LATE:1", false, true
        );
        service.recordReturnTotal(
                allocation, 75_000L, LocalDateTime.of(2026, 8, 7, 14, 0),
                "returned", "RETURN:FULL"
        );

        assertThat(allocation.getConfirmedKopecks()).isEqualTo(75_000L);
        assertThat(allocation.getReturnedKopecks()).isEqualTo(75_000L);
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RETURNED);
    }

    @Test
    void partialAndIncrementalLateConfirmationsNeverReopenReservation() {
        ContractorPaymentAllocation allocation = allocation(100_000L, ContractorAllocationStatus.CANCELED);
        allocation.setReleasedAt(LocalDateTime.of(2026, 8, 7, 9, 0));

        service.recordConfirmation(
                allocation, 30_000L, LocalDateTime.of(2026, 8, 7, 10, 0),
                "late partial", "LATE:30000", true, true
        );
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE);

        service.recordConfirmation(
                allocation, 50_000L, LocalDateTime.of(2026, 8, 7, 11, 0),
                "late increment", "LATE:50000", true, true
        );
        assertThat(allocation.getConfirmedKopecks()).isEqualTo(50_000L);
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.LATE_PAYMENT_AFTER_RELEASE);
    }

    @Test
    void closingOwnerFallbackRecordsNoReleasedContractorAmount() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L,
                ContractorAllocationStatus.OWNER_FALLBACK
        );

        service.recordRelease(
                allocation,
                ContractorAllocationStatus.RELEASED_UNPAID,
                LocalDateTime.of(2026, 8, 7, 15, 0),
                "not paid",
                "OWNER:CLOSED"
        );

        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        org.mockito.Mockito.verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getAmountKopecks()).isZero();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RELEASED_UNPAID);
    }

    @Test
    void ownerFallbackEventCopiesStructuredDecisionTraceAndReadableReason() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L,
                ContractorAllocationStatus.OWNER_FALLBACK
        );
        allocation.setAttemptNo(1);
        allocation.setRoutingDecisionReason(
                ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED
        );
        allocation.setSpecialistRejectionReason(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE
        );
        allocation.setManagerRejectionReason(
                ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED
        );

        service.recordReservation(allocation);

        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType())
                .isEqualTo(ContractorAllocationEventType.OWNER_FALLBACK);
        assertThat(event.getValue().getRoutingDecisionReason())
                .isEqualTo(ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED);
        assertThat(event.getValue().getSpecialistRejectionReason())
                .isEqualTo(ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE);
        assertThat(event.getValue().getManagerRejectionReason())
                .isEqualTo(ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED);
        assertThat(event.getValue().getReason())
                .contains("routingDecision=LIMIT_DAILY_COUNT_EXCEEDED")
                .contains("specialistRejection=INSUFFICIENT_AVAILABLE_BALANCE")
                .contains("managerRejection=LIMIT_DAILY_COUNT_EXCEEDED");
    }

    @Test
    void immutableFinancialEventCapturesAuthenticatedManualActor() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner-auditor", "", java.util.List.of())
        );
        ContractorPaymentAllocation allocation = allocation(
                10_000L,
                ContractorAllocationStatus.PARTIALLY_RETURNED
        );
        allocation.setConfirmedKopecks(10_000L);

        service.recordReturnTotal(
                allocation,
                5_000L,
                LocalDateTime.of(2026, 8, 7, 16, 0),
                "manual exact return",
                "MANUAL:RETURN:5000"
        );

        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        org.mockito.Mockito.verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getActor()).isEqualTo("owner-auditor");
    }

    @Test
    void lateClientReportCannotDowngradePartialConfirmationOrReviveTerminalAllocation() {
        ContractorPaymentAllocation partial = allocation(
                100_000L, ContractorAllocationStatus.PARTIALLY_CONFIRMED
        );
        partial.setConfirmedKopecks(40_000L);
        ContractorPaymentAllocation released = allocation(
                100_000L, ContractorAllocationStatus.RELEASED_UNPAID
        );

        assertThat(service.recordClientReported(
                partial, LocalDateTime.now(), "late report", "REPORT:PARTIAL"
        )).isFalse();
        assertThat(service.recordClientReported(
                released, LocalDateTime.now(), "late report", "REPORT:RELEASED"
        )).isFalse();

        assertThat(partial.getStatus()).isEqualTo(ContractorAllocationStatus.PARTIALLY_CONFIRMED);
        assertThat(released.getStatus()).isEqualTo(ContractorAllocationStatus.RELEASED_UNPAID);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void replayedConfirmationWithSameTotalCannotReopenReturnedState() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.RETURNED
        );
        allocation.setConfirmedKopecks(100_000L);
        allocation.setReturnedKopecks(100_000L);

        boolean changed = service.recordConfirmation(
                allocation,
                100_000L,
                LocalDateTime.now(),
                "provider replay",
                "CONFIRM:REPLAY",
                false,
                true
        );

        assertThat(changed).isFalse();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RETURNED);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void resolvingPendingWithZeroRestoresConfirmedStateAndWritesAuditFact() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.RETURN_AMOUNT_PENDING
        );
        allocation.setConfirmedKopecks(100_000L);
        allocation.setNeedsReturnAmount(true);
        allocation.setReleasedAt(LocalDateTime.now().minusHours(1));

        boolean changed = service.recordReturnTotal(
                allocation,
                0L,
                LocalDateTime.now(),
                "provider confirmed no return",
                "RETURN:RESOLVED:0"
        );

        assertThat(changed).isTrue();
        assertThat(allocation.isNeedsReturnAmount()).isFalse();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.SIMULATED_PAID);
        assertThat(allocation.getReleasedAt()).isNull();
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getAmountKopecks()).isZero();
    }

    @Test
    void resolvingPendingWithAlreadyRecordedPartialTotalClearsPendingWithoutDoubleCounting() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.RETURN_AMOUNT_PENDING
        );
        allocation.setConfirmedKopecks(100_000L);
        allocation.setReturnedKopecks(25_000L);
        allocation.setNeedsReturnAmount(true);

        boolean changed = service.recordReturnTotal(
                allocation,
                25_000L,
                LocalDateTime.now(),
                "provider confirmed exact total",
                "RETURN:RESOLVED:25000"
        );

        assertThat(changed).isTrue();
        assertThat(allocation.isNeedsReturnAmount()).isFalse();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.PARTIALLY_RETURNED);
        assertThat(allocation.getReturnedKopecks()).isEqualTo(25_000L);
    }

    @Test
    void staleUnpaidReleaseCannotOverwriteConcurrentConfirmation() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.CONFIRMED
        );
        allocation.setConfirmedKopecks(100_000L);

        boolean changed = service.recordRelease(
                allocation,
                ContractorAllocationStatus.RELEASED_UNPAID,
                LocalDateTime.now(),
                "stale unpaid callback",
                "UNPAID:STALE"
        );

        assertThat(changed).isFalse();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.CONFIRMED);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void returnedAllocationCannotRegressBackToUnknownReturnPending() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.RETURNED
        );
        allocation.setConfirmedKopecks(100_000L);
        allocation.setReturnedKopecks(100_000L);

        boolean changed = service.recordReturnAmountPending(
                allocation,
                LocalDateTime.now(),
                "stale partial refund signal",
                "RETURN:PENDING:STALE"
        );

        assertThat(changed).isFalse();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RETURNED);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void laterConfirmationCannotHideAnUnresolvedReturnAmount() {
        ContractorPaymentAllocation allocation = allocation(
                100_000L, ContractorAllocationStatus.RETURN_AMOUNT_PENDING
        );
        allocation.setConfirmedKopecks(40_000L);
        allocation.setNeedsReturnAmount(true);

        boolean changed = service.recordConfirmation(
                allocation,
                70_000L,
                LocalDateTime.now(),
                "additional provider confirmation",
                "CONFIRM:AFTER_RETURN_PENDING",
                false,
                false
        );

        assertThat(changed).isTrue();
        assertThat(allocation.getConfirmedKopecks()).isEqualTo(70_000L);
        assertThat(allocation.isNeedsReturnAmount()).isTrue();
        assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.RETURN_AMOUNT_PENDING);
    }

    @Test
    void closedWithoutPaymentPeriodUsesDurableReleaseCancelAndExpiryFacts() {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(7L);
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        when(eventRepository.sumByProfileAndModeAndTypeInAndPeriod(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(ContractorAllocationMode.LIVE),
                org.mockito.ArgumentMatchers.argThat(types ->
                        types.contains(ContractorAllocationEventType.RELEASED)
                                && types.contains(ContractorAllocationEventType.EXPIRED)
                                && types.contains(ContractorAllocationEventType.CANCELED)
                                && types.size() == 3
                ),
                org.mockito.ArgumentMatchers.eq(from),
                org.mockito.ArgumentMatchers.eq(to)
        )).thenReturn(42_000L);

        assertThat(service.closedWithoutPaymentInPeriod(
                profile, ContractorAllocationMode.LIVE, from, to
        )).isEqualTo(42_000L);
    }

    private ContractorPaymentAllocation allocation(long amount, ContractorAllocationStatus status) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(1L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setAmountKopecks(amount);
        allocation.setStatus(status);
        return allocation;
    }
}
