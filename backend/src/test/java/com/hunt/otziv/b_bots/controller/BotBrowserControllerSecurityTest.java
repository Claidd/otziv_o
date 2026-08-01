package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;

class BotBrowserControllerSecurityTest {

    @Test
    void everyBrowserOperationHasTheSameRoleGate() throws Exception {
        assertBrowserRoleGate(BotBrowserController.class.getMethod(
                "getBrowserMetadata",
                Long.class,
                Authentication.class
        ));
        assertBrowserRoleGate(BotBrowserController.class.getMethod(
                "openBrowser",
                Long.class,
                com.hunt.otziv.b_bots.dto.BrowserOpenRequest.class,
                Authentication.class
        ));
        assertBrowserRoleGate(BotBrowserController.class.getMethod(
                "closeBrowser",
                Long.class,
                Authentication.class
        ));
        assertBrowserRoleGate(BotBrowserController.class.getMethod(
                "heartbeatBrowserSession",
                Long.class,
                String.class,
                Authentication.class
        ));
        assertBrowserRoleGate(BotBrowserController.class.getMethod(
                "closeBrowserSession",
                Long.class,
                String.class,
                Authentication.class
        ));
    }

    private void assertBrowserRoleGate(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value())
                .isEqualTo("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')");
    }
}
