package com.hunt.otziv.payments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hunt.otziv.admin.controller.ApiCabinetController;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.ManualPaymentRecipientMonthlySummaryResponse;
import com.hunt.otziv.payments.dto.ManualPaymentTaskAccountingTargetOption;
import com.hunt.otziv.payments.dto.ManualPaymentTaskResponse;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

class ManualPaymentTaskNoStoreControllerTest {

    @Test
    void adminTaskReadsAndMutationsDisableCaching() {
        ManualPaymentTaskService service = mock(ManualPaymentTaskService.class);
        ManualPaymentTaskResponse task = mock(ManualPaymentTaskResponse.class);
        ManualPaymentTaskAccountingTargetOption option = mock(ManualPaymentTaskAccountingTargetOption.class);
        ManualPaymentRecipientMonthlySummaryResponse summary = mock(ManualPaymentRecipientMonthlySummaryResponse.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("admin");
        when(service.managementTasks()).thenReturn(List.of(task));
        when(service.managementAccountingTargetOptions(null, null, null)).thenReturn(List.of(option));
        when(service.recipientMonthlySummary("2026-08")).thenReturn(summary);
        when(service.createManagementTask(null, "admin")).thenReturn(task);
        when(service.updateManagementTaskStatus(1L, null, "admin")).thenReturn(task);
        when(service.updateManagementTask(1L, null, "admin")).thenReturn(task);
        AdminPaymentController controller = new AdminPaymentController(
                mock(PaymentLinkService.class),
                mock(PaymentProfileService.class),
                mock(TbankRuntimeSettingsService.class),
                service,
                mock(ContractorPaymentTargetAccessPolicy.class)
        );

        assertNoStore(controller.manualPaymentTasks(), List.of(task));
        assertNoStore(controller.manualPaymentTaskAccountingTargets(null, null, null), List.of(option));
        assertNoStore(controller.manualRecipientMonthlySummary("2026-08"), summary);
        assertNoStore(controller.createManualPaymentTask(null, authentication), task);
        assertNoStore(controller.updateManualPaymentTaskStatus(1L, null, authentication), task);
        assertNoStore(controller.updateManualPaymentTask(1L, null, authentication), task);
    }

    @Test
    void managerTaskReadsAndMutationsDisableCaching() {
        ManualPaymentTaskService service = mock(ManualPaymentTaskService.class);
        UserService userService = mock(UserService.class);
        User user = mock(User.class);
        ManualPaymentTaskResponse task = mock(ManualPaymentTaskResponse.class);
        ManualPaymentTaskAccountingTargetOption option = mock(ManualPaymentTaskAccountingTargetOption.class);
        Principal principal = () -> "manager";
        when(user.getId()).thenReturn(7L);
        when(userService.findByUserName("manager")).thenReturn(Optional.of(user));
        when(service.managerTasks(7L)).thenReturn(List.of(task));
        when(service.managerAccountingTargetOptions(7L, null, null)).thenReturn(List.of(option));
        when(service.createManagerTask(7L, null, "manager")).thenReturn(task);
        when(service.updateManagerTaskStatus(7L, 1L, null, "manager")).thenReturn(task);
        when(service.updateManagerTask(7L, 1L, null, "manager")).thenReturn(task);
        ApiCabinetController controller = mock(ApiCabinetController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "manualPaymentTaskService", service);

        assertNoStore(controller.manualPaymentTasks(principal), List.of(task));
        assertNoStore(controller.manualPaymentTaskAccountingTargets(principal, null, null), List.of(option));
        assertNoStore(controller.createManualPaymentTask(principal, null), task);
        assertNoStore(controller.updateManualPaymentTaskStatus(principal, 1L, null), task);
        assertNoStore(controller.updateManualPaymentTask(principal, 1L, null), task);
    }

    private static void assertNoStore(ResponseEntity<?> response, Object expectedBody) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(expectedBody);
    }
}
