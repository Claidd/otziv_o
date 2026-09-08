package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.repository.LeadCommandRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** No payload or contact details leave this administrative maintenance boundary. */
@Service
public class LeadLegacyClassificationService {
    private final LeadCommandRepository repository;
    private final LeadCommandCodec codec;
    private final int maxAttempts;

    public LeadLegacyClassificationService(LeadCommandRepository repository, LeadCommandCodec codec,
            @Value("${lead.vps.retry.maxAttempts:20}") int maxAttempts) {
        this.repository = repository;
        this.codec = codec;
        this.maxAttempts = maxAttempts;
    }

    public Batch classify(boolean dryRun, int limit, long afterId, Long throughId, String actor) {
        if (limit < 1 || limit > 500 || afterId < 0 || (throughId != null && throughId < afterId)) {
            throw new IllegalArgumentException("Invalid cursor: limit 1..500, 0 <= afterId <= throughId");
        }
        if (!dryRun && (actor == null || actor.isBlank() || actor.length() > 255)) {
            throw new IllegalArgumentException("Authenticated maintenance actor required");
        }
        long upper = throughId == null ? Math.max(afterId, repository.legacyHighWaterMark()) : throughId;
        var candidates = repository.legacyBatch(afterId, upper, limit);
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int changed = 0;
        int conflicts = 0;
        long next = afterId;
        for (var candidate : candidates) {
            String reason = invalidReason(candidate);
            String state = reason != null ? "QUARANTINED"
                    : candidate.attempts() >= maxAttempts ? "DEAD" : "UNKNOWN";
            if (reason == null) reason = "DEAD".equals(state) ? "LEGACY_ATTEMPTS_EXHAUSTED" : "LEGACY_DELIVERY_UNCONFIRMED";
            String outcome = "DRY_RUN";
            if (!dryRun) {
                boolean applied = repository.classifyLegacy(candidate.id(), state, reason, actor);
                if (applied) changed++; else conflicts++;
                outcome = applied ? "CHANGED" : "CONFLICT";
            }
            reasons.merge(reason, 1, Integer::sum);
            rows.add(new Row(candidate.id(), state, reason, outcome));
            next = candidate.id();
        }
        boolean complete = repository.legacyBatch(next, upper, 1).isEmpty();
        return new Batch(dryRun, afterId, upper, next, complete, rows.size(), changed, conflicts, Map.copyOf(reasons), List.copyOf(rows));
    }

    private String invalidReason(LeadCommandRepository.Legacy row) {
        if (row.json() == null || row.json().isBlank()) return "LEAD_PAYLOAD_EMPTY";
        if (row.json().matches("\\s*\\{\\s*}\\s*")) return "LEAD_PAYLOAD_EMPTY_OBJECT";
        try { codec.decode(row.kind(), row.version(), row.json()); return null; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }

    public record Row(long id, String proposedState, String reason, String outcome) {}
    public record Batch(boolean dryRun, long afterId, long throughId, long nextAfterId, boolean complete,
                        int scanned, int changed, int conflicts, Map<String, Integer> reasons, List<Row> rows) {}
}
