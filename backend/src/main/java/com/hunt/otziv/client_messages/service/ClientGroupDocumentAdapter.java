package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ClientGroupDocumentDelivery;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ClientGroupDocumentAdapter implements ClientGroupDocumentDelivery {
    public static final String MAX_UPLOAD_FAILED = "max_upload_failed";
    private final WhatsAppService whatsapp;
    private final TelegramService telegram;
    private final MaxBotClient max;
    public ClientMessageSendResult send(String client,String destination,String caption,byte[] bytes,String filename,String operation) {
        if(destination.startsWith("WHATSAPP:"))
            return whatsapp.sendDocumentToGroupOnce(client,destination.substring(9),caption,bytes,filename,"application/pdf",operation);
        if(destination.startsWith("TELEGRAM:"))
            return telegram.sendDocumentOnceMessageId(Long.parseLong(destination.substring(9)),bytes,filename,caption)
                    .map(id -> ClientMessageSendResult.sent("Telegram",id.toString()))
                    .orElseGet(() -> ClientMessageSendResult.failed("operation_unknown","Telegram не подтвердил доставку"));
        if(destination.startsWith("MAX:")) {
            String upload;
            try { upload=max.uploadDocument(bytes,filename); }
            // The file is uploaded before any message: nothing reached the chat, so the send may be repeated.
            catch(RuntimeException uploadFailed) { return ClientMessageSendResult.failed(MAX_UPLOAD_FAILED,"Файл не загрузился в MAX; сообщение клиенту не отправлялось"); }
            var result=max.sendDocumentToChatOnce(Long.parseLong(destination.substring(4)),caption,upload);
            if(result.confirmed()) return ClientMessageSendResult.sent("MAX",result.messageId());
            if(MaxBotClient.ATTACHMENT_NOT_READY.equals(result.errorCode()))
                return ClientMessageSendResult.failed(MaxBotClient.ATTACHMENT_NOT_READY,"MAX ещё обрабатывает файл; сообщение не отправлено и будет повторено");
            if("max_not_configured".equals(result.errorCode()) || "invalid_request".equals(result.errorCode()))
                return ClientMessageSendResult.failed(result.errorCode(),"MAX не принял файл до отправки");
            return ClientMessageSendResult.failed("operation_unknown","MAX не подтвердил доставку");
        }
        return ClientMessageSendResult.failed("invalid_request","Канал группы не определён");
    }
}
