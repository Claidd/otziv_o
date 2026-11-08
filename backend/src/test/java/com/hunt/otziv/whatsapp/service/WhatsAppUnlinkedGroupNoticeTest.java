package com.hunt.otziv.whatsapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.CompanyHint;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.LinkState;
import com.hunt.otziv.whatsapp.service.WhatsAppUnlinkedGroupNotice.Sender;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WhatsAppUnlinkedGroupNoticeTest {
    private static final LocalDateTime SENT = LocalDateTime.of(2026, 11, 8, 20, 59, 9);

    private static WhatsAppUnlinkedGroupNotice mediafix(long newMessages, List<CompanyHint> hints, int more) {
        return new WhatsAppUnlinkedGroupNotice("ВИ Mediafix 2 гис", "whatsapp_vika", List.of("Вика Ц."), "Мария", Sender.STAFF,
                SENT, "Авто СПА, Mediafix - Mediafix,Полярная\n\nЗдравствуйте, ваш заказ выполнен. К оплате: 2400 руб.",
                newMessages, "company_missing", hints, more, LocalDateTime.of(2026, 11, 8, 21, 59, 20),
                LocalDateTime.of(2026, 12, 8, 20, 59, 10));
    }

    @Test
    void theNoticeSaysWhatGroupWhoAndWhatAndPointsAtTheCompanyThatAlreadyHasAnotherGroup() {
        String html = mediafix(1, List.of(new CompanyHint("Авто СПА, Mediafix", "В работе", "Вика Ц.",
                LinkState.OTHER_GROUP, "АБ Авто СПА 2 гис")), 0).html();

        assertThat(html).startsWith("🔴 <b>Сообщение из WhatsApp-группы без привязки к компании</b>");
        assertThat(html).contains("<b>Группа:</b> ВИ Mediafix 2 гис\n")
                .contains("<b>Аккаунт:</b> whatsapp_vika (Вика Ц.)\n")
                .contains("<b>Написал(а):</b> Мария — сотрудник, 08.11 20:59\n")
                .contains("<b>Текст:</b> Авто СПА, Mediafix - Mediafix,Полярная Здравствуйте, ваш заказ выполнен. К оплате: 2400 руб.\n")
                .contains("<b>Почему не привязана:</b> компания не найдена ни по этой группе, ни по телефону автора, ни по названию группы.")
                .contains("• Авто СПА, Mediafix — В работе, Вика Ц. Уже привязана к другой группе «АБ Авто СПА 2 гис».")
                .contains("Система снова поищет компанию 08.11 21:59")
                .contains("Если группу привяжут до 08.12, сообщения обработаются, иначе удалятся.")
                .contains("все сообщения бота по этой компании пойдут в новую группу");
        assertThat(html).doesNotContain("Новых сообщений").doesNotContain("и ещё");
        assertThat(html.length()).isLessThan(3900);
    }

    @Test
    void severalMessagesAndManyHintsAreSummedUp() {
        var hint = new CompanyHint("English World", "Бан", "Анжелика Б.", LinkState.BANNED, null);
        String html = mediafix(3, List.of(hint, new CompanyHint("World", null, null, LinkState.NO_GROUP, null),
                new CompanyHint("New World", "На стопе", null, LinkState.INVITE_PENDING, null)), 4).html();

        assertThat(html).contains("<b>Новых сообщений:</b> 3, выше последнее")
                .contains("• English World — Бан, Анжелика Б. В статусе «Бан» группа к ней не привязывается.")
                .contains("• World. Группа к ней не привязана.")
                .contains("• New World — На стопе. Ссылку на группу сменили, бот ждёт подтверждения новой группы.")
                .contains("• и ещё 4");
    }

    @Test
    void everyDynamicValueIsEscapedForTelegramHtml() {
        String html = new WhatsAppUnlinkedGroupNotice("<b>A&B</b>", "client<x>", List.of("M&M"), "<i>Bob</i>", Sender.CLIENT,
                SENT, "1 < 2 & 3 > 2", 1, "group_mismatch",
                List.of(new CompanyHint("R&D <Co>", "В <работе>", "A&B", LinkState.OTHER_GROUP, "<g>")), 0, null, null).html();

        assertThat(html).contains("<b>Группа:</b> &lt;b&gt;A&amp;B&lt;/b&gt;")
                .contains("client&lt;x&gt; (M&amp;M)")
                .contains("&lt;i&gt;Bob&lt;/i&gt; — клиент")
                .contains("1 &lt; 2 &amp; 3 &gt; 2")
                .contains("R&amp;D &lt;Co&gt; — В &lt;работе&gt;, A&amp;B. Уже привязана к другой группе «&lt;g&gt;».")
                .contains("найдена компания, но она уже привязана к другой группе")
                .contains("Система снова поищет компанию в течение получаса")
                .contains("Если группу привяжут, сообщения обработаются");
        // Only the notice's own markup is HTML.
        assertThat(html.replaceAll("</?b>", "")).doesNotContain("<").doesNotContain(">");
    }

    @Test
    void ourOwnAndSystemMessagesAndMissingValuesReadPlainly() {
        assertThat(new WhatsAppUnlinkedGroupNotice(null, null, List.of(), null, Sender.OUR_ACCOUNT, null, "  ", 1,
                "invite_pending", List.of(), 0, null, null).html())
                .contains("<b>Группа:</b> —\n")
                .contains("<b>Аккаунт:</b> —\n")
                .contains("<b>Написал(а):</b> без имени — наш аккаунт\n")
                .contains("<b>Текст:</b> без текста\n")
                .contains("сменили ссылку на группу; бот ещё не подтвердил новую группу")
                .contains("<b>Похожих компаний</b> по названию группы нет.");
        assertThat(new WhatsAppUnlinkedGroupNotice("G", "c", null, "Bot", Sender.SYSTEM, SENT, "x", 1, "other",
                null, 0, null, null).html())
                .contains("<b>Написал(а):</b> Bot — система, 08.11 20:59")
                .contains("компания для этой группы не найдена.");
    }

    @Test
    void longTextIsCutToThePreviewWithoutSplittingACharacter() {
        String text = "😀".repeat(WhatsAppUnlinkedGroupNotice.TEXT_PREVIEW + 50);
        String preview = WhatsAppUnlinkedGroupNotice.preview(text);
        assertThat(preview).endsWith("…");
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(WhatsAppUnlinkedGroupNotice.TEXT_PREVIEW + 1);
    }

    @Test
    void groupNamesMatchCompanyTitlesByWholeWords() {
        List<String> mediafix = WhatsAppGroupCompanyLinker.titleCandidates("ВИ Mediafix 2 гис");
        assertThat(mediafix).contains("mediafix");
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("Авто СПА, Mediafix", mediafix)).isEqualTo(1);
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("Mediafix", mediafix)).isZero();
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("Mediafixer", mediafix)).isNegative();

        List<String> english = WhatsAppGroupCompanyLinker.titleCandidates("English World. Отзывы");
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("English World", english)).isZero();
        // The group's name contains a whole company title.
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("World", english)).isEqualTo(2);
        assertThat(WhatsAppUnlinkedGroupNotifier.matchScore("Авто СПА, Mediafix", english)).isNegative();
        assertThat(WhatsAppGroupCompanyLinker.titleCandidates(null)).isEmpty();
    }
}
