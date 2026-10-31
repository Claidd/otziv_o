package com.hunt.otziv.u_users.api;

import java.util.Collection;
import java.util.List;

/** Identity-owned admin and responsible-manager notice policy. Null chat remains a durable pending recipient. */
public interface PaymentNoticeRecipients {
    record Recipient(long userId,Long managerId,Long chatId) {}
    List<Recipient> forManagers(Collection<Long> managerIds);
    Long currentChat(long userId,Long managerId);
}
