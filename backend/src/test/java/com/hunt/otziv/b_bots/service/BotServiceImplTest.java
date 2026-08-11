package com.hunt.otziv.b_bots.service;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.model.StatusBot;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.r_review.bot.service.ReviewBotCooldownService;
import com.hunt.otziv.u_users.service.UserService;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BotServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private StatusBotService statusBotService;

    @Mock
    private BotsRepository botsRepository;

    @Mock
    private WorkerService workerService;

    @Mock
    private BusinessAuditService businessAuditService;

    @Mock
    private ReviewBotCooldownService botCooldownService;

    @Mock
    private ReviewAccountPoolAlertScheduler accountPoolAlertScheduler;

    @Mock
    private BotCrudAccessService botCrudAccessService;

    @Test
    void createRechecksAndLocksFreshRoleInsideTheWriteCommandBeforeSave() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "creator",
                "n/a",
                "ROLE_WORKER"
        );
        authentication.setAuthenticated(true);
        Worker owner = worker(7L);
        User user = new User();
        user.setId(5L);
        user.setWorkers(Set.of(owner));
        StatusBot ready = new StatusBot();
        ready.setBotStatusTitle("Новый");
        BotDTO request = new BotDTO();
        request.setLogin("79000000000");
        request.setPassword("secret");
        request.setFio("Новый бот");

        when(userService.findByUserNameWithAssignments("creator")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(5L)).thenReturn(owner);
        when(statusBotService.findByTitle("Новый")).thenReturn(ready);

        assertTrue(service.createBot(request, authentication));

        var ordered = inOrder(botCrudAccessService, botsRepository);
        ordered.verify(botCrudAccessService).requireCreateAccess(authentication);
        ordered.verify(botsRepository).save(any(Bot.class));
    }

    @Test
    void createRejectsBlankPasswordBeforePersisting() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        BotDTO request = new BotDTO();
        request.setPassword(" \t ");

        assertThrows(IllegalArgumentException.class, () -> service.createBot(request, authentication));

        verify(botCrudAccessService).requireCreateAccess(authentication);
        verify(botsRepository, never()).save(any(Bot.class));
    }

    @Test
    void claimNewAccountFromOwnCityUsesOnlyReadyActiveAccountInTargetCity() {
        BotServiceImpl service = service();
        City city = city(320L, "Город 320");
        Bot excluded = bot(10L, "Впиши Имя Фамилию", true, "Новый");
        Bot inactive = bot(11L, "Впиши Имя Фамилию", false, "Новый");
        Bot wrongStatus = bot(12L, "Впиши Имя Фамилию", true, "В работе");
        Bot selected = bot(13L, "Впиши Имя Фамилию", true, "Новый");

        when(botsRepository.findBotsByFioAndCity("Впиши Имя Фамилию", 320L))
                .thenReturn(List.of(excluded, inactive, wrongStatus, selected));
        when(botCooldownService.isAvailableForAssignment(wrongStatus)).thenReturn(true);
        when(botCooldownService.isAvailableForAssignment(selected)).thenReturn(true);

        Optional<Bot> result = service.claimNewAccountFromOwnCity(city, Set.of(10L));

        assertTrue(result.isPresent());
        assertEquals(13L, result.get().getId());
        assertEquals(320L, result.get().getBotCity().getId());
        verify(botsRepository, never()).save(selected);
    }

    @Test
    void legacyUpdateUsesCanonicalPathIdAndLocksBeforeMutation() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        Worker worker = worker(7L);
        Bot existing = editableBot(42L, worker);
        BotDTO request = editableDto(null, worker);
        request.setBotCity(null); // the legacy edit form does not submit a city field
        request.setFio("Новое ФИО");
        BotCrudAccessService.LockedCrudBot lockedBot =
                new BotCrudAccessService.LockedCrudBot(existing, 7L, true);
        when(botCrudAccessService.requireLockedAccess(42L, authentication)).thenReturn(lockedBot);

        assertTrue(service.updateBot(request, 42L, authentication));

        assertEquals("Новое ФИО", existing.getFio());
        assertEquals(320L, existing.getBotCity().getId());
        verify(botCrudAccessService).requireLockedAccess(42L, authentication);
        verify(botCrudAccessService).requireUpdateOwnership(lockedBot, request);
        verify(botsRepository).save(existing);
    }

    @Test
    void legacyUpdateTreatsBlankPasswordAsKeepExisting() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        Worker worker = worker(7L);
        Bot existing = editableBot(42L, worker);
        BotDTO request = editableDto(null, worker);
        request.setPassword(" \t ");
        request.setFio("Новое ФИО");
        BotCrudAccessService.LockedCrudBot lockedBot =
                new BotCrudAccessService.LockedCrudBot(existing, 7L, true);
        when(botCrudAccessService.requireLockedAccess(42L, authentication)).thenReturn(lockedBot);

        assertTrue(service.updateBot(request, 42L, authentication));

        assertEquals("password-42", existing.getPassword());
        verify(botsRepository).save(existing);
    }

    @Test
    void genericEditDtoNeverCarriesStoredPassword() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        Bot existing = editableBot(42L, worker(7L));
        when(botCrudAccessService.requireLockedAccess(42L, authentication)).thenReturn(
                new BotCrudAccessService.LockedCrudBot(existing, 7L, true)
        );

        BotDTO result = service.findById(42L, authentication);

        assertNull(result.getPassword());
    }

    @Test
    void workerOwnedAccountListRetainsCredentialForWorkerWorkflow() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        User user = new User();
        user.setId(5L);
        Worker owner = worker(7L);
        Bot existing = editableBot(42L, owner);
        when(userService.findByUserName("worker")).thenReturn(Optional.of(user));
        when(workerService.getWorkerByUserId(5L)).thenReturn(owner);
        when(botsRepository.findAllByWorkerAndActiveIsTrue(owner)).thenReturn(List.of(existing));

        List<BotDTO> result = service.getAllBotsByWorkerActiveIsTrue(authentication);

        assertEquals(1, result.size());
        assertEquals("password-42", result.getFirst().getPassword());
    }

    @Test
    void reassignmentBetweenAccessCheckAndLockedMutationIsRejectedAsNotFound() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        BotDTO request = editableDto(42L, worker(7L));
        ResponseStatusException denied = new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "Ресурс не найден"
        );
        when(botCrudAccessService.requireLockedAccess(42L, authentication)).thenThrow(denied);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.updateBot(request, 42L, authentication)
        );

        assertEquals(404, error.getStatusCode().value());
        verify(botsRepository, never()).save(any(Bot.class));
    }

    @Test
    void workerDeleteLocksAndRechecksCurrentOwnership() {
        BotServiceImpl service = service();
        TestingAuthenticationToken authentication = workerAuthentication();
        Bot owned = editableBot(42L, worker(7L));
        when(botCrudAccessService.requireLockedAccess(42L, authentication)).thenReturn(
                new BotCrudAccessService.LockedCrudBot(owned, 7L, true)
        );

        service.deleteBot(42L, authentication);

        verify(botsRepository).delete(owned);
        verify(botsRepository, never()).deleteById(42L);
    }

    private BotServiceImpl service() {
        return new BotServiceImpl(
                userService,
                statusBotService,
                botsRepository,
                workerService,
                businessAuditService,
                botCooldownService,
                accountPoolAlertScheduler,
                botCrudAccessService
        );
    }

    private Bot bot(Long id, String fio, boolean active, String statusTitle) {
        Bot bot = new Bot();
        bot.setId(id);
        bot.setFio(fio);
        bot.setLogin("login-" + id);
        bot.setPassword("password-" + id);
        bot.setCounter(0);
        bot.setActive(active);
        bot.setBotCity(city(320L, "Город 320"));
        StatusBot status = new StatusBot();
        status.setBotStatusTitle(statusTitle);
        bot.setStatus(status);
        return bot;
    }

    private Bot editableBot(Long id, Worker worker) {
        Bot bot = bot(id, "Старое ФИО", true, "Новый");
        bot.setWorker(worker);
        return bot;
    }

    private BotDTO editableDto(Long id, Worker worker) {
        return BotDTO.builder()
                .id(id)
                .login("login-42")
                .password("password-42")
                .fio("Старое ФИО")
                .active(true)
                .counter(0)
                .status("Новый")
                .worker(worker)
                .botCity(city(320L, "Город 320"))
                .build();
    }

    private Worker worker(Long id) {
        Worker worker = new Worker();
        worker.setId(id);
        return worker;
    }

    private TestingAuthenticationToken workerAuthentication() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "worker",
                "n/a",
                "ROLE_WORKER"
        );
        authentication.setAuthenticated(true);
        return authentication;
    }

    private City city(Long id, String title) {
        City city = new City();
        city.setId(id);
        city.setTitle(title);
        return city;
    }
}
