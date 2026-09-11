package com.hunt.otziv.u_users.controller;

import com.hunt.otziv.u_users.repository.AuthSessionStateRepository;
import com.hunt.otziv.u_users.service.UserSessionRevocationService;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/security/session-revocations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ApiSessionRevocationAdminController {
    private final AuthSessionStateRepository state;
    private final UserSessionRevocationService revocations;
    private final com.hunt.otziv.u_users.service.UserSessionProtocolCutoverService cutover;

    @GetMapping
    public List<Pending> pending(@RequestParam(defaultValue = "50") int limit) {
        return state.pendingMutations(limit).stream()
                .map(item -> new Pending(item.id(), item.userId(), item.epoch(), item.phase())).toList();
    }

    @PostMapping("/{operation}/reconcile")
    public void reconcile(@PathVariable String operation) { revocations.reconcile(operation); }

    @PostMapping("/bootstrap/{userId}")
    public Pending bootstrap(@PathVariable long userId) {
        String operation = cutover.bootstrap(userId);
        revocations.reconcile(operation);
        var result = state.mutation(operation).orElseThrow();
        return new Pending(result.id(),result.userId(),result.epoch(),result.phase());
    }

    @PostMapping("/{operation}/resolve-password-outcome")
    public void resolve(@PathVariable String operation, @RequestBody Resolution request, Principal actor) {
        revocations.resolveUnknownPassword(operation, actor.getName(), request.verifiedExternalOutcome());
    }

    public record Pending(String operationId, long userId, long epoch, String phase) {}
    public record Resolution(String verifiedExternalOutcome) {}
}
