package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.service.ClientChatParticipantClassifier;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore.NoticeBatch;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedEventStore.NoticeGroup;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.CompanyHint;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.LinkState;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.Sender;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
    static final int GROUPS_PER_RUN = 10;
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

    /** Sends the due notices; returns how many groups were announced. */
    public int notifyDue() {
        if (!telegram.canSendAdminAlerts()) {
            // Sending is off (local stand) or not configured: the notice queue metric shows the backlog.
            return 0;
        }
        int sent = 0;
        for (NoticeGroup group : store.noticeDueGroups(GROUPS_PER_RUN)) {
            try {
                var batch = store.noticeBatch(group);
                if (batch.isEmpty()) {
                    continue;
                }
                if (!telegram.sendHtmlAlertToAdmins(notice(group, batch.get()).html())) {
                    log.warn("Unlinked WhatsApp group notice was not accepted and stays pending: groupId={}", group.groupId());
                    // Telegram refused: the other groups wait for the next run instead of failing one by one.
                    break;
                }
                store.markNotified(group, batch.get().latestAt());
                sent++;
            } catch (RuntimeException failure) {
                log.warn("Unlinked WhatsApp group notice failed: groupId={}, exceptionType={}",
                        group.groupId(), failure.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /** Previews the notice of every group with open messages, whether or not it was sent. */
    public List<Preview> previews() {
        List<Preview> result = new ArrayList<>();
        for (var row : store.unresolvedGroups()) {
            var group = new NoticeGroup((String) row.get("client_id"), (String) row.get("group_id"));
            if (result.stream().anyMatch(preview -> preview.clientId().equals(group.clientId())
                    && preview.groupId().equals(group.groupId()))) {
                continue;
            }
            store.latestBatch(group).ifPresent(batch ->
                    result.add(new Preview(group.clientId(), group.groupId(), notice(group, batch).html())));
            if (result.size() >= GROUPS_PER_RUN) {
                break;
            }
        }
        return result;
    }

    public record Preview(String clientId, String groupId, String html) { }

    WhatsAppUnlinkedGroupNotice notice(NoticeGroup group, NoticeBatch batch) {
        WhatsAppGroupReplyDTO reply = batch.latest();
        List<SimilarCompany> hints = similarCompanies(reply.getGroupName());
        List<CompanyHint> shown = hints.stream().limit(MAX_HINTS).map(this::hint).toList();
        return new WhatsAppUnlinkedGroupNotice(
                reply.getGroupName(),
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
