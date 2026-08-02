package com.hunt.otziv.reputationai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.reputationai.api.dto.ReputationAiEnabledUpdateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ReputationAiControllerSecurityContractTest {

    @Test
    void runtimeKillSwitchCanOnlyBeChangedByAdminOrOwner() throws Exception {
        Method method = ReputationAiController.class.getMethod(
                "setEnabled",
                ReputationAiEnabledUpdateRequest.class
        );

        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
                .contains("ADMIN", "OWNER")
                .doesNotContain("MANAGER", "MARKETOLOG", "WORKER");
    }
}
