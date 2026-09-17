package com.hunt.otziv.client_campaigns;

import static com.hunt.otziv.client_campaigns.CampaignModels.*;
import com.hunt.otziv.client_messages.api.ClientMessageDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Called only after CampaignStore has committed its durable SENDING barrier. No transport retries. */
@Service
public class CampaignSender {
    private final CampaignStore store;
    private final ClientMessageDelivery delivery;
    private final TelegramService telegram;
    private final MaxBotClient max;
    private final WhatsAppService whatsapp;
    private final String publicBaseUrl;
    public CampaignSender(CampaignStore store,ClientMessageDelivery delivery,TelegramService telegram,MaxBotClient max,
            WhatsAppService whatsapp,@Value("${client-offers.public-base-url:${OTZIV_APP_BASE_URL:https://o-ogo.ru}}") String publicBaseUrl) {
        this.store=store; this.delivery=delivery; this.telegram=telegram; this.max=max; this.whatsapp=whatsapp;
        this.publicBaseUrl=publicBaseUrl.replaceAll("/+$","");
    }
    public ClientMessageSendResult send(Claim claim) {
        var c = claim.campaign(); var r = claim.recipient();
        String text = c.settings().message();
        if (c.fileName() == null || "LINK".equals(c.settings().fileMode())) {
            if (c.fileName() != null) {
                if (!publicBaseUrl.matches("https?://[^/]+(?:/.*)?"))
                    return ClientMessageSendResult.failed("invalid_request","Не задан публичный адрес сайта для ссылки на файл");
                text += "\n\n" + c.fileName() + "\n" + publicBaseUrl + "/api/public/client-offer-files/" + c.fileToken();
            }
            return delivery.deliverWithOperationId(r.target(),r.clientId(),r.groupId(),text,null,r.operationId());
        }
        Attachment file;
        try { file = store.attachment(c.id()); }
        catch (RuntimeException failure) { return ClientMessageSendResult.failed("invalid_request","Не удалось прочитать файл рассылки"); }
        if (file == null) return ClientMessageSendResult.failed("invalid_request","Файл рассылки не найден");
        if (r.destinationKey().startsWith("TELEGRAM:")) {
            if (!telegram.canSendDocuments()) return ClientMessageSendResult.failed("invalid_request","Отправка Telegram выключена или бот не настроен");
            return telegram.sendDocumentOnceMessageId(r.telegramChatId(),file.bytes(),file.name(),text)
                    .map(id -> ClientMessageSendResult.sent("Telegram",id.toString())).orElseGet(CampaignSender::unknown);
        }
        if (r.destinationKey().startsWith("MAX:")) {
            if (!max.isConfigured()) return ClientMessageSendResult.failed("max_not_configured","MAX не настроен");
            String uploadToken;
            try {
                uploadToken = store.maxUploadToken(c.id());
                if (uploadToken == null) {
                    uploadToken = max.uploadDocument(file.bytes(),file.name());
                    store.rememberMaxUpload(c.id(),uploadToken);
                }
            } catch (RuntimeException failure) {
                return ClientMessageSendResult.failed("invalid_request","Не удалось загрузить файл в MAX; сообщение не отправлялось");
            }
            var result = max.sendDocumentToChatOnce(r.maxChatId(),text,uploadToken);
            return result.confirmed() ? ClientMessageSendResult.sent("MAX",result.messageId())
                    : ClientMessageSendResult.failed(result.errorCode(),"MAX не подтвердил доставку. Если файл ещё обрабатывается, повторите ошибку позже");
        }
        if (r.destinationKey().startsWith("WHATSAPP:"))
            return whatsapp.sendDocumentToGroupOnce(r.clientId(),r.groupId(),text,file.bytes(),file.name(),file.contentType(),r.operationId());
        return ClientMessageSendResult.failed("chat_platform_unknown","Канал чата не распознан");
    }
    private static ClientMessageSendResult unknown() {
        return ClientMessageSendResult.failed("operation_unknown","Доставка не подтверждена; проверьте чат без повторной отправки");
    }
}
