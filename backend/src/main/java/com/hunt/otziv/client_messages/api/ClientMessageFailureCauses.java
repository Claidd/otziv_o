package com.hunt.otziv.client_messages.api;

import java.util.Locale;
import java.util.Optional;

/**
 * Why an autoresponder message did not reach the client, and what a person does about it, in plain words. One source
 * for the manager-control cards, so every failure code a card shows comes with a cause and a next step instead of
 * «подробности сохранены для администратора».
 */
public final class ClientMessageFailureCauses {

    /** «Причина: …» and «Что сделать: …» of one failure code. */
    public record Cause(String cause, String action) {
        public String text() {
            return "Причина: " + cause + " Что сделать: " + action;
        }
    }

    private ClientMessageFailureCauses() {
    }

    public static Optional<Cause> of(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        return Optional.ofNullable(switch (normalized) {
            case "gateway_not_ready", "draining", "gateway_unavailable", "whatsapp_unavailable", "gateway_timeout",
                 "operation_unknown", "operation_pending", "remote_service_unavailable" -> new Cause(
                    "WhatsApp-аккаунт отправки (номер менеджера) сейчас не на связи: шлюз перезапускается, телефон "
                            + "потерял интернет или аккаунт ждёт входа по QR-коду. Клиенту ничего не ушло.",
                    "система повторит сама в рабочее время. Если сообщения не уходят дольше часа — проверьте, что "
                            + "телефон с этим номером включён и в сети, и попросите администратора переподключить аккаунт.");
            case "whatsapp_group_unavailable", "not_member", "whatsapp_delivery_rejected", "delivery_rejected" -> new Cause(
                    "WhatsApp не доставляет сообщения в группу клиента: номер менеджера больше не в группе или "
                            + "ссылка-приглашение сброшена. Клиент сообщение не получил.",
                    "попросите клиента снова добавить номер менеджера в группу или прислать новую ссылку, сохраните "
                            + "её в карточке компании и нажмите «Починить».");
            case "whatsapp_group_missing", "missing_group_id" -> new Cause(
                    "группа WhatsApp клиента не привязана к компании.",
                    "откройте карточку компании и привяжите чат по ссылке-приглашению.");
            case "telegram_group_missing", "chat_not_found", "bot_removed", "bot_blocked", "chat_permission_denied",
                 "bot_not_member", "telegram_chat_unavailable" -> new Cause(
                    "бот Telegram не в группе клиента или не может в ней писать.",
                    "добавьте бота в группу снова (кнопка «Привязать Telegram») и дайте ему право писать.");
            case "max_group_missing" -> new Cause(
                    "группа MAX клиента не привязана к компании.",
                    "откройте карточку компании и привяжите чат MAX.");
            case "max_chat_unavailable" -> new Cause(
                    "бот MAX не имеет доступа к группе клиента или группа не найдена.",
                    "добавьте бота MAX в группу клиента снова и проверьте привязку чата в карточке компании.");
            case "telegram_not_configured", "max_not_configured" -> new Cause(
                    "отправка через этот мессенджер выключена или бот не настроен.",
                    "попросите администратора проверить настройки бота.");
            case "whatsapp_client_missing", "unknown_client", "missing_client" -> new Cause(
                    "у менеджера компании не указан аккаунт, с которого отправлять сообщения.",
                    "попросите администратора указать менеджеру аккаунт отправки в справочнике «Аккаунты».");
            case "empty_client_url", "chat_platform_unknown" -> new Cause(
                    "в карточке компании нет ссылки на чат клиента или она не распознана.",
                    "сохраните в карточке компании ссылку на группу WhatsApp, Telegram или MAX.");
            case "payment_instruction_failed" -> new Cause(
                    "не удалось подготовить счёт или ссылку на оплату для сообщения.",
                    "откройте заказ и проверьте способ оплаты и получателя, затем нажмите «Починить».");
            case "order_missing" -> new Cause(
                    "заказ, к которому относится сообщение, удалён или недоступен.",
                    "ничего отправлять не нужно; если заказ должен быть — восстановите его.");
            case "rolled_back_before_send" -> new Cause(
                    "сбой сохранения на сервере прервал подготовку; клиенту ничего не ушло.",
                    "система повторит сама. Если ошибка повторяется — сообщите администратору.");
            case "message_empty", "missing_message" -> new Cause(
                    "текст сообщения пустой.",
                    "проверьте шаблон текста в «Справочники → Автоответчик».");
            default -> null;
        });
    }
}
