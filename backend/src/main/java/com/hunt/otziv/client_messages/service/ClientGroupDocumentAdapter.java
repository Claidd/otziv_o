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
            var result=max.sendDocumentToChatOnce(Long.parseLong(destination.substring(4)),caption,max.uploadDocument(bytes,filename));
            return result.confirmed()?ClientMessageSendResult.sent("MAX",result.messageId()):ClientMessageSendResult.failed("operation_unknown","MAX не подтвердил доставку");
        }
        return ClientMessageSendResult.failed("invalid_request","Канал группы не определён");
    }
}
