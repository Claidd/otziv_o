package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;
import com.hunt.otziv.whatsapp.service.service.ReplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ReplyServiceImpl implements ReplyService {

    public void processIncomingReply(WhatsAppReplyDTO reply) {
        log.info("📩 Ответ от клиента {} ({}): {}", reply.getClientId(), reply.getFrom(), reply.getMessage());
        String telephoneNumber = reply.getFrom().replaceAll("@c\\.us$", "");
        log.info("📞 Извлечён номер телефона: {}", telephoneNumber);
        // Здесь можно реализовать:
        // - поиск лида по номеру
        // - изменение его статуса (например, "Ответ получен")
        // - отправку уведомления менеджеру
        // - запись сообщения в историю и т.д.
    }
}

