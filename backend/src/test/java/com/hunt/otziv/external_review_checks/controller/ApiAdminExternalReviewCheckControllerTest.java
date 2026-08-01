package com.hunt.otziv.external_review_checks.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.external_review_checks.dto.ExternalReviewCheckEnabledUpdateRequest;
import com.hunt.otziv.external_review_checks.dto.ExternalReviewCheckStatusResponse;
import com.hunt.otziv.external_review_checks.service.ExternalReviewCheckRuntimeSwitch;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

class ApiAdminExternalReviewCheckControllerTest {

    @Test
    void controllerIsRestrictedToAdminAndOwner() {
        PreAuthorize authorization = ApiAdminExternalReviewCheckController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
                .contains("ADMIN", "OWNER")
                .doesNotContain("MANAGER", "MARKETOLOG", "WORKER");
    }

    @Test
    void operatorUpdateReturnsAllThreeSwitchStates() {
        ExternalReviewCheckRuntimeSwitch runtimeSwitch = mock(ExternalReviewCheckRuntimeSwitch.class);
        when(runtimeSwitch.setOperatorEnabled(false)).thenReturn(
                new ExternalReviewCheckRuntimeSwitch.Status(false, true, false)
        );
        ApiAdminExternalReviewCheckController controller =
                new ApiAdminExternalReviewCheckController(runtimeSwitch);

        ExternalReviewCheckStatusResponse response = controller.setEnabled(
                new ExternalReviewCheckEnabledUpdateRequest(false)
        );

        verify(runtimeSwitch).setOperatorEnabled(false);
        assertThat(response.enabled()).isFalse();
        assertThat(response.hardEnabled()).isTrue();
        assertThat(response.operatorEnabled()).isFalse();
    }

    @Test
    void missingOperatorValueIsRejected() {
        ApiAdminExternalReviewCheckController controller =
                new ApiAdminExternalReviewCheckController(mock(ExternalReviewCheckRuntimeSwitch.class));

        assertThatThrownBy(() -> controller.setEnabled(
                new ExternalReviewCheckEnabledUpdateRequest(null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
