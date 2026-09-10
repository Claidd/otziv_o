package com.hunt.otziv.u_users.api;

import java.util.Set;
import org.springframework.security.core.Authentication;

/** A durable actor reference, never a password, bearer token, or grant of new permissions. */
public interface DeferredUserAuthority {
    record Actor(Long userId, String username, long authEpoch, Set<String> authorities) {
        public Actor { authorities = Set.copyOf(authorities); }
    }
    Actor capture(Authentication authentication);
    Authentication revalidate(Actor actor);
}
