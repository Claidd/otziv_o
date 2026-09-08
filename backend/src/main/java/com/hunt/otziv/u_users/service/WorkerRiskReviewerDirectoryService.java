package com.hunt.otziv.u_users.service;

import com.hunt.otziv.u_users.api.WorkerRiskReviewerDirectory;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves managed associations inside identity and exports scalar snapshots only. */
@Service
@RequiredArgsConstructor
public class WorkerRiskReviewerDirectoryService implements WorkerRiskReviewerDirectory {
    private final UserService userService;

    @Override
    @Transactional(readOnly = true)
    public List<Reviewer> reviewersForWorker(Long workerUserId) {
        User worker = userService.findByIdToUserInfo(workerUserId);
        if (worker == null || !Objects.equals(worker.getId(), workerUserId)) {
            return List.of();
        }
        Map<Long, Reviewer> recipients = new LinkedHashMap<>();
        if (worker.getManagers() != null) {
            worker.getManagers().stream().filter(Objects::nonNull).map(Manager::getUser)
                    .forEach(user -> addRecipient(recipients, user));
        }
        addRecipients(recipients, userService.getAllOwners("ROLE_OWNER"));
        addRecipients(recipients, userService.getAllOwners("ROLE_ADMIN"));
        recipients.remove(worker.getId());
        return List.copyOf(recipients.values());
    }

    private void addRecipients(Map<Long, Reviewer> recipients, List<User> users) {
        if (users != null) {
            users.forEach(user -> addRecipient(recipients, user));
        }
    }

    private void addRecipient(Map<Long, Reviewer> recipients, User user) {
        if (user != null && user.getId() != null && user.isActive()) {
            recipients.putIfAbsent(user.getId(), new Reviewer(user.getId(), user.getTelegramChatId()));
        }
    }
}
