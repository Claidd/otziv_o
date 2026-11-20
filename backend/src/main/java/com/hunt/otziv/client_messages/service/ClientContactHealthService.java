package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientContactHealth;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.repository.ArchiveCompanyMessageCandidateRepository;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import com.hunt.otziv.t_telegrambot.api.TelegramChatAccess;
import com.hunt.otziv.whatsapp.api.WhatsAppChatAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** This read model owns only client_contact_health; business records and delivery ledgers stay intact. */
@Service
@RequiredArgsConstructor
public class ClientContactHealthService implements ClientContactHealth {
    private final JdbcTemplate jdbc;
    private final ArchiveCompanyMessageCandidateRepository archiveCandidates;
    private final BackgroundAutomationSettings settings;
    private final TelegramChatAccess telegram;
    private final WhatsAppChatAccess whatsapp;
    private final com.hunt.otziv.maxbot.api.MaxChatAccess max;

    record Snapshot(Long id, String title, String status, Long managerId, String url, String client,
                    String group, Long telegram, Long max, boolean active) {
        String platform() {
            String link = text(url).toLowerCase(Locale.ROOT);
            if (link.matches("^(https?://)?(www\\.)?chat\\.whatsapp\\.com/.*")) return "WHATSAPP";
            if (link.matches("^(https?://)?(t\\.me|telegram\\.me)/.*")) return "TELEGRAM";
            if (link.matches("^(https?://)?(max\\.ru|web\\.max\\.ru)/.*")) return "MAX";
            return "UNKNOWN";
        }
        String hash() {
            try {
                // Lengths avoid delimiter collisions and retain invite case.
                String input = List.of(text(url), text(client), text(group), text(telegram), text(max)).stream()
                        .map(s -> s.length() + ":" + s).collect(java.util.stream.Collectors.joining());
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    record Evidence(String hash, String status, String reason, String unavailable, LocalDateTime checkedAt) { }
    record Probe(String status, String reason) { }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static final String SNAPSHOT_SQL = """
                SELECT c.company_id,c.company_title,cs.status_title,c.company_manager,c.company_url_chat,
                       m.client_id,c.company_group_id,c.company_telegram_group_chat_id,c.company_max_group_chat_id,c.company_active
                FROM companies c LEFT JOIN company_status cs ON cs.company_status_id=c.company_status
                LEFT JOIN managers m ON m.manager_id=c.company_manager
                """;
    private List<Snapshot> snapshots(String where,Object... args) {
        return jdbc.query(SNAPSHOT_SQL + where, (rs, n) -> new Snapshot(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getObject(4,Long.class),
                    rs.getString(5),rs.getString(6),rs.getString(7),rs.getObject(8,Long.class),rs.getObject(9,Long.class),rs.getBoolean(10)), args);
    }
    private Snapshot snapshot(Long id) {
        return id == null ? null : snapshots(" WHERE c.company_id=?",id).stream().findFirst().orElse(null);
    }
    private Map<Long,Evidence> allEvidence() {
        Map<Long,Evidence> result = new HashMap<>();
        jdbc.query("SELECT company_id,route_hash,check_status,reason_code,unavailable_reason,checked_at FROM client_contact_health",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getLong(1),new Evidence(rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toLocalDateTime())));
        return result;
    }
    private Set<Long> activeCompanies(List<Snapshot> snapshots) {
        return archiveCandidates.companiesWithArchiveReorderBlocker(snapshots.stream().map(Snapshot::id).toList(),
                settingList("client.messages.archive.inactive-order-statuses",ScheduledClientMessageService.DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                settingList("client.messages.open-next-order-request-statuses",ScheduledClientMessageService.DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES));
    }
    @Override public Routing routing(Long managerId) {
        var companies = snapshots(" WHERE c.company_manager=?",managerId);
        var active = activeCompanies(companies);
        var evidence = allEvidence();
        Set<Long> excluded = new HashSet<>();
        for (var s:companies) if (s.active() && "На стопе".equalsIgnoreCase(text(s.status())) && !active.contains(s.id())
                && unavailable(s,evidence.get(s.id())) != null) excluded.add(s.id());
        Set<Long> needsManager = new HashSet<>(active);
        companies.stream().filter(s -> "В работе".equalsIgnoreCase(text(s.status())))
                .map(Snapshot::id).forEach(needsManager::add);
        return new Routing(Set.copyOf(excluded),Set.copyOf(needsManager));
    }
    private Evidence evidence(Long id) {
        return jdbc.query("SELECT route_hash,check_status,reason_code,unavailable_reason,checked_at FROM client_contact_health WHERE company_id=?",
                (rs,n) -> new Evidence(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toLocalDateTime()), id)
                .stream().findFirst().orElse(null);
    }
    private List<String> settingList(String key, String fallback) {
        String raw = settings.getStringFresh(key, fallback);
        List<String> values = Arrays.stream(text(raw).split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        return values.isEmpty() ? Arrays.stream(fallback.split(",")).map(String::trim).toList() : values;
    }
    @Override public boolean hasActiveWork(Long id) {
        return archiveCandidates.hasArchiveReorderBlocker(id,
                settingList("client.messages.archive.inactive-order-statuses", ScheduledClientMessageService.DEFAULT_ARCHIVE_INACTIVE_ORDER_STATUSES),
                settingList("client.messages.open-next-order-request-statuses", ScheduledClientMessageService.DEFAULT_OPEN_NEXT_ORDER_REQUEST_STATUSES));
    }
    private boolean idle(Snapshot s) {
        return s != null && s.active() && "На стопе".equalsIgnoreCase(text(s.status())) && !hasActiveWork(s.id());
    }
    @Override public boolean requiresManagerAttention(Long id) {
        var s = snapshot(id);
        return s != null && ("В работе".equalsIgnoreCase(text(s.status())) || hasActiveWork(id));
    }
    private String unavailable(Snapshot s, Evidence e) {
        if (s == null) return null;
        if (text(s.url()).isEmpty()) return "chat_missing";
        if ("TELEGRAM".equals(s.platform()) && s.telegram() == null
                || "WHATSAPP".equals(s.platform()) && (text(s.group()).isEmpty() || text(s.client()).isEmpty())
                || "MAX".equals(s.platform()) && s.max() == null) return "chat_unbound";
        if (e == null || e.unavailable() == null) return null;
        return s.hash().equals(e.hash()) ? e.unavailable() : "route_changed_unverified";
    }
    @Override public boolean adminOnly(Long id) {
        var s = snapshot(id);
        return idle(s) && unavailable(s,evidence(id)) != null;
    }
    @Override public boolean holdNewMessage(ClientMessageDelivery.Target target, String clientId, String groupId) {
        if (target == null || target.companyId() == null) return false;
        var s = snapshot(target.companyId());
        if (s == null || !Objects.equals(text(s.url()),text(target.urlChat()))
                || !Objects.equals(s.telegram(),target.telegramChatId()) || !Objects.equals(s.max(),target.maxChatId())
                || !Objects.equals(text(s.client()),text(clientId)) || !Objects.equals(text(s.group()),text(groupId))) return false;
        return idle(s) && unavailable(s,evidence(s.id())) != null;
    }

    @Override public List<Contact> excludedCompanies() {
        return contacts(null).stream().filter(Contact::excluded).toList();
    }
    @Override public List<Contact> managerIssues(Long managerId) {
        if (managerId == null) return List.of();
        return contacts(managerId).stream().filter(contact -> !contact.excluded()).toList();
    }
    private List<Contact> contacts(Long manager) {
        String where = " WHERE c.company_active=1 AND COALESCE(cs.status_title,'')<>'Бан'";
        var companies = manager == null ? snapshots(where) : snapshots(where+" AND c.company_manager=?",manager);
        var evidence = allEvidence();
        var active = activeCompanies(companies);
        return companies.stream().map(s -> contact(s,evidence.get(s.id()),active.contains(s.id())))
                .filter(Objects::nonNull).sorted(Comparator.comparing(Contact::title,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
    }
    private Contact contact(Long id) {
        var s = snapshot(id);
        var e = evidence(id);
        return contact(s,e,hasActiveWork(id));
    }
    private Contact contact(Snapshot s,Evidence e,boolean work) {
        String reason = unavailable(s,e);
        if (s == null || !s.active() || "Бан".equalsIgnoreCase(text(s.status())) || reason == null) return null;
        boolean excluded = "На стопе".equalsIgnoreCase(text(s.status())) && !work;
        return new Contact(s.id(),s.title(),s.status(),s.managerId(),s.platform(),s.url(),reason,description(reason),
                e != null && s.hash().equals(e.hash()) ? e.status() : e != null && e.unavailable() != null ? "UNKNOWN" : "UNAVAILABLE",
                e != null && s.hash().equals(e.hash()) ? e.checkedAt() : null,excluded,work);
    }
    static String description(String reason) {
        return switch (reason) {
            case "chat_missing" -> "В компании не указан клиентский чат";
            case "chat_unbound" -> "Ссылка указана, но чат не привязан к аккаунту отправки";
            case "chat_not_found" -> "Telegram не находит чат для текущего бота";
            case "max_chat_closed" -> "MAX подтвердил: клиентский чат закрыт. Укажите действующий чат и проверьте доступ бота";
            case "bot_removed", "bot_not_member", "not_member" -> "Аккаунт отправки больше не состоит в чате";
            case "bot_blocked" -> "Клиент заблокировал бота";
            case "chat_permission_denied" -> "У бота нет права писать в чат";
            case "delivery_rejected" -> "WhatsApp подтвердил отказ доставки; требуется проверка чата";
            case "route_changed_unverified" -> "Контакт изменён после подтверждённой ошибки; новый чат ещё не проверен";
            default -> "Доступ к клиентскому чату требует проверки";
        };
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Contact recheck(Long id) {
        var s = snapshot(id);
        if (s == null) throw new IllegalArgumentException("Компания не найдена");
        LocalDateTime started = LocalDateTime.now(java.time.ZoneOffset.UTC);
        Probe p;
        String missing = unavailable(s,null);
        if (missing != null) p = new Probe("UNAVAILABLE",missing);
        else if ("TELEGRAM".equals(s.platform())) {
            var result = telegram.check(s.telegram()); p = new Probe(result.status(), result.reason());
        } else if ("WHATSAPP".equals(s.platform())) {
            var result = whatsapp.check(s.client(),s.group()); p = new Probe(result.status(),result.reason());
        } else if ("MAX".equals(s.platform())) {
            var result = max.check(s.max()); p = new Probe(result.status(),result.reason());
        } else p = new Probe("UNKNOWN","access_unconfirmed");
        // CAS by time: a slower older probe cannot overwrite a newer result. An UNKNOWN result retains
        // confirmed unavailability only for the identical route; only AVAILABLE clears that evidence.
        jdbc.update("""
                INSERT INTO client_contact_health(company_id,route_hash,platform,check_status,reason_code,unavailable_reason,checked_at)
                SELECT ?,?,?,?,?,?,? FROM companies c LEFT JOIN managers m ON m.manager_id=c.company_manager
                WHERE c.company_id=? AND CAST(c.company_url_chat AS BINARY) <=> CAST(? AS BINARY)
                  AND CAST(m.client_id AS BINARY) <=> CAST(? AS BINARY)
                  AND CAST(c.company_group_id AS BINARY) <=> CAST(? AS BINARY)
                  AND c.company_telegram_group_chat_id <=> ? AND c.company_max_group_chat_id <=> ?
                ON DUPLICATE KEY UPDATE
                unavailable_reason=IF(checked_at<=VALUES(checked_at),
                    IF(VALUES(check_status)='UNKNOWN' AND unavailable_reason IS NOT NULL,
                        IF(route_hash=VALUES(route_hash),unavailable_reason,'route_changed_unverified'),VALUES(unavailable_reason)),unavailable_reason),
                platform=IF(checked_at<=VALUES(checked_at),VALUES(platform),platform),
                check_status=IF(checked_at<=VALUES(checked_at),VALUES(check_status),check_status),
                reason_code=IF(checked_at<=VALUES(checked_at),VALUES(reason_code),reason_code),
                route_hash=IF(checked_at<=VALUES(checked_at),VALUES(route_hash),route_hash),
                checked_at=GREATEST(checked_at,VALUES(checked_at))
                """,id,s.hash(),s.platform(),p.status(),p.reason(),"UNAVAILABLE".equals(p.status())?p.reason():null,Timestamp.valueOf(started),
                    id,s.url(),s.client(),s.group(),s.telegram(),s.max());
        // Always return current routing/eligibility, never the stale network snapshot.
        var current = snapshot(id);
        var result = contact(id);
        if (result != null) return result;
        var e = evidence(id);
        boolean same = current != null && e != null && current.hash().equals(e.hash());
        return new Contact(id,current == null ? s.title():current.title(),current == null ? s.status():current.status(),
                current == null ? s.managerId():current.managerId(),current == null ? s.platform():current.platform(),
                current == null ? s.url():current.url(),same?e.reason():"route_changed",
                same && "AVAILABLE".equals(e.status()) ? "Доступ к чату подтверждён. Старые сообщения не отправлялись повторно."
                    : "Доступ не подтверждён. Проверьте контакт; повторная отправка не выполнялась.",
                same?e.status():"UNKNOWN",same?e.checkedAt():null,false,hasActiveWork(id));
    }

    @Scheduled(fixedDelayString = "${client.contact-health.delay-ms:60000}", initialDelayString = "${client.contact-health.initial-delay-ms:90000}", scheduler = "clientContactHealthScheduler")
    public void checkDueContacts() {
        // Access checks are diagnostics, not autoresponder dispatch. Pausing sends must not hide a broken contact.
        var ids = jdbc.queryForList("""
                SELECT c.company_id FROM companies c LEFT JOIN client_contact_health h ON h.company_id=c.company_id
                LEFT JOIN company_status cs ON cs.company_status_id=c.company_status
                WHERE c.company_active=1 AND COALESCE(cs.status_title,'')<>'Бан'
                  AND (h.checked_at IS NULL OR h.checked_at < ?
                    OR (h.check_status='UNKNOWN' AND h.checked_at < ?))
                ORDER BY CASE WHEN EXISTS (
                    SELECT 1 FROM scheduled_client_message_state s
                    WHERE s.company_id=c.company_id AND s.state_status='ACTIVE'
                      AND (s.consecutive_failures>0 OR s.delivery_status='UNKNOWN')
                ) THEN 0 ELSE 1 END,h.checked_at,c.company_id LIMIT 10
                """,Long.class,Timestamp.valueOf(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(6)),
                    Timestamp.valueOf(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(15)));
        for (Long id : ids) recheck(id);
    }
}
