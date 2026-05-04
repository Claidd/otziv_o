package com.hunt.otziv.manager.services;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagerPermissionServiceTest {

    private final ManagerPermissionService service = new ManagerPermissionService();

    @Test
    void hasRoleAcceptsPrefixedAndUnprefixedRoleNames() {
        Authentication authentication = authentication("ROLE_ADMIN");

        assertTrue(service.hasRole(authentication, "ADMIN"));
        assertTrue(service.hasRole(authentication, "ROLE_ADMIN"));
        assertFalse(service.hasRole(authentication, "OWNER"));
    }

    @Test
    void hasAnyRoleChecksEveryProvidedRole() {
        Authentication authentication = authentication("ROLE_MANAGER");

        assertTrue(service.hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER"));
        assertFalse(service.hasAnyRole(authentication, "ADMIN", "OWNER"));
    }

    @Test
    void hasOnlyWorkerRoleRejectsElevatedUsers() {
        assertTrue(service.hasOnlyWorkerRole(authentication("ROLE_WORKER")));
        assertFalse(service.hasOnlyWorkerRole(authentication("ROLE_WORKER", "ROLE_MANAGER")));
        assertFalse(service.hasOnlyWorkerRole(authentication("ROLE_MANAGER")));
    }

    @Test
    void primaryReviewRoleUsesExistingPriorityOrder() {
        assertEquals("ROLE_ADMIN", service.primaryReviewRole(authentication("ROLE_WORKER", "ROLE_ADMIN")));
        assertEquals("ROLE_OWNER", service.primaryReviewRole(authentication("ROLE_OWNER", "ROLE_MANAGER")));
        assertEquals("ROLE_MANAGER", service.primaryReviewRole(authentication("ROLE_MANAGER")));
        assertEquals("ROLE_WORKER", service.primaryReviewRole(authentication("ROLE_WORKER")));
        assertEquals("anonymous", service.primaryReviewRole(authentication("ROLE_USER")));
    }

    @Test
    void nullAuthenticationHasNoManagerPermissions() {
        assertFalse(service.hasRole(null, "ADMIN"));
        assertFalse(service.hasAnyRole(null, "ADMIN", "OWNER"));
        assertFalse(service.hasOnlyWorkerRole(null));
        assertEquals("anonymous", service.primaryReviewRole(null));
    }

    private Authentication authentication(String... authorities) {
        return new UsernamePasswordAuthenticationToken(
                "user",
                "password",
                Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList()
        );
    }
}
