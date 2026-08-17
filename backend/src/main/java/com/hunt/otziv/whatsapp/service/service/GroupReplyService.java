package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;

/** Manager/autoresponder group workflow. It deliberately stays outside cold outreach. */
public interface GroupReplyService {

    void processGroupReply(WhatsAppGroupReplyDTO groupReply);
}
