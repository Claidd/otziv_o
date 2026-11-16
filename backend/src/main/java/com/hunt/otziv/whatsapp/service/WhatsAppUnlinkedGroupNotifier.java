package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.service.ClientChatParticipantClassifier;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore.NoticeBatch;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore.NoticeGroup;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore.AccountNotice;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.CompanyHint;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.LinkState;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.Sender;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;

/**
 * Tells the admin chats about messages from WhatsApp groups that no company is linked to, with
 * everything needed to act on them at once. Runs after the recovery replay, outside the inbound
 * transaction: a message whose group got linked by the replay never produces a notice. A notice is
 * marked sent only after an admin chat accepted it, so a Telegram failure delays it, never drops it;
 * WhatsAppUnlinkedNoticeQueueHealth reports a due notice that waits.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WhatsAppUnlinkedGroupNotifier {
    static final int PREVIEW_LIMIT = 10;
    static final int MAX_HINTS = 3;
    static final int MIN_CANDIDATE_LENGTH = 3;
    /** Unlinked messages are kept this long (see WhatsAppUnlinkedEventStore.expireStale). */
    static final int KEEP_DAYS = 30;
    /** The database and the managers' working day are on Irkutsk time. */
    static final ZoneId ZONE = ZoneId.of("Asia/Irkutsk");

    private final WhatsAppUnlinkedEventStore store;
    private final JdbcTemplate jdbc;
    private final ClientChatParticipantClassifier participants;
    private final TelegramService telegram;
    private final WhatsAppService whatsApp;

    /** Sends the due notices; returns how many groups were announced. */
    public int notifyDue() {
        if (!telegram.canSendAdminAlerts()) {
            // Sending is off (local stand) or not configured: the notice queue metric shows the backlog.
            return 0;
        }
        // A second scheduler must not send the same snapshot while the first is talking to Telegram.
        // This connection-scoped mutex does not lock inbound messages, companies or payment rows.
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            try (var acquire = connection.prepareStatement("SELECT GET_LOCK('otziv.whatsapp.unlinked.notice',0)");
                 var release = connection.prepareStatement("SELECT RELEASE_LOCK('otziv.whatsapp.unlinked.notice')")) {
                try (var lock = acquire.executeQuery()) {
                    if (!lock.next() || lock.getInt(1) != 1) return 0;
                }
                try {
                    return sendDueAccounts();
                } finally {
                    release.execute();
                }
            }
        });
    }

    private int sendDueAccounts() {
        int sent = 0;
        for (AccountNotice account : store.dueNotices()) {
            try {
                var names = new GroupNames();
                var html = account.singleGroup().map(batch -> {
                    var group = new NoticeGroup(account.clientId(), batch.latest().getGroupId());
                    return notice(group, batch, names.resolve(group, account.groupNames().get(group.groupId()))).html();
                }).orElseGet(() -> digest(account, names));
                if (!telegram.sendHtmlAlertToAdmins(html)) break;
                store.markNotified(account.receipts());
                sent += account.groupNames().size();
            } catch (RuntimeException failure) {
                log.warn("Unlinked WhatsApp group notice failed: clientId={}, exceptionType={}",
                        account.clientId(), failure.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** Previews the notice of every group with open messages, whether or not it was sent. */
    public List<Preview> previews(int offset) {
        List<Preview> result = new ArrayList<>();
        var names = new GroupNames();
        for (var group : store.previewGroups(offset, PREVIEW_LIMIT)) {
            store.latestBatch(group).ifPresent(batch ->
                    result.add(new Preview(group.clientId(), group.groupId(),
                            notice(group, batch, names.resolve(group, batch.latest().getGroupName())).html())));
        }
        return result;
    }

    public record Preview(String clientId, String groupId, String html) { }

    /** One directory read per account per pass, only for display; never mutate the saved webhook. */
    private class GroupNames {
        private final Map<String,Map<String,String>> directories = new HashMap<>();

        String resolve(NoticeGroup group, String reported) {
            if (reported != null && !reported.isBlank()) return reported;
            return directories.computeIfAbsent(group.clientId(), this::readDirectory).get(group.groupId());
        }

        private Map<String,String> readDirectory(String clientId) {
            var names = new HashMap<String,String>();
            try {
                var snapshot = whatsApp.groupsSnapshot(clientId);
                if (snapshot.available()) {
                    for (var group : snapshot.groups()) {
                        if (group.name() != null && !group.name().isBlank()) names.put(group.groupId(), group.name());
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("WhatsApp group directory unavailable for admin notice: clientId={}, exceptionType={}",
                        clientId, failure.getClass().getSimpleName());
            }
            return names;
        }
    }

    private String digest(AccountNotice account, GroupNames names) {
        var titles = account.groupNames().entrySet().stream().limit(PREVIEW_LIMIT)
                .map(entry -> {
                    var group = new NoticeGroup(account.clientId(), entry.getKey());
                    var name = names.resolve(group, entry.getValue());
                    return name == null ? group.groupId() : name;
                }).toList();
        return WhatsAppUnlinkedGroupNotice.digest(account.clientId(), account.groupNames().size(),
                account.receipts().size(), account.stickers(), titles);
    }

    private WhatsAppUnlinkedGroupNotice notice(NoticeGroup group, NoticeBatch batch, String name) {
        WhatsAppGroupReplyDTO reply = batch.latest();
        List<SimilarCompany> hints = similarCompanies(name);
        List<CompanyHint> shown = hints.stream().limit(MAX_HINTS).map(this::hint).toList();
        return new WhatsAppUnlinkedGroupNotice(
                name == null ? group.groupId() : name,
                group.clientId(),
                accountManagers(group.clientId()),
                reply.getFromName(),
                sender(reply),
                sentAt(reply, batch.latestAt()),
                reply.getMessage(),
                batch.newEvents(),
                batch.latestReason(),
                shown,
                hints.size() - shown.size(),
                batch.nextAttemptAt(),
                batch.firstSeenAt() == null ? null : batch.firstSeenAt().plusDays(KEEP_DAYS)
        );
    }

    private List<String> accountManagers(String clientId) {
        return jdbc.queryForList("""
                SELECT u.fio FROM managers m JOIN users u ON u.id=m.user_id
                WHERE m.client_id=? AND u.fio IS NOT NULL AND u.fio<>'' ORDER BY m.manager_id
                """, String.class, clientId);
    }

    private Sender sender(WhatsAppGroupReplyDTO reply) {
        if (reply.isFromMe()) {
            return Sender.OUR_ACCOUNT;
        }
        if (Boolean.TRUE.equals(reply.getSystemGenerated())) {
            return Sender.SYSTEM;
        }
        try {
            return switch (participants.classify(ClientChatPlatform.WHATSAPP, ClientChatDirection.INCOMING,
                    reply.getGroupId(), reply.getFrom(), reply.getFromName(), null)) {
                case STAFF -> Sender.STAFF;
                case CLIENT -> Sender.CLIENT;
                case BOT -> Sender.SYSTEM;
                case UNKNOWN -> Sender.UNKNOWN;
            };
        } catch (RuntimeException unavailable) {
            return Sender.UNKNOWN;
        }
    }

    private static LocalDateTime sentAt(WhatsAppGroupReplyDTO reply, LocalDateTime savedAt) {
        Long timestamp = reply.getTimestamp();
        return timestamp == null || timestamp <= 0
                ? savedAt
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZONE);
    }

    record SimilarCompany(String title, String status, String manager, LinkState state, String linkedGroupId, int score) { }

    /** Active companies whose title matches the group name: the same title first, then contained either way. */
    List<SimilarCompany> similarCompanies(String groupName) {
        List<String> candidates = WhatsAppGroupCompanyLinker.titleCandidates(groupName).stream()
                .filter(candidate -> candidate.length() >= MIN_CANDIDATE_LENGTH).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<SimilarCompany> matches = new ArrayList<>();
        jdbc.query("""
                SELECT c.company_title,s.status_title,c.company_group_id,c.company_whatsapp_invite_pending,u.fio
                FROM companies c
                LEFT JOIN company_status s ON s.company_status_id=c.company_status
                LEFT JOIN managers m ON m.manager_id=c.company_manager
                LEFT JOIN users u ON u.id=m.user_id
                WHERE c.company_active=1
                """, rs -> {
            int score = matchScore(rs.getString(1), candidates);
            if (score < 0) {
                return;
            }
            String status = rs.getString(2);
            String groupId = rs.getString(3);
            LinkState state = status != null && status.trim().equalsIgnoreCase("бан") ? LinkState.BANNED
                    : rs.getBoolean(4) ? LinkState.INVITE_PENDING
                    : groupId != null && !groupId.isBlank() ? LinkState.OTHER_GROUP
                    : LinkState.NO_GROUP;
            matches.add(new SimilarCompany(rs.getString(1), status, rs.getString(5), state,
                    groupId == null || groupId.isBlank() ? null : groupId.trim(), score));
        });
        return matches.stream()
                .sorted(Comparator.comparingInt(SimilarCompany::score).thenComparing(SimilarCompany::title,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** 0 the same title, 1 the company title contains the group's name, 2 the group's name contains the title. */
    static int matchScore(String companyTitle, List<String> candidates) {
        String title = WhatsAppGroupCompanyLinker.normalizedTitle(companyTitle);
        if (title.length() < MIN_CANDIDATE_LENGTH) {
            return -1;
        }
        String paddedTitle = " " + title + " ";
        int best = -1;
        for (String candidate : candidates) {
            int score = title.equals(candidate) ? 0
                    : paddedTitle.contains(" " + candidate + " ") ? 1
                    : (" " + candidate + " ").contains(paddedTitle) ? 2
                    : -1;
            if (score >= 0 && (best < 0 || score < best)) {
                best = score;
            }
        }
        return best;
    }

    /** The shown hint names the group the company is already linked to, as the chat history last saw it. */
    private CompanyHint hint(SimilarCompany company) {
        String linkedGroupTitle = null;
        if (company.state() == LinkState.OTHER_GROUP) {
            List<String> titles = jdbc.queryForList("""
                    SELECT chat_title FROM client_chat_messages
                    WHERE platform='WHATSAPP' AND chat_id=? AND chat_title IS NOT NULL AND chat_title<>''
                    ORDER BY message_at DESC LIMIT 1
                    """, String.class, company.linkedGroupId());
            linkedGroupTitle = titles.isEmpty() ? null : titles.getFirst();
        }
        return new CompanyHint(company.title(), company.status(), company.manager(), company.state(), linkedGroupTitle);
    }
}
