package com.hunt.otziv.review_recovery.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBotExclusionRepository;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewRecoveryBotExclusionService {

    private static final long STUB_BOT_ID = 1L;

    private final ReviewRecoveryBotExclusionRepository repository;

    @Transactional(readOnly = true)
    public Set<Long> excludedBotIds(Long taskId) {
        if (taskId == null) {
            return new HashSet<>();
        }
        Set<Long> result = repository.findBotIdsByTaskId(taskId);
        return result == null ? new HashSet<>() : new HashSet<>(result);
    }

    @Transactional
    public void reject(Long taskId, Bot bot, String reason) {
        if (taskId == null || bot == null || bot.getId() == null || STUB_BOT_ID == bot.getId()) {
            return;
        }
        repository.insertIgnore(taskId, bot.getId(), normalizeReason(reason));
    }

    @Transactional
    public int clearForTask(Long taskId) {
        return taskId == null ? 0 : repository.deleteByTaskId(taskId);
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "CHANGE";
        }
        String normalized = reason.trim().toUpperCase();
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }
}
