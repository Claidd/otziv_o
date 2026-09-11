package com.hunt.otziv.u_users.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.u_users.api.DeferredUserAuthority.Actor;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DeferredUserAuthorityServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final DeferredUserAuthorityService service = new DeferredUserAuthorityService(users);
    private final User user = new User();
    private final Actor actor = new Actor(7L, "fixture", 3, Set.of("ROLE_MANAGER"));

    @BeforeEach void setup() {
        user.setId(7L); user.setUsername("fixture"); user.setActive(true); user.setAuthEpoch(3);
        user.setRoles(List.of(role("ROLE_MANAGER")));
        when(users.findByUsername("fixture")).thenAnswer(call -> Optional.of(user));
    }

    @Test void persistedAuthorityContainsNoCredentialsAndDoesNotGainNewRoles() {
        var auth = UsernamePasswordAuthenticationToken.authenticated("fixture", "sensitive-test-credential",
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(service.capture(auth)).isEqualTo(actor);
        user.setRoles(List.of(role("ROLE_MANAGER"), role("ROLE_ADMIN")));
        var restored = service.revalidate(actor);
        assertThat(restored.getCredentials()).isNull();
        assertThat(restored.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_MANAGER");
    }

    @Test void disabledUserCannotExecuteOldCommand() {
        user.setActive(false);
        assertThatThrownBy(() -> service.revalidate(actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void revokedAuthenticationEpochCannotExecuteOldCommand() {
        user.setAuthEpoch(4);
        assertThatThrownBy(() -> service.revalidate(actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void reusedUsernameDoesNotInheritQueuedCommands() {
        user.setId(8L);
        assertThatThrownBy(() -> service.revalidate(actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void removedRoleCannotExecuteOldCommand() {
        user.setRoles(List.of(role("ROLE_USER")));
        assertThatThrownBy(() -> service.revalidate(actor)).isInstanceOf(AccessDeniedException.class);
    }

    private static Role role(String name) { var role = new Role(); role.setName(name); return role; }
}
