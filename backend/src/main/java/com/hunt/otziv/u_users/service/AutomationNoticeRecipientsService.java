package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.AutomationNoticeRecipients;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutomationNoticeRecipientsService implements AutomationNoticeRecipients {
    private final ManagerRepository managers;
    private final UserService users;

    @Override @Transactional(readOnly = true)
    public List<Long> archiveRecipients(Long managerId) {
        List<Long> result = new ArrayList<>();
        if (managerId != null) managers.findById(managerId).ifPresent(manager -> {
            if (manager.getAuditTelegramGroupChatId() != null) result.add(manager.getAuditTelegramGroupChatId());
            else if (manager.getUser() != null && manager.getUser().isActive()) result.add(manager.getUser().getTelegramChatId());
        });
        for (String role : List.of("ROLE_OWNER", "ROLE_ADMIN")) {
            var recipients = users.getAllOwners(role);
            if (recipients != null) recipients.stream().filter(user -> user != null && user.isActive())
                    .forEach(user -> result.add(user.getTelegramChatId()));
        }
        return result.stream().filter(id -> id != null && id != 0).distinct().toList();
    }
}
