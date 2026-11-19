package com.hunt.otziv.whatsapp.service;

import com.hunt.otziv.whatsapp.api.WhatsAppChatAccess;
import com.hunt.otziv.whatsapp.api.WhatsAppGroupDeliveryBlocks;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WhatsAppChatAccessService implements WhatsAppChatAccess {
    private final WhatsAppService whatsapp;
    private final WhatsAppGroupDeliveryBlocks blocks;

    @Override public Result check(String clientId, String groupId) {
        try {
            if (blocks.isBlocked(clientId, groupId)) return new Result("UNAVAILABLE", "delivery_rejected");
            var snapshot = whatsapp.groupsSnapshot(clientId);
            if (snapshot == null || !snapshot.available() || snapshot.stale() || snapshot.groups() == null)
                return new Result("UNKNOWN", "group_list_unavailable");
            var group = snapshot.groups().stream().filter(it -> groupId.equals(it.groupId())).findFirst().orElse(null);
            if (group == null) return new Result("UNKNOWN", "not_listed");
            if ("not_member".equals(group.inviteStatus())) return new Result("UNAVAILABLE", "not_member");
            if ("ok".equals(group.inviteStatus()) || "not_admin".equals(group.inviteStatus()))
                return new Result("AVAILABLE", "member");
            return new Result("UNKNOWN", "membership_unconfirmed");
        } catch (RuntimeException unavailable) {
            return new Result("UNKNOWN", "group_list_unavailable");
        }
    }
}
