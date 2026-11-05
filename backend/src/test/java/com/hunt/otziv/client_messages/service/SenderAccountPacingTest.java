package com.hunt.otziv.client_messages.service;

import static com.hunt.otziv.client_messages.service.SenderAccountPacing.Lane.AUTORESPONDER;
import static com.hunt.otziv.client_messages.service.SenderAccountPacing.Lane.CAMPAIGN;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SenderAccountPacingTest {

    private static final int GAP = 180;
    private static final String ACCOUNT = "WHATSAPP:lika";
    private final LocalDateTime t0 = LocalDateTime.of(2026, 11, 4, 11, 0);
    private final InMemorySenderDispatchLedger ledger = new InMemorySenderDispatchLedger();
    private final SenderAccountPacing pacing = new SenderAccountPacing(ledger);

    @Test
    void accountIsTheWhatsAppClientOrTheSingleBot() {
        assertThat(SenderAccountPacing.account("WhatsApp", " lika ")).isEqualTo("WHATSAPP:lika");
        assertThat(SenderAccountPacing.account("WHATSAPP", null)).isNull();
        assertThat(SenderAccountPacing.account("WhatsApp", " ")).isNull();
        assertThat(SenderAccountPacing.account("Telegram", "lika")).isEqualTo("TELEGRAM");
        assertThat(SenderAccountPacing.account("max", null)).isEqualTo("MAX");
        assertThat(SenderAccountPacing.account("ANY", "lika")).isNull();
        assertThat(SenderAccountPacing.account(null, "lika")).isNull();
        assertThat(SenderAccountPacing.account("WhatsApp", "x".repeat(300))).hasSize(191);
    }

    @Test
    void campaignWaitsForTheAutoresponderSendOnTheSameAccountOnly() {
        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0).allowed()).isTrue();

        var held = pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(30));
        assertThat(held.allowed()).isFalse();
        assertThat(held.hold()).isEqualTo(SenderAccountPacing.Hold.INTERVAL);
        assertThat(held.nextAt()).isEqualTo(t0.plusSeconds(GAP));
        assertThat(pacing.admit(CAMPAIGN, "WHATSAPP:vika", GAP, t0.plusSeconds(30)).allowed()).isTrue();

        // One WAITING mark per gap, however often the campaign ticks.
        pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(40));
        pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(50));
        assertThat(ledger.count(ACCOUNT, "CAMPAIGN", "WAITING")).isEqualTo(1);
    }

    @Test
    void lanesAlternateWhenBothWaitForOneAccount() {
        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0).allowed()).isTrue();
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(30)).allowed()).isFalse();

        // The autoresponder took the previous slot and the campaign is waiting: its turn.
        var yielded = pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0.plusSeconds(GAP));
        assertThat(yielded.allowed()).isFalse();
        assertThat(yielded.hold()).isEqualTo(SenderAccountPacing.Hold.TURN);
        assertThat(yielded.nextAt()).isEqualTo(t0.plusSeconds(GAP + 60));
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(GAP + 5)).allowed()).isTrue();

        // Now the autoresponder is the waiting lane and the campaign yields.
        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0.plusSeconds(GAP + 60)).hold())
                .isEqualTo(SenderAccountPacing.Hold.INTERVAL);
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(2 * GAP + 5)).hold())
                .isEqualTo(SenderAccountPacing.Hold.TURN);
        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0.plusSeconds(2 * GAP + 10)).allowed()).isTrue();
        assertThat(ledger.count(ACCOUNT, "AUTORESPONDER", "SENT")).isEqualTo(2);
        assertThat(ledger.count(ACCOUNT, "CAMPAIGN", "SENT")).isEqualTo(1);
    }

    @Test
    void aLaneAloneUsesEveryGapAndAStaleWaitingMarkStopsBlocking() {
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0).allowed()).isTrue();
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(GAP)).allowed()).isTrue();

        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0.plusSeconds(GAP + 10)).allowed()).isFalse();
        // The autoresponder gave up (state closed); after two gaps its mark no longer holds the campaign.
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(2 * GAP)).hold()).isEqualTo(SenderAccountPacing.Hold.TURN);
        assertThat(pacing.admit(CAMPAIGN, ACCOUNT, GAP, t0.plusSeconds(GAP + 10 + 2 * GAP + 61)).allowed()).isTrue();
    }

    @Test
    void unknownAccountKeepsOnlyChannelRules() {
        assertThat(pacing.admit(CAMPAIGN, null, GAP, t0).allowed()).isTrue();
        assertThat(ledger.rows).isEmpty();
    }

    @Test
    void oldRowsArePurgedWhenANewSlotIsRecorded() {
        ledger.record(ACCOUNT, "CAMPAIGN", "SENT", t0.minusDays(4));
        assertThat(pacing.admit(AUTORESPONDER, ACCOUNT, GAP, t0).allowed()).isTrue();
        assertThat(ledger.rows).extracting(InMemorySenderDispatchLedger.Row::at).containsExactly(t0);
    }
}
