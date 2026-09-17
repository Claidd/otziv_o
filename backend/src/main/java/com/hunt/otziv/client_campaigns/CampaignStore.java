package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;

import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@RequiredArgsConstructor
public class CampaignStore {
    private final JdbcTemplate jdbc;

    public List<Campaign> list() {
        return jdbc.query("SELECT * FROM client_offer_campaign ORDER BY created_at DESC LIMIT 100", this::campaign);
    }
    public Campaign get(String id) {
        return jdbc.query("SELECT * FROM client_offer_campaign WHERE id=?", this::campaign, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Рассылка не найдена"));
    }
    private Campaign lock(String id) {
        return jdbc.query("SELECT * FROM client_offer_campaign WHERE id=? FOR UPDATE", this::campaign, id).stream()
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Рассылка не найдена"));
    }
    public Counts counts(String id) {
        Map<String, Long> values = new HashMap<>();
        jdbc.query("SELECT state, COUNT(*) AS n FROM client_offer_recipient WHERE campaign_id=? GROUP BY state",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> values.put(r.getString(1), r.getLong(2)), id);
        return new Counts(values.values().stream().mapToLong(Long::longValue).sum(), values.getOrDefault("PENDING", 0L),
                values.getOrDefault("SENDING", 0L), values.getOrDefault("SENT", 0L), values.getOrDefault("FAILED", 0L),
                values.getOrDefault("UNKNOWN", 0L), values.getOrDefault("SKIPPED", 0L));
    }
    public List<Recipient> recipients(String id, int page) {
        return jdbc.query("SELECT * FROM client_offer_recipient WHERE campaign_id=? ORDER BY priority,company_id LIMIT 100 OFFSET ?",
                this::recipient, id, Math.max(0, page) * 100L);
    }
    public Attachment attachment(String id) {
        return jdbc.query("""
                SELECT c.file_name,c.file_type,f.content FROM client_offer_campaign c
                JOIN client_offer_campaign_file f ON f.campaign_id=c.id WHERE c.id=?
                """, (r, n) -> new Attachment(r.getString(1), r.getString(2), r.getBytes(3)), id).stream()
                .findFirst().orElse(null);
    }
    public Attachment publicAttachment(String token) {
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var ids = jdbc.queryForList("SELECT id FROM client_offer_campaign WHERE file_token=? AND started_at IS NOT NULL",
                String.class, token);
        return ids.isEmpty() ? null : attachment(ids.getFirst());
    }
    public String maxUploadToken(String id) {
        return jdbc.queryForObject("SELECT max_upload_token FROM client_offer_campaign_file WHERE campaign_id=?",String.class,id);
    }
    public void rememberMaxUpload(String id, String token) {
        jdbc.update("UPDATE client_offer_campaign_file SET max_upload_token=? WHERE campaign_id=?",token,id);
    }

    @Transactional
    public Campaign save(String id, Settings s, Attachment file, boolean removeFile, String actor, LocalDateTime now) {
        CampaignSchedule.validate(s);
        // Client-generated UUID makes a retried create update the same draft.
        jdbc.update("""
                INSERT IGNORE INTO client_offer_campaign
                (id,title,message,daily_limit,interval_minutes,window_start,window_end,include_active,include_stopped,
                 include_banned,created_by,created_at,file_mode,file_token) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id,s.title(),s.message(),s.dailyLimit(),s.intervalMinutes(),s.windowStart(),s.windowEnd(),
                s.includeActive(),s.includeStopped(),s.includeBanned(),actor,now,s.fileMode(),UUID.randomUUID().toString());
        var current = lock(id);
        requireState(current, "DRAFT");
        jdbc.update("""
                UPDATE client_offer_campaign SET title=?,message=?,daily_limit=?,interval_minutes=?,window_start=?,
                window_end=?,include_active=?,include_stopped=?,include_banned=?,file_mode=? WHERE id=?
                """, s.title().trim(),s.message().trim(),s.dailyLimit(),s.intervalMinutes(),s.windowStart(),s.windowEnd(),
                s.includeActive(),s.includeStopped(),s.includeBanned(),s.fileMode(),id);
        if (removeFile || file != null) jdbc.update("DELETE FROM client_offer_campaign_file WHERE campaign_id=?", id);
        if (file != null) {
            jdbc.update("INSERT INTO client_offer_campaign_file (campaign_id,content) VALUES (?,?)", id,file.bytes());
            jdbc.update("UPDATE client_offer_campaign SET file_name=?,file_type=? WHERE id=?", file.name(),file.contentType(),id);
        } else if (removeFile) jdbc.update("UPDATE client_offer_campaign SET file_name=NULL,file_type=NULL WHERE id=?", id);
        return get(id);
    }

    private List<Recipient> audience(Settings settings) {
        List<Recipient> rows = jdbc.query("""
                SELECT c.company_id,c.company_title,c.company_url_chat,c.company_group_id,
                       c.company_telegram_group_chat_id,c.company_max_group_chat_id,m.client_id,s.status_title
                FROM companies c JOIN company_status s ON s.company_status_id=c.company_status
                LEFT JOIN managers m ON m.manager_id=c.company_manager
                WHERE s.status_title IN ('В работе','На стопе','Бан')
                ORDER BY CASE s.status_title WHEN 'В работе' THEN 1 WHEN 'На стопе' THEN 2 ELSE 3 END,c.company_id
                """, (r,n) -> {
            long companyId = r.getLong("company_id");
            String group = CampaignAudience.group(r.getString("status_title"));
            String url = r.getString("company_url_chat"), client = r.getString("client_id"), chat = r.getString("company_group_id");
            Long tg = r.getObject("company_telegram_group_chat_id", Long.class), max = r.getObject("company_max_group_chat_id", Long.class);
            String destination = CampaignAudience.destination(url,client,chat,tg,max);
            return new Recipient(0,null,companyId,Objects.toString(r.getString("company_title"),"Компания"),group,
                    CampaignAudience.priority(group),destination == null ? "MISSING:"+companyId : destination,url,client,chat,tg,max,
                    destination == null ? "SKIPPED" : "PENDING",null,destination == null ? "Не настроен привязанный чат компании" : null,null);
        });
        Set<String> destinations = new HashSet<>();
        return rows.stream().filter(r -> CampaignAudience.included(r.audience(),settings))
                .filter(r -> destinations.add(r.destinationKey())).toList();
    }
    public List<AudienceCount> preview(Settings settings) {
        CampaignSchedule.validate(settings);
        var recipients = audience(settings);
        return List.of("ACTIVE","STOPPED","BANNED").stream().map(group -> new AudienceCount(group,
                recipients.stream().filter(r -> group.equals(r.audience())).count(),
                recipients.stream().filter(r -> group.equals(r.audience()) && "PENDING".equals(r.state())).count())).toList();
    }

    @Transactional
    public void start(String id, LocalDateTime now) {
        var c = lock(id);
        if ("RUNNING".equals(c.state())) return;
        requireState(c,"DRAFT");
        List<Recipient> rows = audience(c.settings());
        if (rows.stream().noneMatch(r -> "PENDING".equals(r.state())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"В выбранных списках нет клиентов с настроенным чатом");
        jdbc.batchUpdate("""
                INSERT INTO client_offer_recipient (campaign_id,company_id,company_title,audience,priority,destination_key,
                chat_url,client_id,group_id,telegram_chat_id,max_chat_id,state,operation_id,error_message)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, rows, 250, (ps,r) -> {
            Object[] values = {id,r.companyId(),r.companyTitle(),r.audience(),r.priority(),r.destinationKey(),r.chatUrl(),
                    r.clientId(),r.groupId(),r.telegramChatId(),r.maxChatId(),r.state(),"offer:"+id+":"+r.companyId(),r.errorMessage()};
            for (int i=0;i<values.length;i++) ps.setObject(i+1,values[i]);
        });
        jdbc.update("UPDATE client_offer_campaign SET state='RUNNING',started_at=?,next_at=? WHERE id=?",
                now,CampaignSchedule.allowed(now,c.settings()),id);
    }

    @Transactional
    public void action(String id, String action, LocalDateTime now) {
        var c = lock(id);
        switch (action) {
            case "pause" -> {
                if ("PAUSED".equals(c.state())) return;
                requireState(c,"RUNNING");
                jdbc.update("UPDATE client_offer_campaign SET state='PAUSED' WHERE id=?",id);
            }
            case "resume" -> {
                if ("RUNNING".equals(c.state())) return;
                requireState(c,"PAUSED");
                jdbc.update("UPDATE client_offer_campaign SET state='RUNNING' WHERE id=?",id);
            }
            case "cancel" -> {
                if ("CANCELLED".equals(c.state())) return;
                requireState(c,"DRAFT","RUNNING","PAUSED");
                jdbc.update("UPDATE client_offer_campaign SET state='CANCELLED' WHERE id=?",id);
                jdbc.update("UPDATE client_offer_recipient SET state='SKIPPED',error_message='Рассылка остановлена',finished_at=? WHERE campaign_id=? AND state='PENDING'",now,id);
            }
            case "retry-failed" -> {
                requireState(c,"PAUSED","COMPLETED");
                LocalDateTime lastFinished = jdbc.queryForObject("SELECT MAX(finished_at) FROM client_offer_recipient WHERE campaign_id=?",LocalDateTime.class,id);
                LocalDateTime next = c.nextAt();
                if (next == null) {
                    next = lastFinished == null ? now : lastFinished.plusMinutes(c.settings().intervalMinutes());
                    if (next.isBefore(now)) next = now;
                    next = CampaignSchedule.allowed(next,c.settings());
                }
                // Only proven unsent attempts enter FAILED. UNKNOWN is never replayed.
                jdbc.update("UPDATE client_offer_recipient SET state='PENDING',error_message=NULL,finished_at=NULL WHERE campaign_id=? AND state='FAILED'",id);
                jdbc.update("UPDATE client_offer_campaign SET state='PAUSED',next_at=? WHERE id=?",next,id);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Неизвестное действие");
        }
    }

    public List<String> due(LocalDateTime now) {
        return jdbc.queryForList("SELECT id FROM client_offer_campaign WHERE state='RUNNING' AND next_at<=? ORDER BY next_at LIMIT 20",String.class,now);
    }

    @Transactional
    public Claim claim(String id, LocalDateTime now) {
        var c = lock(id);
        if (!"RUNNING".equals(c.state()) || c.nextAt() == null || c.nextAt().isAfter(now)) return null;
        // A process crash or lost acknowledgement leaves a barrier, never a resend.
        jdbc.update("""
                UPDATE client_offer_recipient SET state='UNKNOWN',error_message='Отправка прервалась; проверьте чат, автоматический повтор заблокирован',finished_at=?
                WHERE campaign_id=? AND state='SENDING' AND claimed_at<?
                """,now,id,now.minusMinutes(30));
        if (countState(id,"SENDING") > 0) return null;
        if (countState(id,"PENDING") == 0) {
            jdbc.update("UPDATE client_offer_campaign SET state='COMPLETED',next_at=NULL WHERE id=?",id);
            return null;
        }
        var allowed = CampaignSchedule.allowed(now,c.settings());
        if (allowed.isAfter(now)) {
            jdbc.update("UPDATE client_offer_campaign SET next_at=? WHERE id=?",allowed,id);
            return null;
        }
        var day = CampaignSchedule.day(now);
        int used = day.equals(c.budgetDay()) ? c.budgetUsed() : 0;
        if (used >= c.settings().dailyLimit()) {
            jdbc.update("UPDATE client_offer_campaign SET next_at=? WHERE id=?",CampaignSchedule.nextDay(now,c.settings()),id);
            return null;
        }
        var r = jdbc.query("SELECT * FROM client_offer_recipient WHERE campaign_id=? AND state='PENDING' ORDER BY priority,company_id LIMIT 1 FOR UPDATE",
                this::recipient,id).getFirst();
        jdbc.update("UPDATE client_offer_recipient SET state='SENDING',claimed_at=? WHERE id=?",now,r.id());
        // Reserve quota before transport. Unknown outcomes count too, so the limit cannot be exceeded.
        jdbc.update("UPDATE client_offer_campaign SET budget_day=?,budget_used=?,next_at=? WHERE id=?",
                day,used+1,CampaignSchedule.allowed(now.plusMinutes(c.settings().intervalMinutes()),c.settings()),id);
        return new Claim(c,r);
    }

    @Transactional
    public void finish(Claim claim, ClientMessageSendResult result, LocalDateTime now) {
        var c = lock(claim.campaign().id());
        String state = result != null && result.sent() ? "SENT" : ClientMessageDelivery.isKnownUnsent(result) ? "FAILED" : "UNKNOWN";
        String error = result == null ? "Результат отправки неизвестен" : result.errorMessage();
        jdbc.update("UPDATE client_offer_recipient SET state=?,finished_at=?,error_message=?,message_id=? WHERE id=? AND state IN ('SENDING','UNKNOWN')",
                state,now,truncate(error,1000),result == null ? null : result.messageId(),claim.recipient().id());
        // Use completion time: slow network requests must not shorten the interval.
        var next = CampaignSchedule.allowed(now.plusMinutes(c.settings().intervalMinutes()),c.settings());
        if (c.nextAt() != null && c.nextAt().isAfter(next)) next = c.nextAt();
        jdbc.update("UPDATE client_offer_campaign SET next_at=? WHERE id=?",next,c.id());
        if ("RUNNING".equals(c.state()) && countState(c.id(),"PENDING") == 0 && countState(c.id(),"SENDING") == 0)
            jdbc.update("UPDATE client_offer_campaign SET state='COMPLETED',next_at=NULL WHERE id=?",c.id());
    }

    @Transactional
    public void releaseWithoutSend(Claim claim) {
        var c = lock(claim.campaign().id());
        jdbc.update("UPDATE client_offer_recipient SET state=?,claimed_at=NULL WHERE id=? AND state='SENDING'",
                "CANCELLED".equals(c.state()) ? "SKIPPED" : "PENDING",claim.recipient().id());
    }
    private long countState(String id, String state) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM client_offer_recipient WHERE campaign_id=? AND state=?",Long.class,id,state));
    }
    private static void requireState(Campaign c, String... allowed) {
        if (!Arrays.asList(allowed).contains(c.state())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Действие недоступно в текущем состоянии рассылки");
    }
    private static String truncate(String s, int length) { return s == null || s.length() <= length ? s : s.substring(0,length); }
    private Campaign campaign(ResultSet r, int n) throws SQLException {
        return new Campaign(r.getString("id"),new Settings(r.getString("title"),r.getString("message"),r.getInt("daily_limit"),
                r.getInt("interval_minutes"),r.getString("window_start"),r.getString("window_end"),r.getBoolean("include_active"),
                r.getBoolean("include_stopped"),r.getBoolean("include_banned"),r.getString("file_mode")),r.getString("state"),
                r.getObject("created_at",LocalDateTime.class),r.getObject("started_at",LocalDateTime.class),r.getObject("next_at",LocalDateTime.class),
                r.getObject("budget_day",java.time.LocalDate.class),r.getInt("budget_used"),r.getString("file_name"),r.getString("file_type"),r.getString("file_token"));
    }
    private Recipient recipient(ResultSet r, int n) throws SQLException {
        return new Recipient(r.getLong("id"),r.getString("campaign_id"),r.getLong("company_id"),r.getString("company_title"),
                r.getString("audience"),r.getInt("priority"),r.getString("destination_key"),r.getString("chat_url"),r.getString("client_id"),
                r.getString("group_id"),r.getObject("telegram_chat_id",Long.class),r.getObject("max_chat_id",Long.class),r.getString("state"),
                r.getString("operation_id"),r.getString("error_message"),r.getObject("finished_at",LocalDateTime.class));
    }
}
