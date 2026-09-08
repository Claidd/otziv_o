package com.hunt.otziv.p_products.application;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;

public record WorkerOrderActor(String username, Set<String> roles) {
    public WorkerOrderActor { roles = Set.copyOf(roles); }
    public static WorkerOrderActor from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return new WorkerOrderActor("", Set.of());
        return new WorkerOrderActor(authentication.getName(), authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", "")).collect(Collectors.toSet()));
    }
    public boolean has(String role) { return roles.contains(role); }
    public Authentication authentication() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                username, null, roles.stream().map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)).toList());
    }
    public void require(String... allowed) {
        if (username == null || username.isBlank() || java.util.Arrays.stream(allowed).noneMatch(roles::contains)) {
            throw new WorkerOrderCommandException(WorkerOrderCommandException.Kind.FORBIDDEN, "Операция недоступна");
        }
    }
}
