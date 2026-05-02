package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;
import com.hunt.otziv.whatsapp.dto.WhatsAppReplyDTO;

public interface ReplyService {
    void processIncomingReply(WhatsAppReplyDTO reply);

    void processGroupReply(WhatsAppGroupReplyDTO groupReply);
}
