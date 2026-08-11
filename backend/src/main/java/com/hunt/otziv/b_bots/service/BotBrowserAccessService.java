package com.hunt.otziv.b_bots.service;

import com.hunt.otziv.b_bots.repository.BotBrowserAccessRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BotBrowserAccessService {

    private static final Set<String> GLOBAL_BROWSER_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_OWNER",
            "ROLE_MANAGER"
    );

    private final BotBrowserAccessRepository repository;

    /**
     * Returns only non-secret bot metadata after a fresh object-level access
     * check. Missing and unauthorized bots deliberately share the same 404.
     */
    @Transactional(readOnly = true)
    public AuthorizedBot requireAccess(long botId, Authentication authentication) {
        String username = authenticatedUsername(authentication);
        if (hasAnyAuthority(authentication, GLOBAL_BROWSER_ROLES)) {
            return repository.findGloballyAccessibleBrowserBot(botId, username)
                    .map(this::toAuthorizedBot)
                    .orElseThrow(this::notFound);
        }

        if (!hasAuthority(authentication, "ROLE_WORKER")) {
            throw notFound();
        }

        return repository.findWorkerAccessibleBrowserBot(botId, username)
                .map(this::toAuthorizedBot)
                .orElseThrow(this::notFound);
    }

    private String authenticatedUsername(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw notFound();
        }
        return authentication.getName();
    }

    private boolean hasAnyAuthority(Authentication authentication, Set<String> expected) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> expected.contains(authority.getAuthority()));
    }

    private boolean hasAuthority(Authentication authentication, String expected) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }

    private AuthorizedBot toAuthorizedBot(BotBrowserAccessRepository.BrowserBotRow row) {
        return new AuthorizedBot(row.getBotId(), row.getLogin(), row.getFio());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ресурс не найден");
    }

    public record AuthorizedBot(Long id, String login, String fio) {
    }
}
