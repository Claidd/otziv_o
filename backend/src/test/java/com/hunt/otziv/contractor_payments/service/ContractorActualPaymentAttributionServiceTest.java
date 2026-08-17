package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentRecipientCommand;
import com.hunt.otziv.contractor_payments.dto.ContractorActualPaymentSource;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentContextResponse;
import com.hunt.otziv.contractor_payments.dto.ManualCardPaymentRecipientResponse;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.dto.ManualPaymentTaskSourceRef;
import com.hunt.otziv.payments.model.ManualPaymentTaskLedgerSourceKind;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.u_users.model.User;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorActualPaymentAttributionServiceTest {

    private static final long SOURCE_ID = 10L;
    private static final long ORDER_ID = 20L;
    private static final LocalDateTime EFFECTIVE_AT = LocalDateTime.now()
            .minusMinutes(5)
            .truncatedTo(ChronoUnit.MICROS);

    @Mock
    private ContractorActualPaymentAttributionRepository attributionRepository;
    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorPaymentAllocationEventRepository eventRepository;
    @Mock
    private ContractorPaymentProfileRepository profileRepository;
    @Mock
    private ContractorPaymentProfileService profileService;
    @Mock
    private ContractorPaymentAccountingService accountingService;
    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private ContractorOrderManagerResolver orderManagerResolver;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;
    @Mock
    private ManualPaymentTaskContractorCapacityService taskCapacityService;

    @InjectMocks
    private ContractorActualPaymentAttributionService service;

    private final Map<Long, ContractorPaymentProfile> profiles = new LinkedHashMap<>();
    private final Map<Long, ContractorPaymentAllocation> allocations = new LinkedHashMap<>();
    private final Map<String, ContractorActualPaymentAttribution> attributions = new LinkedHashMap<>();
    private final Set<String> events = new LinkedHashSet<>();
    private final AtomicLong attributionIds = new AtomicLong(500L);
    private final AtomicLong allocationIds = new AtomicLong(800L);
    private final Map<Long, Long> available = new LinkedHashMap<>();

    private ContractorPaymentProfile specialist;
    private ContractorPaymentProfile manager;

    @BeforeEach
    void setUp() {
        specialist = profile(1L, 11L, ContractorRole.SPECIALIST, "Анна Специалист");
        manager = profile(2L, 22L, ContractorRole.MANAGER, "Мария Менеджер");
        profiles.put(specialist.getId(), specialist);
        profiles.put(manager.getId(), manager);

        lenient().when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        lenient().when(taskCapacityService.ordinaryAvailable(any(), any())).thenAnswer(invocation ->
                profileService.available(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(profileService.capacityPosition(any(), any())).thenAnswer(invocation ->
                profileService.available(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(appSettingService.getStringFresh(
                anyString(), org.mockito.ArgumentMatchers.isNull()
        )).thenReturn("true");
        lenient().when(attributionRepository.findByAttributionKey(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(attributions.get(invocation.getArgument(0))));
        lenient().when(attributionRepository.findByAttributionKeyForUpdate(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(attributions.get(invocation.getArgument(0))));
        lenient().when(attributionRepository.findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                any(), anyLong()
        )).thenAnswer(invocation -> attributions.values().stream()
                .filter(row -> row.getSourceKind() == invocation.getArgument(0))
                .filter(row -> row.getSourceId().equals(invocation.getArgument(1)))
                .sorted(Comparator.comparing(ContractorActualPaymentAttribution::getId))
                .toList());
        lenient().when(attributionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ContractorActualPaymentAttribution row = invocation.getArgument(0);
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", attributionIds.getAndIncrement());
            }
            attributions.put(row.getAttributionKey(), row);
            return row;
        });

        lenient().when(profileRepository.findAllByIdForUpdate(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().sorted().map(profiles::get).filter(java.util.Objects::nonNull).toList();
        });
        lenient().when(allocationRepository.findRecipientProfileIdById(anyLong())).thenAnswer(invocation -> {
            ContractorPaymentAllocation allocation = allocations.get(invocation.getArgument(0));
            return Optional.ofNullable(allocation == null || allocation.getRecipientProfile() == null
                    ? null : allocation.getRecipientProfile().getId());
        });
        lenient().when(allocationRepository.findLatestIdsBySourceAcrossModes(anyString(), anyLong()))
                .thenAnswer(invocation -> latestSourceIds(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(allocationRepository.findLatestId(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> latestId(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)
                ));
        lenient().when(allocationRepository.findAllByIdForUpdate(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().sorted().map(allocations::get).filter(java.util.Objects::nonNull).toList();
        });
        lenient().when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                any(), any(), anyLong()
        )).thenAnswer(invocation -> allocations.values().stream()
                .filter(value -> value.getMode() == invocation.getArgument(0))
                .filter(value -> value.getSourceType() == invocation.getArgument(1))
                .filter(value -> value.getSourceId().equals(invocation.getArgument(2)))
                .max(Comparator.comparing(ContractorPaymentAllocation::getAttemptNo)
                        .thenComparing(ContractorPaymentAllocation::getId)));
        lenient().when(allocationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ContractorPaymentAllocation allocation = invocation.getArgument(0);
            if (allocation.getId() == null) {
                allocation.setId(allocationIds.getAndIncrement());
            }
            allocations.put(allocation.getId(), allocation);
            return allocation;
        });
        lenient().when(profileService.available(any(), any())).thenAnswer(invocation ->
                available.getOrDefault(((ContractorPaymentProfile) invocation.getArgument(0)).getId(), 0L));
        lenient().when(accountingService.recordRelease(any(), any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    ContractorPaymentAllocation allocation = invocation.getArgument(0);
                    String ref = invocation.getArgument(4);
                    if (!events.add(eventKey(allocation.getId(), ref))) {
                        return false;
                    }
                    allocation.setStatus(invocation.getArgument(1));
                    allocation.setReleasedAt(invocation.getArgument(2));
                    return true;
                });
        lenient().when(accountingService.recordConfirmation(
                any(), anyLong(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()
        )).thenAnswer(invocation -> {
            ContractorPaymentAllocation allocation = invocation.getArgument(0);
            String ref = invocation.getArgument(4);
            if (!events.add(eventKey(allocation.getId(), ref))) {
                return false;
            }
            allocation.setConfirmedKopecks(invocation.getArgument(1));
            allocation.setStatus(allocation.getMode() == ContractorAllocationMode.SHADOW
                    ? ContractorAllocationStatus.SIMULATED_PAID
                    : ContractorAllocationStatus.CONFIRMED);
            return true;
        });
        lenient().when(eventRepository.existsByAllocationIdAndExternalRef(anyLong(), anyString()))
                .thenAnswer(invocation -> events.contains(eventKey(invocation.getArgument(0), invocation.getArgument(1))));
    }

    @Test
    void frozenOwnerTaskSuppressesOrdinaryOwnerAlias() {
        ManualPaymentTaskRouteSnapshot task = taskSnapshot(
                ManualPaymentTaskAccountingTargetKind.OWNER, null);
        LinkedHashMap<String, ManualCardPaymentRecipientResponse> candidates = aliases(
                task, new ManualCardPaymentRecipientResponse(
                        ContractorRecipientType.OWNER, null, null,
                        "Владелец", 0L, 0L, true), "OWNER:OWNER");

        ReflectionTestUtils.invokeMethod(service,
                "suppressOrdinaryAliasForTask", candidates, task);

        assertThat(candidates.keySet()).containsExactly(task.candidateKey());
    }

    @Test
    void frozenSpecialistTaskSuppressesProfileAliasSoOldKeyFailsClosed() {
        ManualPaymentTaskRouteSnapshot task = taskSnapshot(
                ManualPaymentTaskAccountingTargetKind.SPECIALIST, 1L);
        LinkedHashMap<String, ManualCardPaymentRecipientResponse> candidates = aliases(
                task, new ManualCardPaymentRecipientResponse(
                        ContractorRecipientType.SPECIALIST, 1L, 11L,
                        "Анна", 0L, 0L, true), "SPECIALIST:1");

        ReflectionTestUtils.invokeMethod(service,
                "suppressOrdinaryAliasForTask", candidates, task);

        assertThat(candidates.keySet()).containsExactly(task.candidateKey());
        assertThat(candidates.values()).noneMatch(value -> "PROFILE:1".equals(value.key()));
    }

    @Test
    void confirmsOriginalRecipientWithoutCreatingSecondAllocation() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );

        ContractorActualPaymentAttribution row = record(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.SPECIALIST, specialist
        );

        assertThat(original.getStatus()).isEqualTo(ContractorAllocationStatus.CONFIRMED);
        assertThat(row.getProjectedOverrunKopecks()).isZero();
        assertThat(actualAllocations()).isEmpty();
        verify(accountingService, never()).recordRelease(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void ownerRouteCreditsActualManagerAndRecordsOverrun() {
        ContractorPaymentAllocation owner = ownerAllocation(100L, ContractorAllocationMode.LIVE);
        available.put(manager.getId(), 100L);

        ContractorActualPaymentAttribution row = record(
                owner.getId(), owner.getId(), ContractorRecipientType.OWNER, null,
                ContractorRecipientType.MANAGER, manager
        );

        assertThat(row.getAvailableBeforeKopecks()).isEqualTo(100L);
        assertThat(row.getProjectedOverrunKopecks()).isEqualTo(900L);
        assertThat(actualAllocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getMode()).isEqualTo(ContractorAllocationMode.LIVE);
            assertThat(allocation.getRecipientProfile()).isEqualTo(manager);
            assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.CONFIRMED);
        });
        verify(accountingService, never()).recordRelease(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void reallocatesSpecialistToManagerAtomically() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );
        available.put(manager.getId(), 1_000L);

        record(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.MANAGER, manager
        );

        assertThat(original.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(actualAllocations()).singleElement()
                .extracting(ContractorPaymentAllocation::getRecipientProfile)
                .isEqualTo(manager);
    }

    @Test
    void reallocatesSpecialistToOwnerWithoutContractorCredit() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );

        record(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.OWNER, null
        );

        assertThat(original.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(actualAllocations()).isEmpty();
        verify(accountingService, never()).recordReservation(any());
    }

    @Test
    void closesShadowAndLiveRoutesButCreditsOnlyLiveActualRecipient() {
        ContractorPaymentAllocation shadow = sourceAllocation(
                90L, ContractorAllocationMode.SHADOW, specialist, ContractorAllocationStatus.RESERVED
        );
        ContractorPaymentAllocation live = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );
        available.put(manager.getId(), 1_000L);

        record(
                live.getId(), shadow.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.MANAGER, manager
        );

        assertThat(shadow.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(live.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(shadow.getLastReconciledAt()).isNotNull();
        assertThat(live.getLastReconciledAt()).isNotNull();
        assertThat(actualAllocations()).singleElement()
                .extracting(ContractorPaymentAllocation::getMode)
                .isEqualTo(ContractorAllocationMode.LIVE);
        assertThat(events).anyMatch(value -> value.contains("HISTORICAL_REALLOCATE"));
    }

    @Test
    void closesShadowAndLiveRoutesForOwnerWithoutContractorCredit() {
        ContractorPaymentAllocation shadow = sourceAllocation(
                90L, ContractorAllocationMode.SHADOW, specialist, ContractorAllocationStatus.RESERVED
        );
        ContractorPaymentAllocation live = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );

        record(
                live.getId(), shadow.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.OWNER, null
        );

        assertThat(shadow.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(live.getStatus()).isEqualTo(ContractorAllocationStatus.CANCELED);
        assertThat(actualAllocations()).isEmpty();
    }

    @Test
    void exactReplayVerifiesOnlyAndDoesNotWriteAccounting() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );
        available.put(manager.getId(), 1_000L);
        ContractorActualPaymentSource source = source(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist
        );
        ContractorActualPaymentRecipientCommand command = command(ContractorRecipientType.MANAGER, manager);
        service.recordFinalAttributions(source, List.of(command));
        clearInvocations(accountingService, allocationRepository, attributionRepository);

        service.recordFinalAttributions(source, List.of(command));

        verifyNoInteractions(accountingService);
        verify(attributionRepository, never()).saveAndFlush(any());
        verify(allocationRepository, never()).saveAndFlush(any());
    }

    @Test
    void corruptedReplayWithMissingConfirmationFailsClosedWithoutRepair() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );
        available.put(manager.getId(), 1_000L);
        ContractorActualPaymentSource source = source(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist
        );
        ContractorActualPaymentRecipientCommand command = command(ContractorRecipientType.MANAGER, manager);
        ContractorActualPaymentAttribution row = service.recordFinalAttributions(source, List.of(command)).getFirst();
        ContractorPaymentAllocation actual = actualAllocations().getFirst();
        events.remove(eventKey(actual.getId(), "ACTUAL_PAYMENT:CONFIRM:" + row.getId()));
        clearInvocations(accountingService, allocationRepository, attributionRepository);

        assertThatThrownBy(() -> service.recordFinalAttributions(source, List.of(command)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("без подтверждения");
        verifyNoInteractions(accountingService);
        verify(attributionRepository, never()).saveAndFlush(any());
        verify(allocationRepository, never()).saveAndFlush(any());
    }

    @Test
    void failedReleaseStopsBeforeImmutableRowOrActualCredit() {
        ContractorPaymentAllocation original = sourceAllocation(
                100L, ContractorAllocationMode.LIVE, specialist, ContractorAllocationStatus.RESERVED
        );
        doReturn(false).when(accountingService)
                .recordRelease(any(), any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> record(
                original.getId(), original.getId(), ContractorRecipientType.SPECIALIST, specialist,
                ContractorRecipientType.MANAGER, manager
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("атомарно освободить");
        verify(attributionRepository, never()).saveAndFlush(any());
        verify(accountingService, never()).recordReservation(any());
        verify(accountingService, never()).recordConfirmation(
                any(), anyLong(), any(), anyString(), anyString(), anyBoolean(), anyBoolean()
        );
    }

    @Test
    void malformedOrUnreadableShadowFlagNeverReopensLegacyBypass() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(appSettingService.getStringFresh(
                anyString(), org.mockito.ArgumentMatchers.isNull()
        )).thenReturn("ture").thenThrow(new IllegalStateException("db unavailable"));

        assertThat(service.actualRecipientAccountingEnabled()).isTrue();
        assertThat(service.actualRecipientAccountingEnabled()).isTrue();
    }

    @Test
    void globalOffFailsBeforeAnyAccountingMutation() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(appSettingService.getStringFresh(
                org.mockito.ArgumentMatchers.eq(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED),
                org.mockito.ArgumentMatchers.isNull()
        )).thenReturn(null);

        assertThatThrownBy(() -> service.recordFinalAttributions(
                source(null, null, ContractorRecipientType.OWNER, null),
                List.of(command(ContractorRecipientType.OWNER, null))
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("выключен");
        verify(attributionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(accountingService);
    }

    @Test
    void issuedShadowOwnerTaskCanBeContextualizedAndFrozenAfterToggleOff() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        when(appSettingService.getStringFresh(
                org.mockito.ArgumentMatchers.eq(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED),
                org.mockito.ArgumentMatchers.isNull()
        )).thenReturn("false");
        ManualPaymentTaskRouteSnapshot task = taskSnapshot(
                ManualPaymentTaskAccountingTargetKind.OWNER, null);
        Order order = new Order();
        order.setId(ORDER_ID);
        PaymentLink link = issuedTaskLink(order, task);
        stubTaskCandidate(link, task);

        ManualCardPaymentContextResponse context = service.manualCardPaymentContext(order, link);
        assertThat(context.originalRecipient().key()).isEqualTo(task.candidateKey());

        service.freezePaymentLinkRecipientIntent(
                order, link, task.candidateKey(), ContractorRecipientType.OWNER, null,
                "Клиент оплатил владельцу из задания", null, "manager");

        assertThat(link.getManualActualAccountingMode()).isEqualTo(ContractorAllocationMode.SHADOW);
        assertThat(link.getManualActualCashDestinationKind())
                .isEqualTo(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        assertThat(link.getManualActualTaskId()).isEqualTo(task.taskId());
        assertThat(link.getManualActualRecipientFrozenAt()).isNotNull();
    }

    @Test
    void issuedShadowExternalTaskCanBeContextualizedAndFrozenAfterLivePromotion() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        ManualPaymentTaskRouteSnapshot task = taskSnapshot(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK, null);
        Order order = new Order();
        order.setId(ORDER_ID);
        PaymentLink link = issuedTaskLink(order, task);
        stubTaskCandidate(link, task);

        ManualCardPaymentContextResponse context = service.manualCardPaymentContext(order, link);
        assertThat(context.originalRecipient().key()).isEqualTo(task.candidateKey());

        service.freezePaymentLinkRecipientIntent(
                order, link, task.candidateKey(), null, null,
                "Клиент оплатил внешнему получателю задания", null, "manager");

        assertThat(link.getManualActualAccountingMode()).isEqualTo(ContractorAllocationMode.SHADOW);
        assertThat(link.getManualActualTaskTargetKind())
                .isEqualTo(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        assertThat(link.getManualActualRecipientFrozenAt()).isNotNull();
    }

    @Test
    void frozenCommonSourceKeepsPersistedShadowModeAfterGlobalPromotionAndReplays() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        ContractorActualPaymentSource source = source(
                null, null, ContractorRecipientType.OWNER, null);
        ContractorActualPaymentRecipientCommand command =
                command(ContractorRecipientType.OWNER, null);

        ContractorActualPaymentAttribution row = service
                .recordFinalAttributionsForFrozenSource(
                        source, List.of(command), ContractorAllocationMode.SHADOW)
                .getFirst();

        assertThat(row.getAccountingMode()).isEqualTo(ContractorAllocationMode.SHADOW);
        clearInvocations(accountingService, allocationRepository, attributionRepository);

        List<ContractorActualPaymentAttribution> replay = service
                .requireFinalAttributionsAccountingAppliedForFrozenSource(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK,
                        SOURCE_ID,
                        ContractorAllocationMode.SHADOW
                );

        assertThat(replay).containsExactly(row);
        verify(attributionRepository, never()).saveAndFlush(any());
        verify(allocationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(accountingService);
    }

    @Test
    void frozenSourceReplayFailsClosedWhenPersistedModeDiffersFromRecordedMode() {
        ContractorActualPaymentSource source = source(
                null, null, ContractorRecipientType.OWNER, null);
        service.recordFinalAttributionsForFrozenSource(
                source,
                List.of(command(ContractorRecipientType.OWNER, null)),
                ContractorAllocationMode.SHADOW
        );
        clearInvocations(accountingService, allocationRepository, attributionRepository);

        assertThatThrownBy(() -> service
                .requireFinalAttributionsAccountingAppliedForFrozenSource(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK,
                        SOURCE_ID,
                        ContractorAllocationMode.LIVE
                ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Режим учёта");
        verify(attributionRepository, never()).saveAndFlush(any());
        verify(allocationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(accountingService);
    }

    @Test
    void frozenPaymentLinkKeepsPersistedShadowModeAfterGlobalPromotionAndReplaysExactly() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.LIVE);
        Order order = new Order();
        order.setId(ORDER_ID);
        PaymentLink original = new PaymentLink();
        original.setId(SOURCE_ID);
        original.setOrder(order);
        original.setAmountKopecks(1_000L);
        original.setManualActualAccountingMode(ContractorAllocationMode.SHADOW);
        original.setManualActualOriginalCashDestinationKind(ContractorCashDestinationKind.OWNER);
        original.setManualActualOriginalRecipientType(ContractorRecipientType.OWNER);
        original.setManualActualOriginalRecipientNameSnapshot("Владелец");
        original.setManualActualCashDestinationKind(ContractorCashDestinationKind.CONTRACTOR_PROFILE);
        original.setManualActualRecipientType(ContractorRecipientType.MANAGER);
        original.setManualActualRecipientProfileId(manager.getId());
        original.setManualActualRecipientUserId(22L);
        original.setManualActualRecipientNameSnapshot("Мария Менеджер");
        original.setManualActualReason("Клиент перевёл менеджеру");
        original.setManualActualActor("manager@example.ru");
        original.setManualActualRecipientFrozenAt(EFFECTIVE_AT.minusMinutes(1));
        PaymentLink evidence = new PaymentLink();
        evidence.setId(600L);
        evidence.setAmountKopecks(1_000L);
        evidence.setPaidAt(EFFECTIVE_AT);

        ContractorActualPaymentAttribution row = service.recordPaymentLinkFinalAttribution(
                order, original, evidence
        );

        assertThat(row.getAccountingMode()).isEqualTo(ContractorAllocationMode.SHADOW);
        assertThat(row.getActualRecipientProfileId()).isEqualTo(manager.getId());
        assertThat(actualAllocations()).singleElement()
                .extracting(ContractorPaymentAllocation::getStatus)
                .isEqualTo(ContractorAllocationStatus.SIMULATED_PAID);

        clearInvocations(allocationRepository, accountingService);
        service.requireCompletedPaymentReplay(
                original,
                1_000L,
                "MANAGER:" + manager.getId(),
                ContractorRecipientType.MANAGER,
                manager.getId(),
                "Клиент перевёл менеджеру",
                null
        );
        verify(allocationRepository, never()).saveAndFlush(any());
        verifyNoInteractions(accountingService);
        assertThatThrownBy(() -> service.requireCompletedPaymentReplay(
                original,
                1_000L,
                "OWNER",
                ContractorRecipientType.OWNER,
                null,
                "Клиент перевёл менеджеру",
                null
        )).isInstanceOf(ResponseStatusException.class);

        ReflectionTestUtils.setField(
                row, "accountingMode", ContractorAllocationMode.LIVE);
        assertThatThrownBy(() -> service.requireCompletedPaymentReplay(
                original,
                1_000L,
                "MANAGER:" + manager.getId(),
                ContractorRecipientType.MANAGER,
                manager.getId(),
                "Клиент перевёл менеджеру",
                null
        )).isInstanceOf(ResponseStatusException.class)
                .satisfies(failure -> assertThat(
                        ((ResponseStatusException) failure).getStatusCode().value())
                        .isEqualTo(409))
                .hasMessageContaining("Режим учёта");
    }

    @Test
    void externalTaskFinalizesAuditsAndReplaysWithoutFakeOwnerIdentity() {
        Order order = new Order();
        order.setId(ORDER_ID);
        PaymentLink original = new PaymentLink();
        original.setId(SOURCE_ID);
        original.setOrder(order);
        original.setAmountKopecks(2_500L);
        original.setManualActualAccountingMode(ContractorAllocationMode.LIVE);
        original.setManualActualOriginalCashDestinationKind(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        original.setManualActualOriginalTaskId(16L);
        original.setManualActualOriginalTaskGeneration(3L);
        original.setManualActualOriginalTaskTargetKind(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        original.setManualActualOriginalRecipientNameSnapshot("Наталья");
        original.setManualActualCashDestinationKind(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        original.setManualActualTaskId(16L);
        original.setManualActualTaskGeneration(3L);
        original.setManualActualTaskTargetKind(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        original.setManualActualRecipientNameSnapshot("Наталья");
        original.setManualActualReason("Клиент оплатил внешнему получателю задания");
        original.setManualActualActor("manager@example.ru");
        original.setManualActualRecipientFrozenAt(EFFECTIVE_AT.minusMinutes(1));
        PaymentLink evidence = new PaymentLink();
        evidence.setId(601L);
        evidence.setAmountKopecks(2_500L);
        evidence.setPaidAt(EFFECTIVE_AT);

        ContractorActualPaymentAttribution row = service.recordPaymentLinkFinalAttribution(
                order, original, evidence);

        assertThat(row.getOriginalRecipientType()).isNull();
        assertThat(row.getActualRecipientType()).isNull();
        assertThat(row.getActualCashDestinationKind())
                .isEqualTo(ContractorCashDestinationKind.MANUAL_PAYMENT_TASK);
        assertThat(row.getActualManualPaymentTaskId()).isEqualTo(16L);
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                anyString(), anyString(), any(), any(), any(), any(), any(), anyString());

        service.requireCompletedPaymentReplay(
                original, 2_500L, "TASK:16:3", null, null,
                "Клиент оплатил внешнему получателю задания", null);
        assertThat(actualAllocations()).isEmpty();
    }

    @Test
    void shadowRedirectIntoProfileTaskCreatesTaskTaggedActualAllocation() {
        when(accountingPhaseService.lockCurrent()).thenReturn(ContractorAllocationMode.SHADOW);
        available.put(specialist.getId(), 1_000L);
        ContractorActualPaymentRecipientCommand taskCommand =
                new ContractorActualPaymentRecipientCommand(
                        "PAYMENT_LINK:" + SOURCE_ID,
                        ContractorRecipientType.SPECIALIST,
                        specialist.getId(),
                        1_000L,
                        "Анна Специалист",
                        "TASK:91:4",
                        ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        91L,
                        4L,
                        ManualPaymentTaskAccountingTargetKind.SPECIALIST
                );

        ContractorActualPaymentAttribution row = service.recordFinalAttributions(
                source(null, null, ContractorRecipientType.OWNER, null),
                List.of(taskCommand)
        ).getFirst();

        assertThat(row.getActualManualPaymentTaskId()).isEqualTo(91L);
        assertThat(actualAllocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.getMode()).isEqualTo(ContractorAllocationMode.SHADOW);
            assertThat(allocation.getManualPaymentTaskId()).isEqualTo(91L);
            assertThat(allocation.getRecipientProfile().getId()).isEqualTo(specialist.getId());
            assertThat(allocation.getStatus()).isEqualTo(ContractorAllocationStatus.SIMULATED_PAID);
        });
    }

    private ContractorActualPaymentAttribution record(
            Long originalAllocationId,
            Long clientFacingAllocationId,
            ContractorRecipientType originalType,
            ContractorPaymentProfile originalProfile,
            ContractorRecipientType actualType,
            ContractorPaymentProfile actualProfile
    ) {
        return service.recordFinalAttributions(
                source(originalAllocationId, clientFacingAllocationId, originalType, originalProfile),
                List.of(command(actualType, actualProfile))
        ).getFirst();
    }

    private ContractorActualPaymentSource source(
            Long originalAllocationId,
            Long clientFacingAllocationId,
            ContractorRecipientType originalType,
            ContractorPaymentProfile originalProfile
    ) {
        return new ContractorActualPaymentSource(
                ContractorActualPaymentSourceKind.PAYMENT_LINK,
                SOURCE_ID,
                30L,
                ORDER_ID,
                null,
                originalAllocationId,
                clientFacingAllocationId,
                originalType,
                originalProfile == null ? null : originalProfile.getId(),
                originalProfile == null ? "Владелец" : originalProfile.getUser().getFio(),
                31L,
                32L,
                EFFECTIVE_AT,
                "Клиент перевёл по другим реквизитам",
                "payment-link-evidence:30",
                "https://example.test/receipt/30",
                "manager"
        );
    }

    private ContractorActualPaymentRecipientCommand command(
            ContractorRecipientType type,
            ContractorPaymentProfile profile
    ) {
        return new ContractorActualPaymentRecipientCommand(
                "PAYMENT_LINK:" + SOURCE_ID,
                type,
                profile == null ? null : profile.getId(),
                1_000L,
                profile == null ? "Владелец" : profile.getUser().getFio()
        );
    }

    private LinkedHashMap<String, ManualCardPaymentRecipientResponse> aliases(
            ManualPaymentTaskRouteSnapshot task,
            ManualCardPaymentRecipientResponse alias,
            String aliasMapKey
    ) {
        LinkedHashMap<String, ManualCardPaymentRecipientResponse> values = new LinkedHashMap<>();
        values.put(task.candidateKey(), new ManualCardPaymentRecipientResponse(
                alias.recipientType(), task.accountingTargetProfileId(), alias.recipientUserId(),
                "Получатель задания", 0L, 0L, true,
                task.candidateKey(), ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                task.taskId(), task.taskGeneration(), task.accountingTargetKind(),
                "Держатель карты", "Получатель учёта", "Зачесть в задание"));
        values.put(aliasMapKey, alias);
        return values;
    }

    private ManualPaymentTaskRouteSnapshot taskSnapshot(
            ManualPaymentTaskAccountingTargetKind kind,
            Long profileId
    ) {
        return new ManualPaymentTaskRouteSnapshot(
                91L, 4L,
                new ManualPaymentTaskSourceRef(
                        ManualPaymentTaskLedgerSourceKind.PAYMENT_LINK, 10L, "generation"),
                "TASK:91:4", kind, profileId, "Получатель учёта",
                ManualPaymentType.MOBILE_BANK, "+79990000000", "Держатель карты",
                null, null, 10_000L, LocalDateTime.now(), "admin");
    }

    private PaymentLink issuedTaskLink(Order order, ManualPaymentTaskRouteSnapshot snapshot) {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(snapshot.taskId());
        PaymentLink link = new PaymentLink();
        link.setId(SOURCE_ID);
        link.setOrder(order);
        link.setAmountKopecks(1_000L);
        link.setManualSource(ManualPaymentSource.MANUAL_TASK);
        link.setManualPaymentTask(task);
        link.setManualTaskSourceGeneration(snapshot.source().sourceGeneration());
        link.setManualTaskGeneration(snapshot.taskGeneration());
        link.setManualActualAccountingMode(ContractorAllocationMode.SHADOW);
        return link;
    }

    private void stubTaskCandidate(
            PaymentLink link,
            ManualPaymentTaskRouteSnapshot snapshot
    ) {
        ContractorRecipientType type = switch (snapshot.accountingTargetKind()) {
            case OWNER -> ContractorRecipientType.OWNER;
            case SPECIALIST -> ContractorRecipientType.SPECIALIST;
            case MANAGER -> ContractorRecipientType.MANAGER;
            case EXTERNAL_TASK, UNRESOLVED -> null;
        };
        when(taskReceiptIntegrationService.candidate(link)).thenReturn(Optional.of(snapshot));
        when(taskReceiptIntegrationService.destination(snapshot)).thenReturn(
                new ManualPaymentTaskReceiptIntegrationService.Destination(
                        snapshot.candidateKey(),
                        ContractorCashDestinationKind.MANUAL_PAYMENT_TASK,
                        type,
                        snapshot.accountingTargetProfileId(),
                        snapshot.taskId(),
                        snapshot.taskGeneration(),
                        snapshot.accountingTargetKind(),
                        snapshot.bankRecipientName(),
                        snapshot.accountingTargetLabel()
                ));
    }

    private ContractorPaymentProfile profile(Long id, Long userId, ContractorRole role, String name) {
        User user = new User();
        user.setId(userId);
        user.setFio(name);
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setRole(role);
        profile.setRecipientName(name);
        return profile;
    }

    private ContractorPaymentAllocation sourceAllocation(
            Long id,
            ContractorAllocationMode mode,
            ContractorPaymentProfile profile,
            ContractorAllocationStatus status
    ) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(id);
        allocation.setMode(mode);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(SOURCE_ID);
        allocation.setAttemptNo(1);
        allocation.setOrderId(ORDER_ID);
        allocation.setRecipientType(profile.getRole() == ContractorRole.SPECIALIST
                ? ContractorRecipientType.SPECIALIST : ContractorRecipientType.MANAGER);
        allocation.setRecipientProfile(profile);
        allocation.setRecipientUserId(profile.getUser().getId());
        allocation.setAmountKopecks(1_000L);
        allocation.setStatus(status);
        allocations.put(id, allocation);
        return allocation;
    }

    private ContractorPaymentAllocation ownerAllocation(Long id, ContractorAllocationMode mode) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(id);
        allocation.setMode(mode);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(SOURCE_ID);
        allocation.setAttemptNo(1);
        allocation.setOrderId(ORDER_ID);
        allocation.setRecipientType(ContractorRecipientType.OWNER);
        allocation.setAmountKopecks(1_000L);
        allocation.setStatus(ContractorAllocationStatus.OWNER_FALLBACK);
        allocations.put(id, allocation);
        return allocation;
    }

    private List<ContractorPaymentAllocation> actualAllocations() {
        return allocations.values().stream()
                .filter(value -> value.getSourceType() == ContractorAllocationSourceType.ACTUAL_PAYMENT)
                .sorted(Comparator.comparing(ContractorPaymentAllocation::getId))
                .toList();
    }

    private List<Long> latestSourceIds(String sourceType, Long sourceId) {
        Map<ContractorAllocationMode, ContractorPaymentAllocation> latest = new LinkedHashMap<>();
        allocations.values().stream()
                .filter(value -> value.getSourceType().name().equals(sourceType))
                .filter(value -> value.getSourceId().equals(sourceId))
                .forEach(value -> latest.merge(value.getMode(), value, (left, right) ->
                        left.getAttemptNo() > right.getAttemptNo()
                                || (left.getAttemptNo() == right.getAttemptNo() && left.getId() > right.getId())
                                ? left : right));
        return latest.values().stream().map(ContractorPaymentAllocation::getId).sorted().toList();
    }

    private Optional<Long> latestId(String mode, String sourceType, Long sourceId) {
        return allocations.values().stream()
                .filter(value -> value.getMode().name().equals(mode))
                .filter(value -> value.getSourceType().name().equals(sourceType))
                .filter(value -> value.getSourceId().equals(sourceId))
                .max(Comparator.comparing(ContractorPaymentAllocation::getAttemptNo)
                        .thenComparing(ContractorPaymentAllocation::getId))
                .map(ContractorPaymentAllocation::getId);
    }

    private String eventKey(Long allocationId, String reference) {
        return allocationId + "|" + reference;
    }
}
