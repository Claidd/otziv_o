package com.hunt.otziv.l_lead.controller;

import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import com.hunt.otziv.l_lead.service.LeadLegacyClassificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/admin/lead-commands")
@PreAuthorize("hasAnyRole('ADMIN','OWNER')")
public class LeadCommandAdminController {
    private final LeadCommandRepository repository;
    private final LeadLegacyClassificationService classification;
    public LeadCommandAdminController(LeadCommandRepository repository, LeadLegacyClassificationService classification) {
        this.repository = repository;
        this.classification = classification;
    }
    @GetMapping public List<Map<String,Object>> counts() { return repository.counts(); }

    /** Repeatable batches; reports never expose payload, phone or remote response. */
    @PostMapping("/classify-legacy")
    public LeadLegacyClassificationService.Batch classify(@RequestParam(defaultValue="true") boolean dryRun,
            @RequestParam(defaultValue="100") int limit, @RequestParam(defaultValue="0") long afterId,
            @RequestParam(required=false) Long throughId, Principal principal) {
        try {
            return classification.classify(dryRun, limit, afterId, throughId, principal == null ? null : principal.getName());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @PostMapping("/{id}/resolve")
    public void resolve(@PathVariable long id, @Valid @RequestBody Resolution request, Principal principal) {
        try {
            if (!repository.resolve(id, request.resolution(), principal.getName(), request.reason())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Command is not awaiting resolution");
            }
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }
    public record Resolution(@NotBlank String resolution, @NotBlank @Size(max=255) String reason) {}
}
