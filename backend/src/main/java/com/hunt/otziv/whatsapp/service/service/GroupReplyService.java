package com.hunt.otziv.whatsapp.service.service;

import com.hunt.otziv.whatsapp.dto.WhatsAppGroupReplyDTO;

/** Manager/autoresponder group workflow. It deliberately stays outside cold outreach. */
public interface GroupReplyService {

    void processGroupReply(WhatsAppGroupReplyDTO groupReply);

    /** A sender edited a group message: recorded as evidence only, never as a new message or command. */
    default void processGroupEdit(WhatsAppGroupReplyDTO groupEdit) {
    }
}
