package com.hunt.otziv.manager_control.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.hunt.otziv.manager_control.diagnostics.ManagerProblemDiagnosticsService.*;

@Component
@RequiredArgsConstructor
public class ManagerProblemDiagnosisValidator {
    private static final Set<String> ACTIONS = Set.of("CHECK_SOURCE", "CHECK_DELIVERY", "CONTACT_RESPONSIBLE", "ESCALATE", "WAIT_FOR_DEPENDENCY");
    private static final Set<String> CLAIM_FIELDS = Set.of("reason", "comment", "last_error", "last_error_message", "error_message", "payment_success_notification_error", "title");
    private final ObjectMapper mapper;

    public Diagnosis validate(String json, List<Fact> facts) {
        try {
            JsonNode root = mapper.readTree(json);
            for (String field : List.of("confirmedFacts", "hypotheses", "missingEvidence", "actions", "verification")) {
                if (!root.path(field).isArray()) throw new IllegalArgumentException("Missing diagnosis array: " + field);
            }
            String summary = required(root, "summary");
            Map<String, Fact> references = facts.stream().collect(Collectors.toMap(Fact::reference, fact -> fact, (a, b) -> a));
            // AI can quote verified facts, but cannot manufacture new facts with a legitimate reference attached.
            List<Finding> confirmed = new ArrayList<>();
            for (JsonNode finding : root.path("confirmedFacts")) {
                List<String> refs = refs(finding, references);
                String text = requiredCanonicalText(finding);
                if (refs.size() != 1 || CLAIM_FIELDS.contains(references.get(refs.getFirst()).field())
                        || !text.equals(canonical(references.get(refs.getFirst())))) {
                    throw new IllegalArgumentException("Confirmed fact must exactly quote a server fact");
                }
                confirmed.add(new Finding(text, refs));
            }
            List<Finding> hypotheses = new ArrayList<>();
            for (JsonNode finding : root.path("hypotheses")) hypotheses.add(new Finding(required(finding, "text"), refs(finding, references)));
            List<Action> actions = new ArrayList<>();
            for (JsonNode action : root.path("actions")) {
                String code = required(action, "code");
                if (!ACTIONS.contains(code)) throw new IllegalArgumentException("Unsupported diagnostic action");
                actions.add(new Action(code, required(action, "text"), required(action, "owner"), required(action, "verification"), refs(action, references)));
            }
            List<String> missing = strings(root.path("missingEvidence"));
            if (facts.stream().anyMatch(fact -> Set.of("coverage", "history_coverage").contains(fact.subject()) || fact.field().equals("availability"))) {
                missing = new ArrayList<>(missing);
                missing.add("Срез источника или истории ограничен. По нему нельзя исключить другие ошибки или более ранние действия; при необходимости проверьте полный журнал.");
            }
            // Uncertain delivery must never suggest retrying a send or creating another payment.
            List<Fact> uncertain = ManagerProblemBusinessDiagnosis.uncertainDelivery(facts);
            if (!uncertain.isEmpty()) {
                Diagnosis safe = ManagerProblemBusinessDiagnosis.uncertain(facts, uncertain.getFirst());
                return new Diagnosis(safe.summary(), List.copyOf(confirmed), List.copyOf(hypotheses), List.copyOf(missing), safe.actions(), safe.verification());
            }
            return new Diagnosis(summary, List.copyOf(confirmed), List.copyOf(hypotheses), List.copyOf(missing), List.copyOf(actions), strings(root.path("verification")));
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid diagnosis JSON", e); }
    }

    public static String canonical(Fact fact) { return fact.subject() + "." + fact.field() + "=" + fact.value(); }
    static boolean isServerFact(Fact fact) { return !CLAIM_FIELDS.contains(fact.field()); }
    private static String requiredCanonicalText(JsonNode node) {
        JsonNode value = node.path("text");
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096) {
            throw new IllegalArgumentException("Invalid canonical diagnosis text");
        }
        // The next check requires equality with an already-redacted server fact. Redacting again here
        // would truncate the field prefix + 2001-character excerpt and make exact attribution impossible.
        return value.asText();
    }
    private static String required(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2000) throw new IllegalArgumentException("Invalid diagnosis field " + name);
        return DiagnosticRedactor.redact(value.asText());
    }
    private static List<String> refs(JsonNode node, Map<String, Fact> known) {
        List<String> values = strings(node.path("evidenceRefs"));
        if (values.isEmpty() || values.stream().anyMatch(ref -> !known.containsKey(ref))) throw new IllegalArgumentException("Unrecognized evidence reference");
        return values;
    }
    private static List<String> strings(JsonNode node) {
        if (!node.isArray() || node.size() > 40) throw new IllegalArgumentException("Invalid diagnosis list");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2000) throw new IllegalArgumentException("Invalid diagnosis text");
            values.add(DiagnosticRedactor.redact(value.asText()));
        }
        return List.copyOf(values);
    }
}
