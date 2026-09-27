package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import com.hunt.otziv.whatsapp.dto.WhatsAppDestination;
import com.hunt.otziv.whatsapp.config.WhatsAppProperties;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Personal lead destinations are frozen at launch and revalidated before dispatch. */
@Component
@RequiredArgsConstructor
public class CampaignLeadAudience {
    private final JdbcTemplate jdbc;
    private final WhatsAppProperties whatsapp;

    public List<String> senders() {
        if (whatsapp.getClients() == null) return List.of();
        return whatsapp.getClients().stream().filter(c -> c.getUrl() != null && !c.getUrl().isBlank())
                .map(WhatsAppProperties.ClientConfig::getId).filter(CampaignLeadAudience::hasText).distinct().sorted().toList();
    }
    void validate(Settings s) {
        if (!s.testOnly() && (s.includeLeadInWork() || s.includeLeadOther()) && hasText(s.leadFallbackClientId())
                && !senders().contains(s.leadFallbackClientId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Выберите настроенный WhatsApp-аккаунт для лидов без менеджера");
    }
    private record Lead(long id,String name,String status,String phone,Long managerId,String clientId) {}
    private List<Lead> rows() {
        return jdbc.query("""
                SELECT l.id,l.company_name,l.lid_status,l.telephone_lead,l.manager_id,m.client_id
                FROM leads l LEFT JOIN managers m ON m.manager_id=l.manager_id
                ORDER BY CASE WHEN TRIM(l.lid_status)='В работе' THEN 1 ELSE 2 END,l.id
                """,(r,n) -> new Lead(r.getLong(1),r.getString(2),r.getString(3),phone(r.getString(4)),
                r.getObject(5,Long.class),r.getString(6)));
    }
    List<Recipient> audience(Settings s) {
        if (s.testOnly() || !(s.includeLeadInWork() || s.includeLeadOther())) return List.of();
        validate(s);
        var leads = rows();
        Set<String> blocked = new HashSet<>();
        leads.stream().filter(l -> banned(l.status())).map(Lead::phone).filter(Objects::nonNull).forEach(blocked::add);
        Set<String> accounts = new HashSet<>(senders());
        List<Recipient> result = new ArrayList<>();
        for (var lead : leads) {
            String audience = group(lead.status());
            if (!included(audience,s) || blocked.contains(lead.phone())) continue;
            String client = lead.managerId() == null ? s.leadFallbackClientId() : lead.clientId();
            String error = lead.phone() == null ? "Некорректный телефон лида"
                    : !accounts.contains(client) ? (lead.managerId() == null ? "Не выбран отправитель для лида без менеджера"
                    : "У менеджера не настроен WhatsApp-аккаунт") : null;
            result.add(new Recipient(0,null,null,hasText(lead.name()) ? lead.name() : "Лид №"+lead.id(),audience,
                    "LEAD_IN_WORK".equals(audience) ? 4 : 5,
                    lead.phone() == null ? "MISSING_LEAD:"+lead.id() : "WHATSAPP_PHONE:"+lead.phone(),
                    null,client,null,null,null,error == null ? "PENDING" : "SKIPPED",null,error,null,null,lead.id(),lead.phone()));
        }
        // Prefer a reachable route when duplicate phone formats have different managers.
        Map<String,Recipient> unique = new LinkedHashMap<>();
        for (var r : result) unique.merge(r.destinationKey(),r,(a,b) ->
                "SKIPPED".equals(a.state()) && "PENDING".equals(b.state()) ? b : a);
        return unique.values().stream().sorted(Comparator.comparingInt(Recipient::priority).thenComparing(Recipient::leadId)).toList();
    }
    public boolean allowed(Settings s,Recipient r) {
        if (s.testOnly() || r.leadId() == null || r.companyId() != null || r.userId() != null
                || r.phone() == null || !("WHATSAPP_PHONE:"+r.phone()).equals(r.destinationKey())) return false;
        // Also rejects deleted leads, changed contact/manager, and newly banned duplicates.
        return audience(s).stream().anyMatch(current -> Objects.equals(current.leadId(),r.leadId())
                && "PENDING".equals(current.state()) && Objects.equals(current.phone(),r.phone())
                && Objects.equals(current.clientId(),r.clientId()));
    }
    private static boolean banned(String status) {
        return Set.of("бан","в бане","ban","banned").contains(status == null ? "" : status.trim().toLowerCase(Locale.ROOT));
    }
    private static String group(String status) {
        return banned(status) ? null : "В работе".equals(status == null ? null : status.trim()) ? "LEAD_IN_WORK" : "LEAD_OTHER";
    }
    private static boolean included(String group,Settings s) {
        return "LEAD_IN_WORK".equals(group) && s.includeLeadInWork() || "LEAD_OTHER".equals(group) && s.includeLeadOther();
    }
    private static String phone(String source) {
        if (source == null || !source.trim().matches("\\+?[0-9() .-]+")) return null;
        String value;
        try { value = WhatsAppDestination.normalize("send",source); }
        catch (IllegalArgumentException invalid) { return null; }
        if (value.length() == 10 && value.startsWith("9")) value = "7"+value;
        return value.matches("[1-9][0-9]{6,14}") ? value : null;
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
