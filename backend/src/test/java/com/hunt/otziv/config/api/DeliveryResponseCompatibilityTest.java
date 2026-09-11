package com.hunt.otziv.config.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hunt.otziv.client_messages.api.DeliveryOperation;
import com.hunt.otziv.common_billing.controller.CommonBillingAdminController;
import com.hunt.otziv.common_billing.dto.CommonInvoiceDetailsResponse;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.common_billing.service.CommonBillingPublicationApprovalFailureMarker;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.manager_control.controller.ApiManagerControlController;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.service.*;
import com.hunt.otziv.manager_daily_summary.service.ManagerSiteActivityService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeliveryResponseCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "SENDING", "RETRYABLE", "UNKNOWN", "FAILED"})
    void legacySuccessAlwaysRequiresConfirmation(String state) {
        var operation = new DeliveryOperation("fixture-operation", state, 1, null);
        assertThatThrownBy(() -> DeliveryResponseCompatibility.requireUnderstoodOutcome(null, operation))
                .isInstanceOf(CodedResponseStatusException.class);
        assertThatCode(() -> DeliveryResponseCompatibility.requireUnderstoodOutcome("queued-v1", operation))
                .doesNotThrowAnyException();
        assertThatCode(() -> DeliveryResponseCompatibility.requireUnderstoodOutcome(null,
                new DeliveryOperation("fixture-operation", "SENT", 1, null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> DeliveryResponseCompatibility.requireUnderstoodOutcome(null,
                new DeliveryOperation("fixture-operation", "SENT", 1, "finalization_required")))
                .isInstanceOf(CodedResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send", "remind", "send-client-message", "reply"})
    void commandRoutesDistinguishOldAndDeliveryAwareClients(String command) throws Exception {
        var billing = mock(CommonBillingService.class);
        var manager = mock(ManagerControlService.class);
        var delivery = new DeliveryOperation("fixture-operation", "QUEUED", 0, null);
        var invoice = mock(CommonInvoiceDetailsResponse.class);
        when(invoice.delivery()).thenReturn(delivery);
        when(billing.sendInvoice(7L, true)).thenReturn(invoice);
        when(billing.sendManualReminder(7L)).thenReturn(invoice);
        var card = mock(ManagerControlConcreteItemResponse.class);
        when(card.delivery()).thenReturn(delivery);
        when(manager.sendClientMessage(eq(7L), any(), any())).thenReturn(card);
        when(manager.replyToClientMessage(eq(7L), any(), any(), any())).thenReturn(card);
        var mvc = MockMvcBuilders.standaloneSetup(
                new CommonBillingAdminController(billing, mock(CommonBillingPublicationApprovalFailureMarker.class),
                        mock(ContractorPaymentTargetAccessPolicy.class), mock(ContractorActualPaymentAttributionFlowPolicy.class)),
                new ApiManagerControlController(manager, new PerformanceMetrics(new SimpleMeterRegistry()),
                        mock(ManagerQueueStateService.class), mock(ManagerSiteActivityService.class), mock(ManagerClientDeliveryStatus.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = command.equals("send") || command.equals("remind")
                ? "/api/common-billing/invoices/7/" + command
                : "/api/admin/manager-control/concrete-items/7/" + command;

        mvc.perform(post(path)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_CONFIRMATION_PENDING"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("сохранено в очереди")));
        // Each HTTP request enqueues exactly once; compatibility handling never calls a provider or retries.
        if (command.equals("send")) verify(billing).sendInvoice(7L, true);
        if (command.equals("remind")) verify(billing).sendManualReminder(7L);
        if (command.equals("send-client-message")) verify(manager).sendClientMessage(eq(7L), any(), any());
        if (command.equals("reply")) verify(manager).replyToClientMessage(eq(7L), any(), any(), any());
        mvc.perform(post(path).header(DeliveryResponseCompatibility.HEADER, "queued-v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delivery.status").value("QUEUED"));
    }
}
