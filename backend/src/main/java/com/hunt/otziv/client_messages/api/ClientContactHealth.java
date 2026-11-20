package com.hunt.otziv.client_messages.api;

import java.time.LocalDateTime;
import java.util.List;

/** Contact access evidence, separate from message receipts. Methods never send or retry messages. */
public interface ClientContactHealth {
    String HELD_CODE = "inactive_company_chat_unavailable";
    record Contact(Long companyId, String title, String companyStatus, Long managerId,
            String platform, String url, String reason, String description, String checkStatus,
            LocalDateTime checkedAt, boolean excluded, boolean activeWork) { }
    boolean adminOnly(Long companyId);
    boolean hasActiveWork(Long companyId);
    /** Live company or an outstanding order/request: delivery failures need manager attention. */
    default boolean requiresManagerAttention(Long companyId) { return hasActiveWork(companyId); }
    record Routing(java.util.Set<Long> excluded, java.util.Set<Long> active) { }
    Routing routing(Long managerId);
    List<Contact> managerIssues(Long managerId);
    List<Contact> excludedCompanies();
    Contact recheck(Long companyId);
    /** Checks the exact new message route against current data; historical operations bypass this. */
    boolean holdNewMessage(ClientMessageDelivery.Target target, String clientId, String groupId);
}
