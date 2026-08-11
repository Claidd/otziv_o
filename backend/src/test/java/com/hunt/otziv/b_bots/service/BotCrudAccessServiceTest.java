package com.hunt.otziv.b_bots.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository.ActiveCrudPrincipalRow;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository.CrudBotRow;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class BotCrudAccessServiceTest {

    private final BotCrudAccessRepository repository = mock(BotCrudAccessRepository.class);
    private final BotsRepository botsRepository = mock(BotsRepository.class);
    private final BotCrudAccessService service = new BotCrudAccessService(repository, botsRepository);

    @Test
    void lockedGuardsRequireTheCallersTransactionToCoverCheckAndAction() throws Exception {
        for (String methodName : new String[]{"requireLockedAccess", "requireLockedGlobalAccess"}) {
            Transactional transactional = BotCrudAccessService.class
                    .getMethod(methodName, long.class, org.springframework.security.core.Authentication.class)
                    .getAnnotation(Transactional.class);

            assertThat(transactional).isNotNull();
            assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_OWNER", "ROLE_WORKER"})
    void createRequiresMatchingFreshActiveDatabaseRole(String role) {
        ActiveCrudPrincipalRow principal = principalRow(5L, role);
        when(repository.findActiveCrudPrincipalForUpdate("creator", Set.of(role)))
                .thenReturn(Optional.of(principal));

        service.requireCreateAccess(authentication("creator", role));

        verify(repository).findActiveCrudPrincipalForUpdate("creator", Set.of(role));
    }

    @Test
    void staleCreateSessionIsRejectedAfterDatabaseRoleOrActiveStateChanges() {
        when(repository.findActiveCrudPrincipalForUpdate("stale", Set.of("ROLE_WORKER")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireCreateAccess(
                authentication("stale", "ROLE_WORKER")
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode().value()).isEqualTo(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_OWNER"})
    void adminAndOwnerRetainFreshGlobalCrudAccess(String role) {
        CrudBotRow row = row(42L, 7L);
        when(repository.findGloballyManageableBot(42L, "privileged"))
                .thenReturn(Optional.of(row));

        BotCrudAccessService.AuthorizedCrudBot result = service.requireAccess(
                42L,
                authentication("privileged", role)
        );

        assertThat(result).isEqualTo(new BotCrudAccessService.AuthorizedCrudBot(42L, 7L, false));
        assertThat(result.workerId()).isEqualTo(7L);
        verify(repository).findGloballyManageableBot(42L, "privileged");
        verify(repository, never()).findWorkerOwnedBot(42L, "privileged");
    }

    @Test
    void workerUsesFreshDirectOwnershipQuery() {
        CrudBotRow row = row(17L, 9L);
        when(repository.findWorkerOwnedBot(17L, "worker"))
                .thenReturn(Optional.of(row));

        BotCrudAccessService.AuthorizedCrudBot result = service.requireAccess(
                17L,
                authentication("worker", "ROLE_WORKER")
        );

        assertThat(result.workerScoped()).isTrue();
        assertThat(result.workerId()).isEqualTo(9L);
        verify(repository).findWorkerOwnedBot(17L, "worker");
        verify(repository, never()).findGloballyManageableBot(17L, "worker");
    }

    @Test
    void lockedWorkerAccessUsesFreshPrincipalThenLockedBotAndDirectOwner() {
        AuthenticationFixture fixture = ownedBotFixture("worker", "ROLE_WORKER", 5L, 9L, 17L);

        BotCrudAccessService.LockedCrudBot result = service.requireLockedAccess(
                17L,
                fixture.authentication()
        );

        assertThat(result.bot()).isSameAs(fixture.bot());
        assertThat(result.workerScoped()).isTrue();
        assertThat(result.workerId()).isEqualTo(9L);
        var ordered = org.mockito.Mockito.inOrder(repository, botsRepository);
        ordered.verify(repository).findActiveCrudPrincipalForUpdate("worker", Set.of("ROLE_WORKER"));
        ordered.verify(botsRepository).findByIdForCrudMutationLock(17L);
    }

    @Test
    void lockedWorkerAccessRejectsBotOwnedByAnotherUser() {
        TestingAuthenticationToken authentication = authentication("worker", "ROLE_WORKER");
        ActiveCrudPrincipalRow principal = principalRow(5L, "ROLE_WORKER");
        when(repository.findActiveCrudPrincipalForUpdate("worker", Set.of("ROLE_WORKER")))
                .thenReturn(Optional.of(principal));
        Bot bot = ownedBot(17L, 9L, 6L);
        when(botsRepository.findByIdForCrudMutationLock(17L)).thenReturn(Optional.of(bot));

        assertThatThrownBy(() -> service.requireLockedAccess(17L, authentication))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void lockedGlobalAccessDoesNotFallBackToWorkerRole() {
        TestingAuthenticationToken staleAdminAndWorker = authentication(
                "mixed",
                "ROLE_ADMIN",
                "ROLE_WORKER"
        );
        when(repository.findActiveCrudPrincipalForUpdate("mixed", Set.of("ROLE_ADMIN")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireLockedGlobalAccess(17L, staleAdminAndWorker))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));
        verify(botsRepository, never()).findByIdForCrudMutationLock(17L);
    }

    @Test
    void workerCannotTransferOwnedBotButAdminAndOwnerCan() {
        Worker anotherWorker = new Worker();
        anotherWorker.setId(99L);
        BotDTO request = new BotDTO();
        request.setWorker(anotherWorker);

        BotCrudAccessService.AuthorizedCrudBot workerAccess =
                new BotCrudAccessService.AuthorizedCrudBot(17L, 9L, true);
        assertThatThrownBy(() -> service.requireUpdateOwnership(workerAccess, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));

        BotCrudAccessService.AuthorizedCrudBot globalAccess =
                new BotCrudAccessService.AuthorizedCrudBot(17L, 9L, false);
        service.requireUpdateOwnership(globalAccess, request);
    }

    @Test
    void managerDoesNotReceiveCrudAccess() {
        assertThatThrownBy(() -> service.requireAccess(
                42L,
                authentication("manager", "ROLE_MANAGER")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));

        verifyNoMoreInteractions(repository);
    }

    @Test
    void missingAndAnotherWorkersBotShareTheSame404() {
        when(repository.findWorkerOwnedBot(99L, "worker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireAccess(
                99L,
                authentication("worker", "ROLE_WORKER")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(404);
                    assertThat(exception.getReason()).isEqualTo("Ресурс не найден");
                });
    }

    private CrudBotRow row(Long botId, Long workerId) {
        CrudBotRow row = mock(CrudBotRow.class);
        when(row.getBotId()).thenReturn(botId);
        when(row.getWorkerId()).thenReturn(workerId);
        return row;
    }

    private ActiveCrudPrincipalRow principalRow(Long userId, String roleName) {
        ActiveCrudPrincipalRow row = mock(ActiveCrudPrincipalRow.class);
        when(row.getUserId()).thenReturn(userId);
        when(row.getRoleName()).thenReturn(roleName);
        return row;
    }

    private AuthenticationFixture ownedBotFixture(
            String username,
            String role,
            Long userId,
            Long workerId,
            Long botId
    ) {
        TestingAuthenticationToken authentication = authentication(username, role);
        ActiveCrudPrincipalRow principal = principalRow(userId, role);
        when(repository.findActiveCrudPrincipalForUpdate(username, Set.of(role)))
                .thenReturn(Optional.of(principal));
        Bot bot = ownedBot(botId, workerId, userId);
        when(botsRepository.findByIdForCrudMutationLock(botId)).thenReturn(Optional.of(bot));
        return new AuthenticationFixture(authentication, bot);
    }

    private Bot ownedBot(Long botId, Long workerId, Long ownerUserId) {
        User user = new User();
        user.setId(ownerUserId);
        Worker worker = new Worker();
        worker.setId(workerId);
        worker.setUser(user);
        Bot bot = new Bot();
        bot.setId(botId);
        bot.setWorker(worker);
        return bot;
    }

    private TestingAuthenticationToken authentication(String username, String... roles) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                username,
                "n/a",
                roles
        );
        authentication.setAuthenticated(true);
        return authentication;
    }

    private record AuthenticationFixture(TestingAuthenticationToken authentication, Bot bot) {
    }
}
