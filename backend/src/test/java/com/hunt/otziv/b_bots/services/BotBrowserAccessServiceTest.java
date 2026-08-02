package com.hunt.otziv.b_bots.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.b_bots.repository.BotBrowserAccessRepository;
import com.hunt.otziv.b_bots.repository.BotBrowserAccessRepository.BrowserBotRow;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class BotBrowserAccessServiceTest {

    private final BotBrowserAccessRepository repository = mock(BotBrowserAccessRepository.class);
    private final BotBrowserAccessService service = new BotBrowserAccessService(repository);

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER"})
    void privilegedRolesRetainGlobalBrowserAccess(String role) {
        BrowserBotRow row = row(42L, "79990000000", "Иванов И.И.");
        when(repository.findGloballyAccessibleBrowserBot(42L, "privileged")).thenReturn(Optional.of(row));

        BotBrowserAccessService.AuthorizedBot result = service.requireAccess(
                42L,
                authentication("privileged", role)
        );

        assertThat(result).isEqualTo(new BotBrowserAccessService.AuthorizedBot(
                42L,
                "79990000000",
                "Иванов И.И."
        ));
        verify(repository).findGloballyAccessibleBrowserBot(42L, "privileged");
        verify(repository, never()).findWorkerAccessibleBrowserBot(42L, "privileged");
    }

    @Test
    void managerAuthorityWinsWhenAccountAlsoHasWorkerRole() {
        BrowserBotRow row = row(7L, "login", "ФИО");
        when(repository.findGloballyAccessibleBrowserBot(7L, "manager")).thenReturn(Optional.of(row));

        service.requireAccess(7L, authentication("manager", "ROLE_WORKER", "ROLE_MANAGER"));

        verify(repository).findGloballyAccessibleBrowserBot(7L, "manager");
        verify(repository, never()).findWorkerAccessibleBrowserBot(7L, "manager");
    }

    @Test
    void workerUsesFreshObjectLevelAccessQuery() {
        BrowserBotRow row = row(17L, "login", "ФИО");
        when(repository.findWorkerAccessibleBrowserBot(17L, "worker"))
                .thenReturn(Optional.of(row));

        BotBrowserAccessService.AuthorizedBot result = service.requireAccess(
                17L,
                authentication("worker", "ROLE_WORKER")
        );

        assertThat(result.id()).isEqualTo(17L);
        verify(repository).findWorkerAccessibleBrowserBot(17L, "worker");
        verify(repository, never()).findGloballyAccessibleBrowserBot(17L, "worker");
    }

    @Test
    void missingAndUnauthorizedWorkerBotsShareTheSame404() {
        when(repository.findWorkerAccessibleBrowserBot(99L, "worker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireAccess(
                99L,
                authentication("worker", "ROLE_WORKER")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(404);
                    assertThat(exception.getReason()).isEqualTo("Ресурс не найден");
                });

        verify(repository).findWorkerAccessibleBrowserBot(99L, "worker");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void callerWithoutSupportedRoleGetsSecretObject404WithoutRepositoryLookup() {
        assertThatThrownBy(() -> service.requireAccess(
                99L,
                authentication("operator", "ROLE_OPERATOR")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode().value()).isEqualTo(404));

        verifyNoMoreInteractions(repository);
    }

    private BrowserBotRow row(Long id, String login, String fio) {
        BrowserBotRow row = mock(BrowserBotRow.class);
        when(row.getBotId()).thenReturn(id);
        when(row.getLogin()).thenReturn(login);
        when(row.getFio()).thenReturn(fio);
        return row;
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
}
