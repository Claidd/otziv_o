package com.hunt.otziv.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.payments.dto.UpdateManualPaymentTaskRequest;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentTaskStatus;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.repository.ManualPaymentTaskRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualPaymentTaskServiceTest {

    @Mock
    private ManualPaymentTaskRepository taskRepository;
    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private ManagerRepository managerRepository;
    @Mock
    private PaymentProfileService paymentProfileService;

    private ManualPaymentTaskService service;

    @BeforeEach
    void setUp() {
        service = new ManualPaymentTaskService(
                taskRepository,
                paymentLinkRepository,
                commonInvoiceRepository,
                managerRepository,
                paymentProfileService
        );
        when(taskRepository.save(any(ManualPaymentTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void legacyTaskUpdatePreservesQuarantinedRawUrlAndKeepsTaskUnroutable() {
        ManualPaymentTask task = unsafeExternalTask();
        when(taskRepository.findByIdWithDetails(71L)).thenReturn(Optional.of(task));

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
        when(taskRepository.findByIdWithDetails(71L)).thenReturn(Optional.of(task));

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
                        true
                ),
                "admin"
        );

        assertEquals("https://pay.example/replacement", task.getManualPaymentUrl());
        assertEquals("https://pay.example/replacement", response.manualPaymentUrl());
        assertTrue(response.routable());
    }

    private ManualPaymentTask unsafeExternalTask() {
        ManualPaymentTask task = new ManualPaymentTask();
        task.setId(71L);
        task.setStatus(ManualPaymentTaskStatus.ACTIVE);
        task.setManualPaymentType(ManualPaymentType.EXTERNAL_LINK);
        task.setManualRecipientName("Получатель");
        task.setManualPaymentUrl("javascript:legacy-recipient()");
        task.setTargetAmountKopecks(100_000L);
        return task;
    }
}
