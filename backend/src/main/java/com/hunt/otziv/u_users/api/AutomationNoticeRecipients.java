package com.hunt.otziv.u_users.api;

import java.util.List;

/** Identity-owned recipients for a committed automatic order archive. No caller-supplied destination. */
public interface AutomationNoticeRecipients {
    List<Long> archiveRecipients(Long managerId);
}
