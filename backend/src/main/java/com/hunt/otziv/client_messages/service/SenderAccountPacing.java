package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.repository.SenderDispatchLedger;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One sending account's interval, shared by the autoresponder family and offer campaigns (MSG-5).
 *
 * <p>The autoresponder keeps its own per-channel interval; this adds the account level: a WhatsApp
 * client id, or the single Telegram or MAX bot. Any lane may use an account once the channel gap has
 * passed since that account's last dispatch. When one lane was held back by the other, the lane that
 * took the previous slot yields the next one, so neither a running campaign nor a busy autoresponder
 * can starve the other. A WAITING mark older than two gaps is ignored: a lane that stopped asking
 * does not keep blocking.
 *
 * <p>Manual messages of the manager control (reply to a client, manual delivery) are recorded on the
 * same account as {@link #MANUAL_LANE} and never wait here: they only move the account's next free
 * slot, so the background lanes keep the channel gap after an operator's message. They take no part
 * in the turn alternation between the two background lanes.
 *
 * <p>Callers hold {@code ScheduledClientMessageStateRepository.lockDispatchBudget()} in the same
 * transaction, so decisions for one account are serialized with every other background sender.
 */
@Component
public class SenderAccountPacing {

    public enum Lane { AUTORESPONDER, CAMPAIGN }

    /** Why a lane waits: the account's interval, or the other lane's turn. */
    public enum Hold { INTERVAL, TURN }

    public record Decision(boolean allowed, LocalDateTime nextAt, Hold hold, String reason) {
        static Decision allow() {
            return new Decision(true, null, null, null);
        }

        /**
         * Stored/returned error code. The same {@code rate_limited} as the per-channel interval, so the
         * manager control, order status and queue views keep treating it as ordinary waiting.
         */
        public String code() {
            return allowed ? null : "rate_limited";
        }
    }

    static final int RETENTION_DAYS = 3;
    /** Ledger lane of an operator's message; recorded only as SENT and never admitted or held. */
    public static final String MANUAL_LANE = "MANUAL";
    private static final int MAX_ACCOUNT_LENGTH = 191;

    private final SenderDispatchLedger ledger;

    public SenderAccountPacing(SenderDispatchLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * Ledger key of the account that sends on {@code channel}, or {@code null} when it is not known
     * (no WhatsApp client id, unknown channel); such sends keep only the per-channel rules.
     */
    public static String account(String channel, String senderClientId) {
        String normalized = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        String account = switch (normalized) {
            case "WHATSAPP" -> senderClientId == null || senderClientId.isBlank() ? null : "WHATSAPP:" + senderClientId.trim();
            case "TELEGRAM" -> "TELEGRAM";
            case "MAX" -> "MAX";
            default -> null;
        };
        return account == null || account.length() <= MAX_ACCOUNT_LENGTH ? account : account.substring(0, MAX_ACCOUNT_LENGTH);
    }

    /** Admits one dispatch on {@code account} or says when to retry; records SENT or WAITING. */
    public Decision admit(Lane lane, String account, int gapSeconds, LocalDateTime now) {
        if (account == null) {
            return Decision.allow();
        }
        Map<String, LocalDateTime> latest = ledger.latest(account);
        Lane other = lane == Lane.CAMPAIGN ? Lane.AUTORESPONDER : Lane.CAMPAIGN;
        Decision decision = decide(lane, now, gapSeconds,
                latest.get(SenderDispatchLedger.SENT + ":" + lane.name()),
                latest.get(SenderDispatchLedger.SENT + ":" + other.name()),
                latest.get(SenderDispatchLedger.WAITING + ":" + other.name()),
                latest.get(SenderDispatchLedger.SENT + ":" + MANUAL_LANE));
        if (decision.allowed()) {
            ledger.purgeOlderThan(now.minusDays(RETENTION_DAYS));
            ledger.record(account, lane.name(), SenderDispatchLedger.SENT, now);
        } else if (shouldMarkWaiting(now, gapSeconds, latest.get(SenderDispatchLedger.WAITING + ":" + lane.name()),
                latestOf(latest.get(SenderDispatchLedger.SENT + ":" + lane.name()),
                        latest.get(SenderDispatchLedger.SENT + ":" + other.name())))) {
            ledger.record(account, lane.name(), SenderDispatchLedger.WAITING, now);
        }
        return decision;
    }

    /**
     * Records an operator's message about to leave from {@code account}. It is never held: the
     * background lanes see it as the account's latest dispatch and keep the gap after it.
     */
    public void recordManual(String account, LocalDateTime now) {
        if (account == null || now == null) {
            return;
        }
        ledger.purgeOlderThan(now.minusDays(RETENTION_DAYS));
        ledger.record(account, MANUAL_LANE, SenderDispatchLedger.SENT, now);
    }

    static Decision decide(Lane lane, LocalDateTime now, int gapSeconds, LocalDateTime ownLastSent,
                           LocalDateTime otherLastSent, LocalDateTime otherWaitingAt) {
        return decide(lane, now, gapSeconds, ownLastSent, otherLastSent, otherWaitingAt, null);
    }

    /** {@code manualLastSent} moves the next free slot only; the turn is decided between the two lanes. */
    static Decision decide(Lane lane, LocalDateTime now, int gapSeconds, LocalDateTime ownLastSent,
                           LocalDateTime otherLastSent, LocalDateTime otherWaitingAt, LocalDateTime manualLastSent) {
        LocalDateTime laneLastSent = latestOf(ownLastSent, otherLastSent);
        LocalDateTime lastSent = latestOf(laneLastSent, manualLastSent);
        if (lastSent == null) {
            return Decision.allow();
        }
        LocalDateTime free = lastSent.plusSeconds(gapSeconds);
        if (free.isAfter(now)) {
            boolean manualLatest = manualLastSent != null && (laneLastSent == null || manualLastSent.isAfter(laneLastSent));
            return new Decision(false, free, Hold.INTERVAL, (manualLatest
                    ? "Аккаунт отправителя только что отправил сообщение менеджера вручную; следующий слот: "
                    : "Аккаунт отправителя занят другой рассылкой; следующий слот: ") + free.withNano(0));
        }
        if (laneLastSent == null) {
            return Decision.allow();
        }
        boolean tookPreviousSlot = ownLastSent != null && !ownLastSent.isBefore(laneLastSent);
        boolean otherWaiting = otherWaitingAt != null && otherWaitingAt.isAfter(laneLastSent)
                && !otherWaitingAt.isBefore(now.minusSeconds(2L * gapSeconds + 60));
        if (tookPreviousSlot && otherWaiting) {
            String owner = lane == Lane.CAMPAIGN ? "автоответчика" : "рассылки предложений";
            return new Decision(false, now.plusSeconds(Math.min(gapSeconds, 60)), Hold.TURN,
                    "Следующий слот аккаунта отправителя отдан сообщению " + owner);
        }
        return Decision.allow();
    }

    /** One WAITING mark per gap is enough to claim the next turn. */
    static boolean shouldMarkWaiting(LocalDateTime now, int gapSeconds, LocalDateTime ownWaitingAt, LocalDateTime lastSent) {
        return ownWaitingAt == null
                || lastSent != null && !ownWaitingAt.isAfter(lastSent)
                || ownWaitingAt.isBefore(now.minusSeconds(gapSeconds));
    }

    private static LocalDateTime latestOf(LocalDateTime first, LocalDateTime second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }
}
