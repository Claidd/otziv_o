package com.hunt.otziv.whatsapp.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The admin notice about a message from a WhatsApp group that is not linked to any company: what the
 * group is, who wrote what, why the system could not attribute it, which company it probably belongs
 * to, what happens next and what to do. Telegram HTML: every dynamic value is escaped here.
 */
public record WhatsAppUnlinkedGroupNotice(
        String groupName,
        String clientId,
        List<String> accountManagers,
        String senderName,
        Sender sender,
        LocalDateTime sentAt,
        String text,
        long newMessages,
        String reason,
        List<CompanyHint> hints,
        int moreHints,
        LocalDateTime nextAttemptAt,
        LocalDateTime deleteAt
) {
    static final int TEXT_PREVIEW = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    public enum Sender { OUR_ACCOUNT, SYSTEM, STAFF, CLIENT, UNKNOWN }

    public enum LinkState { OTHER_GROUP, BANNED, INVITE_PENDING, NO_GROUP }

    public record CompanyHint(String title, String status, String manager, LinkState state, String linkedGroupTitle) { }

    public String html() {
        var out = new StringBuilder("🔴 <b>Сообщение из WhatsApp-группы без привязки к компании</b>\n\n");
        line(out, "Группа", escape(orDash(groupName)));
        line(out, "Аккаунт", escape(orDash(clientId) + (accountManagers == null || accountManagers.isEmpty()
                ? "" : " (" + String.join(", ", accountManagers) + ")")));
        line(out, "Написал(а)", author());
        line(out, "Текст", preview(text));
        if (newMessages > 1) {
            line(out, "Новых сообщений", newMessages + ", выше последнее");
        }
        out.append('\n');
        line(out, "Почему не привязана", reasonText(reason));
        out.append('\n');
        if (hints == null || hints.isEmpty()) {
            out.append("<b>Похожих компаний</b> по названию группы нет.\n");
        } else {
            out.append("<b>Похожие компании:</b>\n");
            for (CompanyHint hint : hints) {
                out.append("• ").append(hintText(hint)).append('\n');
            }
            if (moreHints > 0) {
                out.append("• и ещё ").append(moreHints).append('\n');
            }
        }
        out.append('\n');
        line(out, "Что будет", "сообщение не попадает в карточки компаний, автоответов нет. Система снова поищет компанию "
                + (nextAttemptAt == null ? "в течение получаса" : nextAttemptAt.format(TIME))
                + ", дальше реже, до раза в сутки. Если группу привяжут"
                + (deleteAt == null ? "" : " до " + deleteAt.format(DAY))
                + ", сообщения обработаются, иначе удалятся.");
        line(out, "Что сделать", "если это группа клиента, укажите ссылку на неё в карточке компании. Если у компании уже "
                + "есть другая группа, после замены ссылки все сообщения бота по этой компании пойдут в новую группу. "
                + "Если группа не клиентская, ничего делать не нужно.");
        return out.toString().trim();
    }

    private String author() {
        String name = hasText(senderName) ? escape(senderName.trim()) : "без имени";
        String role = switch (sender == null ? Sender.UNKNOWN : sender) {
            case OUR_ACCOUNT -> "наш аккаунт";
            case SYSTEM -> "система";
            case STAFF -> "сотрудник";
            case CLIENT -> "клиент";
            case UNKNOWN -> "";
        };
        String when = sentAt == null ? "" : sentAt.format(TIME);
        String details = String.join(", ", java.util.stream.Stream.of(role, when).filter(WhatsAppUnlinkedGroupNotice::hasText).toList());
        return details.isEmpty() ? name : name + " — " + details;
    }

    static String reasonText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "company_missing" -> "компания не найдена ни по этой группе, ни по телефону автора, ни по названию группы.";
            case "invite_pending" -> "по телефону автора и названию найдена компания, у которой сменили ссылку на группу; "
                    + "бот ещё не подтвердил новую группу.";
            case "group_mismatch" -> "по телефону автора и названию найдена компания, но она уже привязана к другой группе.";
            default -> "компания для этой группы не найдена.";
        };
    }

    private static String hintText(CompanyHint hint) {
        var out = new StringBuilder(escape(orDash(hint.title())));
        String details = String.join(", ", java.util.stream.Stream.of(hint.status(), hint.manager())
                .filter(WhatsAppUnlinkedGroupNotice::hasText).map(String::trim).toList());
        if (!details.isEmpty()) {
            out.append(" — ").append(escape(details));
        }
        // A manager written as «Вика Ц.» already ends the sentence.
        out.append(out.charAt(out.length() - 1) == '.' ? " " : ". ").append(switch (hint.state()) {
            case BANNED -> "В статусе «Бан» группа к ней не привязывается.";
            case OTHER_GROUP -> hasText(hint.linkedGroupTitle())
                    ? "Уже привязана к другой группе «" + escape(hint.linkedGroupTitle().trim()) + "»."
                    : "Уже привязана к другой группе.";
            case INVITE_PENDING -> "Ссылку на группу сменили, бот ждёт подтверждения новой группы.";
            case NO_GROUP -> "Группа к ней не привязана.";
        });
        return out.toString();
    }

    static String preview(String text) {
        if (!hasText(text)) {
            return "без текста";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.codePointCount(0, flat.length()) > TEXT_PREVIEW) {
            flat = flat.substring(0, flat.offsetByCodePoints(0, TEXT_PREVIEW)).trim() + "…";
        }
        return escape(flat);
    }

    private static void line(StringBuilder out, String label, String escapedValue) {
        out.append("<b>").append(label).append(":</b> ").append(escapedValue).append('\n');
    }

    private static String orDash(String value) {
        return hasText(value) ? value.trim() : "—";
    }

    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
