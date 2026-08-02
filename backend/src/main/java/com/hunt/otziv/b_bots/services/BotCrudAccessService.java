package com.hunt.otziv.b_bots.services;

import com.hunt.otziv.b_bots.dto.BotDTO;
import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository.ActiveCrudPrincipalRow;
import com.hunt.otziv.b_bots.repository.BotCrudAccessRepository.CrudBotRow;
import com.hunt.otziv.b_bots.repository.BotsRepository;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BotCrudAccessService {

    private static final Set<String> GLOBAL_CRUD_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_OWNER"
    );
    private static final Set<String> CREATE_CRUD_ROLES = Set.of(
            "ROLE_ADMIN",
            "ROLE_OWNER",
            "ROLE_WORKER"
    );

    private final BotCrudAccessRepository repository;
    private final BotsRepository botsRepository;

    /**
     * Rechecks active status and the current database role before a create.
     * The database role must still match one of the authorities in this session,
     * so a stale or re-purposed session cannot cross role boundaries.
     */
    @Transactional
    public void requireCreateAccess(Authentication authentication) {
        lockFreshPrincipal(authentication, CREATE_CRUD_ROLES);
    }

    /**
     * Locks and verifies a currently active ADMIN/OWNER principal. Callers use
     * this at the start of a transaction that reads bot secrets or mutates the
     * global bot directory, so a concurrent role revoke cannot overtake the
     * protected operation.
     */
    @Transactional
    public void requireGlobalAccess(Authentication authentication) {
        lockFreshPrincipal(authentication, GLOBAL_CRUD_ROLES);
    }

    /**
     * Acquires locks in the canonical principal -&gt; bot order and repeats both
     * the database role and direct worker-ownership checks in the same
     * transaction as the caller's read/mutation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedCrudBot requireLockedAccess(long botId, Authentication authentication) {
        ActiveCrudPrincipal principal = lockFreshPrincipal(authentication, CREATE_CRUD_ROLES);
        Bot bot = lockBot(botId);
        boolean workerScoped = !GLOBAL_CRUD_ROLES.contains(principal.roleName());
        requireCurrentOwnership(bot, principal, workerScoped);
        return toLockedBot(bot, workerScoped);
    }

    /**
     * ADMIN/OWNER-only variant for the modern administrative API. A stale
     * global JWT must not fall back to a current WORKER role.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedCrudBot requireLockedGlobalAccess(long botId, Authentication authentication) {
        lockFreshPrincipal(authentication, GLOBAL_CRUD_ROLES);
        return toLockedBot(lockBot(botId), false);
    }

    /**
     * Performs a fresh database-backed role and object ownership check.
     * Missing and unauthorized bots deliberately share the same 404 response.
     */
    @Transactional(readOnly = true)
    public AuthorizedCrudBot requireAccess(long botId, Authentication authentication) {
        String username = authenticatedUsername(authentication);
        if (hasAnyAuthority(authentication, GLOBAL_CRUD_ROLES)) {
            return repository.findGloballyManageableBot(botId, username)
                    .map(row -> toAuthorizedBot(row, false))
                    .orElseThrow(this::notFound);
        }

        if (!hasAuthority(authentication, "ROLE_WORKER")) {
            throw notFound();
        }

        return repository.findWorkerOwnedBot(botId, username)
                .map(row -> toAuthorizedBot(row, true))
                .orElseThrow(this::notFound);
    }

    /**
     * A worker may edit fields of their bot but may not transfer ownership.
     * ADMIN and OWNER retain the existing global reassignment capability.
     */
    public void requireUpdateOwnership(AuthorizedCrudBot authorizedBot, BotDTO request) {
        if (!authorizedBot.workerScoped()) {
            return;
        }
        Long requestedWorkerId = request == null || request.getWorker() == null
                ? null
                : request.getWorker().getId();
        if (!Objects.equals(authorizedBot.workerId(), requestedWorkerId)) {
            throw notFound();
        }
    }

    public void requireUpdateOwnership(LockedCrudBot lockedBot, BotDTO request) {
        requireUpdateOwnership(lockedBot.authorizedBot(), request);
    }

    private ActiveCrudPrincipal lockFreshPrincipal(
            Authentication authentication,
            Set<String> allowedRoles
    ) {
        String username = authenticatedUsername(authentication);
        Set<String> sessionCrudRoles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(allowedRoles::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (sessionCrudRoles.isEmpty()) {
            throw notFound();
        }

        ActiveCrudPrincipalRow row = repository
                .findActiveCrudPrincipalForUpdate(username, sessionCrudRoles)
                .orElseThrow(this::notFound);
        if (row.getUserId() == null
                || row.getRoleName() == null
                || !sessionCrudRoles.contains(row.getRoleName())) {
            throw notFound();
        }
        return new ActiveCrudPrincipal(row.getUserId(), row.getRoleName());
    }

    private Bot lockBot(long botId) {
        return botsRepository.findByIdForCrudMutationLock(botId)
                .orElseThrow(this::notFound);
    }

    private void requireCurrentOwnership(
            Bot bot,
            ActiveCrudPrincipal principal,
            boolean workerScoped
    ) {
        if (!workerScoped) {
            return;
        }
        Long ownerUserId = bot.getWorker() == null || bot.getWorker().getUser() == null
                ? null
                : bot.getWorker().getUser().getId();
        if (!Objects.equals(principal.userId(), ownerUserId)) {
            throw notFound();
        }
    }

    private LockedCrudBot toLockedBot(Bot bot, boolean workerScoped) {
        Long workerId = bot.getWorker() == null ? null : bot.getWorker().getId();
        if (workerScoped && workerId == null) {
            throw notFound();
        }
        return new LockedCrudBot(bot, workerId, workerScoped);
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

    private AuthorizedCrudBot toAuthorizedBot(CrudBotRow row, boolean workerScoped) {
        if (workerScoped && row.getWorkerId() == null) {
            throw notFound();
        }
        return new AuthorizedCrudBot(row.getBotId(), row.getWorkerId(), workerScoped);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ресурс не найден");
    }

    public record AuthorizedCrudBot(Long id, Long workerId, boolean workerScoped) {
    }

    public record LockedCrudBot(Bot bot, Long workerId, boolean workerScoped) {
        public AuthorizedCrudBot authorizedBot() {
            return new AuthorizedCrudBot(bot.getId(), workerId, workerScoped);
        }
    }

    private record ActiveCrudPrincipal(Long userId, String roleName) {
    }
}
