package com.hunt.otziv.client_messages.api;

/**
 * The words people see for each autoresponder scenario: the switch on «Справочники → Автоответчик» that runs it and
 * what a manager does by hand while the autoresponder does not. One source for the autoresponder's own task messages
 * and the manager-control cards, so both name the same switch the same way.
 */
public final class ClientMessageScenarioSwitches {

    public static final String SETTINGS_PAGE = "Справочники → Автоответчик";
    public static final String MASTER_SWITCH = "Фоновая очередь задач работает";

    private ClientMessageScenarioSwitches() {
    }

    /** The scenario's own switch as the settings page names it; null when it has none (only the master switch). */
    public static String switchTitle(String scenario) {
        return switch (scenario == null ? "" : scenario) {
            case "CLIENT_TEXT_REMINDER" -> "Напоминать, если ждем текст клиента";
            case "REVIEW_CHECK_REMINDER" -> "Напоминать о проверке шаблонов";
            case "REVIEW_CHECK_AUTO_ARCHIVE" -> "Архив после 7 напоминаний";
            case "PAYMENT_REMINDER" -> "Напоминать об оплате";
            case "PAYMENT_OVERDUE_ESCALATION" -> "Контролировать просрочку оплаты";
            case "BAD_REVIEW_INVOICE" -> "Счет после плохого отзыва";
            case "BAD_REVIEW_AUTO_BAN" -> "Автобан после плохих отзывов";
            case "REVIEW_RECOVERY_NOTICE" -> "Уведомлять клиента после восстановления";
            case "ARCHIVE_REORDER_OFFER" -> "Предлагать новый заказ архивным компаниям";
            default -> null;
        };
    }

    /**
     * Who turns it back on, for a reason as {@code ClientMessageAutomationPolicy.stopReason} words it. The switches
     * are shared by all clients: there is none on a company or an order, and a manager cannot change them.
     */
    public static String whoTurnsItOn(String reason) {
        String text = reason == null ? "" : reason;
        if (text.contains("на паузе")) return "Пауза снимется сама, тогда автоответчик продолжит.";
        if (text.contains("переключатель")) {
            return "Это общий переключатель для всех клиентов: его включают владелец или администратор.";
        }
        return "Включить автоответчик могут владелец или администратор.";
    }

    /** What the manager does by hand for this order while the autoresponder stays off. */
    public static String manualAction(String scenario) {
        return switch (scenario == null ? "" : scenario) {
            case "CLIENT_TEXT_REMINDER" -> "Попросите у клиента текст или пожелания сами.";
            case "REVIEW_CHECK_REMINDER", "REVIEW_CHECK_DELIVERY_RETRY" ->
                    "Напомните клиенту проверить шаблоны отзывов и при необходимости отправьте ссылку сами.";
            case "PAYMENT_REMINDER", "PAYMENT_INVOICE_RETRY", "BAD_REVIEW_INVOICE" ->
                    "Напомните клиенту об оплате и при необходимости отправьте счёт сами.";
            case "PAYMENT_OVERDUE_ESCALATION" ->
                    "Проверьте оплату и, если её нет, переведите заказ в «Не оплачено» сами.";
            case "REVIEW_CHECK_AUTO_ARCHIVE" -> "Решите сами, переводить ли заказ в архив.";
            default -> "Сделайте этот шаг по заказу сами.";
        };
    }
}
