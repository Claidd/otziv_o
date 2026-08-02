package com.hunt.otziv.admin.controller;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService;
import com.hunt.otziv.b_bots.services.BotCrudAccessService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
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
        BotCrudAccessService crudAccessService = mock(BotCrudAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        ApiAdminDictionaryController controller = mock(
                ApiAdminDictionaryController.class,
                CALLS_REAL_METHODS
        );
        ReflectionTestUtils.setField(controller, "botBrowserAccessService", accessService);
        ReflectionTestUtils.setField(controller, "botCrudAccessService", crudAccessService);
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
        when(crudAccessService.requireLockedGlobalAccess(42L, staleAdmin)).thenThrow(denied);

        assertThatThrownBy(() -> controller.getBot(42L, staleAdmin)).isSameAs(denied);

        verify(crudAccessService).requireLockedGlobalAccess(42L, staleAdmin);
        verifyNoInteractions(accessService);
        verifyNoInteractions(botsRepository);
    }

    @Test
    void modernBotCreateChecksFreshGlobalRoleBeforeValidationOrPersistence() {
        BotCrudAccessService crudAccessService = mock(BotCrudAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        ApiAdminDictionaryController controller = mock(
                ApiAdminDictionaryController.class,
                CALLS_REAL_METHODS
        );
        ReflectionTestUtils.setField(controller, "botCrudAccessService", crudAccessService);
        ReflectionTestUtils.setField(controller, "botsRepository", botsRepository);
        Authentication staleOwner = new TestingAuthenticationToken(
                "revoked-owner",
                "n/a",
                "ROLE_OWNER"
        );
        ResponseStatusException denied = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Ресурс не найден"
        );
        doThrow(denied).when(crudAccessService).requireGlobalAccess(staleOwner);

        assertThatThrownBy(() -> controller.createBot(null, staleOwner)).isSameAs(denied);

        verify(crudAccessService).requireGlobalAccess(staleOwner);
        verifyNoInteractions(botsRepository);
    }

    @Test
    void managerReceivesOnlyPasswordFreeBrowserMetadata() {
        BotBrowserAccessService accessService = mock(BotBrowserAccessService.class);
        BotCrudAccessService crudAccessService = mock(BotCrudAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        ApiAdminDictionaryController controller = mock(
                ApiAdminDictionaryController.class,
                CALLS_REAL_METHODS
        );
        ReflectionTestUtils.setField(controller, "botBrowserAccessService", accessService);
        ReflectionTestUtils.setField(controller, "botCrudAccessService", crudAccessService);
        ReflectionTestUtils.setField(controller, "botsRepository", botsRepository);
        Authentication manager = new TestingAuthenticationToken(
                "manager",
                "n/a",
                "ROLE_MANAGER"
        );
        when(accessService.requireAccess(42L, manager)).thenReturn(
                new BotBrowserAccessService.AuthorizedBot(42L, "79990001122", "Browser Bot")
        );

        ApiAdminDictionaryController.BotResponse response = controller.getBot(42L, manager);

        assertEquals(42L, response.id());
        assertEquals("79990001122", response.login());
        assertEquals("", response.password());
        verify(accessService).requireAccess(42L, manager);
        verifyNoInteractions(crudAccessService, botsRepository);
    }

    @Test
    void currentOwnerReceivesFullDetailsOnlyThroughLockedGlobalGuard() {
        BotBrowserAccessService accessService = mock(BotBrowserAccessService.class);
        BotCrudAccessService crudAccessService = mock(BotCrudAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        ApiAdminDictionaryController controller = mock(
                ApiAdminDictionaryController.class,
                CALLS_REAL_METHODS
        );
        ReflectionTestUtils.setField(controller, "botBrowserAccessService", accessService);
        ReflectionTestUtils.setField(controller, "botCrudAccessService", crudAccessService);
        ReflectionTestUtils.setField(controller, "botsRepository", botsRepository);
        Authentication owner = new TestingAuthenticationToken("owner", "n/a", "ROLE_OWNER");
        Bot bot = new Bot();
        bot.setId(42L);
        bot.setLogin("79990001122");
        bot.setPassword("secret");
        bot.setFio("Owned Bot");
        when(crudAccessService.requireLockedGlobalAccess(42L, owner)).thenReturn(
                new BotCrudAccessService.LockedCrudBot(bot, null, false)
        );

        ApiAdminDictionaryController.BotResponse response = controller.getBot(42L, owner);

        assertEquals("secret", response.password());
        verify(crudAccessService).requireLockedGlobalAccess(42L, owner);
        verifyNoInteractions(accessService, botsRepository);
    }

    private void assertAdminOnly(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", preAuthorize.value());
    }
}
