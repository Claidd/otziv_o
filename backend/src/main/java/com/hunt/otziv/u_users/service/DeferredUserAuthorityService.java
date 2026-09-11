package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeferredUserAuthorityService implements DeferredUserAuthority {
    private final UserRepository users;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Actor capture(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw denied();
        User user = current(authentication.getName());
        Set<String> roles = roles(user);
        Set<String> captured = authentication.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(roles::contains).collect(Collectors.toUnmodifiableSet());
        if (captured.isEmpty()) throw denied();
        return new Actor(user.getId(), user.getUsername(), user.getAuthEpoch(), captured);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Authentication revalidate(Actor actor) {
        if (actor == null) throw denied();
        User user = current(actor.username());
        if (!Objects.equals(actor.userId(), user.getId()) || actor.authEpoch() != user.getAuthEpoch()
                || actor.authorities().isEmpty() || !roles(user).containsAll(actor.authorities())) throw denied();
        // An elevation since enqueue must not expand the original command's authority.
        return UsernamePasswordAuthenticationToken.authenticated(user.getUsername(), null,
                actor.authorities().stream().sorted().map(SimpleGrantedAuthority::new).toList());
    }

    private User current(String username) {
        User user = users.findByUsername(username).orElseThrow(DeferredUserAuthorityService::denied);
        if (!user.isActive() || user.getId() == null) throw denied();
        return user;
    }
    private static Set<String> roles(User user) {
        return user.getRoles() == null ? Set.of() : user.getRoles().stream()
                .map(role -> role.getAuthority()).filter(Objects::nonNull).collect(Collectors.toSet());
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Deferred command actor is no longer authorized"); }
}
