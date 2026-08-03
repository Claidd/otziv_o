package com.hunt.otziv.admin.controller;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.b_bots.repository.StatusBotRepository;
import com.hunt.otziv.b_bots.services.BotBrowserAccessService;
import com.hunt.otziv.b_bots.services.BotCrudAccessService;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.p_products.services.service.BotAssignmentService;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(crudAccessService.requireAccess(42L, staleAdmin)).thenThrow(denied);

        assertThatThrownBy(() -> controller.getBot(42L, staleAdmin)).isSameAs(denied);

        verify(crudAccessService).requireAccess(42L, staleAdmin);
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
        assertFalse(response.passwordPresent());
        verify(accessService).requireAccess(42L, manager);
        verifyNoInteractions(crudAccessService, botsRepository);
    }

    @Test
    void currentOwnerReceivesPasswordFreeProjectedDetailsThroughFreshGlobalGuard() throws Exception {
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
        BotsRepository.AdminBotRow row = mock(BotsRepository.AdminBotRow.class);
        when(row.getId()).thenReturn(42L);
        when(row.getLogin()).thenReturn("79990001122");
        when(row.getFio()).thenReturn("Owned Bot");
        when(row.getActive()).thenReturn(true);
        when(row.getCounter()).thenReturn(0);
        when(row.getPasswordPresent()).thenReturn(true);
        when(botsRepository.findAdminRowById(42L)).thenReturn(Optional.of(row));

        ApiAdminDictionaryController.BotResponse response = controller.getBot(42L, owner);

        assertTrue(response.passwordPresent());
        JsonNode serialized = new ObjectMapper().valueToTree(response);
        assertFalse(serialized.has("password"));
        verify(crudAccessService).requireAccess(42L, owner);
        verify(botsRepository).findAdminRowById(42L);
        verifyNoInteractions(accessService);
    }

    @Test
    void botEntityCannotLeakPasswordThroughGenericJsonOrToString() throws Exception {
        Bot bot = new Bot();
        bot.setId(42L);
        bot.setLogin("79990001122");
        bot.setPassword("entity-secret");

        JsonNode serialized = new ObjectMapper().valueToTree(bot);

        assertFalse(serialized.has("password"));
        assertFalse(serialized.toString().contains("entity-secret"));
        assertFalse(bot.toString().contains("entity-secret"));

        JsonNode dto = new ObjectMapper().valueToTree(
                BotDTO.builder().id(42L).login("79990001122").password("dto-secret").build()
        );
        assertFalse(dto.has("password"));
        assertFalse(dto.toString().contains("dto-secret"));
    }

    @Test
    void blankPasswordOnAdministrativeUpdatePreservesStoredSecretAndResponseDoesNotReturnIt() throws Exception {
        BotCrudAccessService crudAccessService = mock(BotCrudAccessService.class);
        BotsRepository botsRepository = mock(BotsRepository.class);
        WorkerRepository workerRepository = mock(WorkerRepository.class);
        StatusBotRepository statusBotRepository = mock(StatusBotRepository.class);
        CityRepository cityRepository = mock(CityRepository.class);
        BotAssignmentService botAssignmentService = mock(BotAssignmentService.class);
        ApiAdminDictionaryController controller = mock(ApiAdminDictionaryController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "botCrudAccessService", crudAccessService);
        ReflectionTestUtils.setField(controller, "botsRepository", botsRepository);
        ReflectionTestUtils.setField(controller, "workerRepository", workerRepository);
        ReflectionTestUtils.setField(controller, "statusBotRepository", statusBotRepository);
        ReflectionTestUtils.setField(controller, "cityRepository", cityRepository);
        ReflectionTestUtils.setField(controller, "botAssignmentService", botAssignmentService);

        Authentication owner = new TestingAuthenticationToken("owner", "n/a", "ROLE_OWNER");
        Worker worker = new Worker();
        worker.setId(5L);
        StatusBot status = new StatusBot();
        status.setId(6L);
        status.setBotStatusTitle("Новый");
        City city = new City();
        city.setId(7L);
        city.setTitle("Иркутск");
        Bot bot = new Bot();
        bot.setId(42L);
        bot.setLogin("79990001122");
        bot.setPassword("stored-secret");
        bot.setFio("Owned Bot");
        bot.setWorker(worker);
        bot.setStatus(status);
        bot.setBotCity(city);

        when(crudAccessService.requireLockedGlobalAccess(42L, owner)).thenReturn(
                new BotCrudAccessService.LockedCrudBot(bot, 5L, false)
        );
        when(botsRepository.findByLogin("79990001122")).thenReturn(Optional.of(bot));
        when(workerRepository.findById(5L)).thenReturn(Optional.of(worker));
        when(statusBotRepository.findById(6L)).thenReturn(Optional.of(status));
        when(cityRepository.findById(7L)).thenReturn(city);
        when(botsRepository.save(bot)).thenReturn(bot);

        ApiAdminDictionaryController.BotResponse response = controller.updateBot(
                42L,
                new ApiAdminDictionaryController.BotRequest(
                        "79990001122", "  ", "Owned Bot", 5L, 7L, 6L, true, 0
                ),
                owner
        );

        assertEquals("stored-secret", bot.getPassword());
        assertTrue(response.passwordPresent());
        JsonNode serialized = new ObjectMapper().valueToTree(response);
        assertFalse(serialized.has("password"));
        assertFalse(serialized.toString().contains("stored-secret"));
    }

    private void assertAdminOnly(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", preAuthorize.value());
    }
}
