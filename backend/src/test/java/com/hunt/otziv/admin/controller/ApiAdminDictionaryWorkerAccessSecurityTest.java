package com.hunt.otziv.admin.controller;

import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiAdminDictionaryWorkerAccessSecurityTest {

    @Test
    void workerAccessSettingsEndpointsAreAdminOnly() throws Exception {
        assertAdminOnly(ApiAdminDictionaryController.class.getMethod("getWorkerCellularAccessSettings"));
        assertAdminOnly(ApiAdminDictionaryController.class.getMethod(
                "updateWorkerCellularAccessSettings",
                ApiAdminDictionaryController.WorkerCellularAccessSettingsRequest.class
        ));
    }

    @Test
    void administrativeBotDetailsDoNotExposePasswordsToWorkers() throws Exception {
        Method method = ApiAdminDictionaryController.class.getMethod(
                "getBot",
                Long.class,
                Authentication.class
        );
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'WORKER')", preAuthorize.value());
    }

    @Test
    void staleGlobalJwtCannotBypassFreshDatabaseRoleAndActiveCheck() {
        BotBrowserAccessService accessService = mock(BotBrowserAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        ApiAdminDictionaryController controller = mock(
                ApiAdminDictionaryController.class,
                CALLS_REAL_METHODS
        );
        ReflectionTestUtils.setField(controller, "botBrowserAccessService", accessService);
        ReflectionTestUtils.setField(controller, "botsRepository", botsRepository);
        Authentication staleAdmin = new TestingAuthenticationToken(
                "revoked-admin",
                "n/a",
                "ROLE_ADMIN"
        );
        ResponseStatusException denied = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ресурс не найден"
        );
        when(accessService.requireAccess(42L, staleAdmin)).thenThrow(denied);

        assertThatThrownBy(() -> controller.getBot(42L, staleAdmin)).isSameAs(denied);

        verifyNoInteractions(botsRepository);
    }

    private void assertAdminOnly(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", preAuthorize.value());
    }
}
