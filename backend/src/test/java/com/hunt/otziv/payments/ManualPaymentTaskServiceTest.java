package com.hunt.otziv.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentAccountingPhaseService;
import com.hunt.otziv.payments.dto.CreateManualPaymentTaskRequest;
import com.hunt.otziv.payments.dto.ManualPaymentTaskBalance;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskRequest;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskAccountingTargetKind;
import com.hunt.otziv.payments.model.ManualPaymentTaskCreationRequest;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.repository.ManualPaymentTaskCreationRequestRepository;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.ManualPaymentTaskAccountingTargetPolicy;
import com.hunt.otziv.payments.service.ManualPaymentTaskAccountingTargetPolicy.TargetResolution;
import com.hunt.otziv.payments.service.ManualPaymentTaskLedgerService;
import com.hunt.otziv.payments.service.ManualPaymentRecipientMonthlySummaryService;
import com.hunt.otziv.payments.service.ManualPaymentTaskReceiptIntegrationService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskServiceTest {

    @Mock
    private ManualPaymentTaskRepository taskRepository;
    @Mock
    private ManualPaymentTaskCreationRequestRepository taskCreationRequestRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private PaymentProfileService paymentProfileService;
    @Mock
    private ManualPaymentTaskLedgerService ledgerService;
    @Mock
    private ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;
    @Mock
    private ManualPaymentTaskAccountingTargetPolicy accountingTargetPolicy;
    @Mock
    private ManualPaymentTaskContractorCapacityService contractorCapacityService;
    @Mock
    private ContractorPaymentAccountingPhaseService contractorPaymentAccountingPhaseService;
    @Mock
    private ManualPaymentRecipientMonthlySummaryService recipientMonthlySummaryService;

    private ManualPaymentTaskService service;

    @BeforeEach
    void setUp() {
        service = new ManualPaymentTaskService(
                taskRepository,
                taskCreationRequestRepository,
                paymentLinkRepository,
                commonInvoiceRepository,
                managerRepository,
                paymentProfileService,
                ledgerService,
                taskReceiptIntegrationService,
                accountingTargetPolicy,
                contractorCapacityService,
                contractorPaymentAccountingPhaseService,
                recipientMonthlySummaryService
        );
        org.mockito.Mockito.lenient().when(taskRepository.save(any(ManualPaymentTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(ledgerService.balance(71L))
                .thenReturn(com.hunt.otziv.payments.dto.ManualPaymentTaskBalance.empty(false));
        org.mockito.Mockito.lenient().when(
                        taskReceiptIntegrationService.lockLegacySourcesThenAccountingMode(71L))
                .thenReturn(new ManualPaymentTaskReceiptIntegrationService.LegacySourceLocks(
                        List.of(), ContractorAllocationMode.SHADOW));
        org.mockito.Mockito.lenient().when(accountingTargetPolicy.resolveForManagement(
                        org.mockito.ArgumentMatchers.eq(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name()),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(100_000L),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.eq(71L),
                        org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new TargetResolution(
                        ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                        null,
                        "Получатель задания",
                        0,
                        0,
                        false,
                        false
                ));
    }

    @Test
    void creationOperationKeyReturnsExactReplayAndRejectsChangedPayload() {
        com.hunt.otziv.u_users.model.Manager manager =
                org.mockito.Mockito.mock(com.hunt.otziv.u_users.model.Manager.class);
        com.hunt.otziv.u_users.model.User user =
                org.mockito.Mockito.mock(com.hunt.otziv.u_users.model.User.class);
        com.hunt.otziv.payments.model.PaymentProfile bankProfile =
                org.mockito.Mockito.mock(com.hunt.otziv.payments.model.PaymentProfile.class);
        when(managerRepository.findByIdWithPaymentProfile(5L)).thenReturn(Optional.of(manager));
        when(manager.getId()).thenReturn(5L);
        when(manager.getUser()).thenReturn(user);
        when(user.getFio()).thenReturn("Менеджер");
        when(user.getUsername()).thenReturn("manager");
        when(paymentProfileService.lockManagerForRouting(manager)).thenReturn(manager);
        when(paymentProfileService.selectForManager(manager)).thenReturn(bankProfile);
        when(paymentProfileService.lockForRouting(bankProfile)).thenReturn(bankProfile);
        when(bankProfile.getId()).thenReturn(9L);
        when(bankProfile.getName()).thenReturn("Основные реквизиты");
        when(accountingTargetPolicy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name(),
                null,
                100_000L,
                false,
                null
        )).thenReturn(new TargetResolution(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK,
                null,
                "Внешний получатель",
                0,
                0,
                false,
                false
        ));
        ManualPaymentTaskCreationRequest creation = new ManualPaymentTaskCreationRequest();
        when(taskCreationRequestRepository.insertIfAbsent(
                org.mockito.ArgumentMatchers.eq("task-create-1"),
                org.mockito.ArgumentMatchers.anyString()
        )).thenAnswer(invocation -> {
            if (creation.getPayloadHash() == null) {
                creation.setOperationKey("task-create-1");
                creation.setPayloadHash(invocation.getArgument(1));
            }
            return 1;
        });
        when(taskCreationRequestRepository.findByOperationKeyForUpdate("task-create-1"))
                .thenReturn(Optional.of(creation));
        ManualPaymentTask[] savedTask = new ManualPaymentTask[1];
        when(taskRepository.save(any(ManualPaymentTask.class))).thenAnswer(invocation -> {
            ManualPaymentTask value = invocation.getArgument(0);
            value.setId(71L);
            savedTask[0] = value;
            return value;
        });
        when(taskRepository.findByIdWithDetails(71L)).thenAnswer(ignored -> Optional.of(savedTask[0]));
        when(ledgerService.balance(71L)).thenReturn(ManualPaymentTaskBalance.empty(false));
        when(contractorPaymentAccountingPhaseService.current()).thenReturn(ContractorAllocationMode.SHADOW);
        CreateManualPaymentTaskRequest request = new CreateManualPaymentTaskRequest(
                5L,
                ManualPaymentType.MOBILE_BANK.name(),
                "+79990000000",
                "Наталья",
                "",
                "Перейти к оплате",
                100_000L,
                "Тест",
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name(),
                null,
                false,
                "task-create-1"
        );

        var first = service.createManagementTask(request, "admin");
        var replay = service.createManagementTask(request, "admin");
        CreateManualPaymentTaskRequest changed = new CreateManualPaymentTaskRequest(
                5L,
                ManualPaymentType.MOBILE_BANK.name(),
                "+79990000000",
                "Наталья",
                "",
                "Перейти к оплате",
                200_000L,
                "Тест",
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name(),
                null,
                false,
                "task-create-1"
        );
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class, () ->
                service.createManagementTask(changed, "admin"));

        assertEquals(71L, first.id());
        assertEquals(71L, replay.id());
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verify(taskRepository, org.mockito.Mockito.times(1)).save(any(ManualPaymentTask.class));
        verify(contractorPaymentAccountingPhaseService).lockCurrent();
        verify(paymentProfileService).lockManagerForRouting(manager);
        verify(paymentProfileService).lockForRouting(bankProfile);
        InOrder creationOrder = inOrder(
                contractorPaymentAccountingPhaseService,
                accountingTargetPolicy,
                taskRepository
        );
        creationOrder.verify(contractorPaymentAccountingPhaseService).lockCurrent();
        creationOrder.verify(accountingTargetPolicy).resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name(),
                null,
                100_000L,
                false,
                null
        );
        creationOrder.verify(taskRepository).save(any(ManualPaymentTask.class));
    }

    @Test
    void newTaskFailsClosedWhenAssignedManagerIsNotEligible() {
        com.hunt.otziv.u_users.model.Manager manager =
                org.mockito.Mockito.mock(com.hunt.otziv.u_users.model.Manager.class);
        when(managerRepository.findByIdWithPaymentProfile(5L)).thenReturn(Optional.of(manager));
        when(manager.getId()).thenReturn(5L);
        ManualPaymentTaskCreationRequest creation = new ManualPaymentTaskCreationRequest();
        when(taskCreationRequestRepository.insertIfAbsent(
                org.mockito.ArgumentMatchers.eq("task-create-inactive"),
                org.mockito.ArgumentMatchers.anyString()
        )).thenAnswer(invocation -> {
            creation.setOperationKey("task-create-inactive");
            creation.setPayloadHash(invocation.getArgument(1));
            return 1;
        });
        when(taskCreationRequestRepository.findByOperationKeyForUpdate("task-create-inactive"))
                .thenReturn(Optional.of(creation));
        when(paymentProfileService.lockManagerForRouting(manager)).thenThrow(
                new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Для создания нового платежа назначьте активного менеджера с ролью менеджера"
                )
        );
        CreateManualPaymentTaskRequest request = new CreateManualPaymentTaskRequest(
                5L,
                ManualPaymentType.MOBILE_BANK.name(),
                "+79990000000",
                "Наталья",
                "",
                "Перейти к оплате",
                100_000L,
                "Тест",
                ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK.name(),
                null,
                false,
                "task-create-inactive"
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.createManagementTask(request, "admin")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(taskRepository, never()).save(any(ManualPaymentTask.class));
        verify(contractorPaymentAccountingPhaseService, never()).lockCurrent();
        org.mockito.Mockito.verifyNoInteractions(accountingTargetPolicy);
    }

    @Test
    void legacyTaskUpdatePreservesQuarantinedRawUrlAndKeepsTaskUnroutable() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));

        var response = service.updateManagementTask(
                71L,
                new UpdateManualPaymentTaskRequest(
                        ManualPaymentType.EXTERNAL_LINK.name(),
                        "",
                        "Получатель",
                        ManualPaymentType.DEFAULT_EXTERNAL_PAYMENT_URL,
                        "Оплатить",
                        100_000L,
                        "legacy edit"
                ),
                "admin"
        );

        assertEquals("javascript:legacy-recipient()", task.getManualPaymentUrl());
        assertEquals("", response.manualPaymentUrl());
        assertFalse(response.routable());
    }

    @Test
    void explicitTaskUpdateReplacesQuarantinedRawUrlAndRestoresRouting() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));

        var response = service.updateManagementTask(
                71L,
                new UpdateManualPaymentTaskRequest(
                        ManualPaymentType.EXTERNAL_LINK.name(),
                        "",
                        "Получатель",
                        "https://pay.example/replacement",
                        "Оплатить",
                        100_000L,
                        "current edit",
                        true,
                        null,
                        null,
                        false,
                        1L
                ),
                "admin"
        );

        assertEquals("https://pay.example/replacement", task.getManualPaymentUrl());
        assertEquals("https://pay.example/replacement", response.manualPaymentUrl());
        assertTrue(response.routable());
    }

    @Test
    void migratedBaselineCountsTowardServiceCompletionThreshold() {
        ManualPaymentTask task = unsafeExternalTask();
        task.setTargetAmountKopecks(100_000L);
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                0, 100_000L, 100_000L, 0, 0, 0, 90_000L, 0, 1, true));

        service.completeIfConfirmedTargetReached(task);

        assertEquals(ManualPaymentTaskStatus.COMPLETED, task.getStatus());
        verify(taskRepository).save(task);
    }

    @Test
    void destinationChangeWithPendingMoneyIsRejectedAfterSourcePhaseThenTaskLocks() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                10_000L, 0, 10_000L, 0, 0, 0, 0, 1, 0, false));
        when(accountingTargetPolicy.resolveForManagement(
                ManualPaymentTaskAccountingTargetKind.OWNER.name(), null, 100_000L,
                false, 71L, false)).thenReturn(new TargetResolution(
                ManualPaymentTaskAccountingTargetKind.OWNER, null, "Владелец",
                0, 0, false, false));
        UpdateManualPaymentTaskRequest request = new UpdateManualPaymentTaskRequest(
                ManualPaymentType.EXTERNAL_LINK.name(), "", "Получатель",
                "https://pay.example/current", "Оплатить", 100_000L, "change target",
                true, ManualPaymentTaskAccountingTargetKind.OWNER.name(), null, false, 1L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.updateManagementTask(71L, request, "admin"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(taskRepository, never()).save(task);
        InOrder locks = inOrder(taskReceiptIntegrationService, taskRepository);
        locks.verify(taskReceiptIntegrationService).lockLegacySourcesThenAccountingMode(71L);
        locks.verify(taskRepository).findByIdWithDetailsForUpdate(71L);
    }

    @Test
    void canceledStatusIsRejectedWhileTaskHasPendingReservation() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                10_000L, 0, 10_000L, 0, 0, 0, 0, 1, 0, false));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.updateManagementTaskStatus(71L, "CANCELED", "admin"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ManualPaymentTaskStatus.ACTIVE, task.getStatus());
        verify(taskRepository, never()).save(task);
    }

    @Test
    void completedStatusIsRejectedWhileTaskHasPendingReservation() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                10_000L, 100_000L, 110_000L, 0, 0, 0, 0, 1, 0, false));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.updateManagementTaskStatus(71L, "COMPLETED", "admin"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ManualPaymentTaskStatus.ACTIVE, task.getStatus());
        verify(taskRepository, never()).save(task);
    }

    @Test
    void completedStatusIsRejectedBeforeConfirmedTargetIsReached() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                0, 99_999L, 99_999L, 0, 0, 0, 0, 0, 0, false));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.updateManagementTaskStatus(71L, "COMPLETED", "admin"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ManualPaymentTaskStatus.ACTIVE, task.getStatus());
        verify(taskRepository, never()).save(task);
    }

    @Test
    void completedStatusAllowsReachedTargetEvenWhenAggregateNeedsLaterReview() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetailsForUpdate(71L)).thenReturn(Optional.of(task));
        when(ledgerService.balance(71L)).thenReturn(new ManualPaymentTaskBalance(
                0, 100_000L, 100_000L, 0, 0, 0, 100_000L, 0, 1, true));

        service.updateManagementTaskStatus(71L, "COMPLETED", "admin");

        assertEquals(ManualPaymentTaskStatus.COMPLETED, task.getStatus());
        verify(taskRepository).save(task);
    }

    private ManualPaymentTask unsafeExternalTask() {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(71L);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setGeneration(1);
        task.setAccountingTargetKind(ManualPaymentTaskAccountingTargetKind.EXTERNAL_TASK);
        task.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        task.setManualRecipientName("Получатель");
        task.setManualPaymentUrl("javascript:legacy-recipient()");
        task.setTargetAmountKopecks(100_000L);
        return task;
    }
}
