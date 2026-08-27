package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadForcedSingleRecipientService {

    static final String FORCE_REASON = "Принудительная передача: единственный "
            + "получатель не принял предложение, других вариантов нет";
    static final String LOTTERY_FORCE_REASON = "Принудительная передача: "
            + "все кандидаты отказались или не ответили, получатель выбран жеребьёвкой";

    private final WorkloadTransferOfferRepository offerRepository;
    private final WorkloadShadowEventRepository eventRepository;
    private final WorkloadShadowSettingsService shadowSettingsService;

    @Transactional
    public int acceptExhaustedQueues(LocalDateTime now) {
        return acceptExhausted(null, now);
    }

    @Transactional
    public int acceptExhaustedWorkflow(long workflowId, LocalDateTime now) {
        return acceptExhausted(workflowId, now);
    }

    private int acceptExhausted(Long workflowId, LocalDateTime now) {
        int singleChanged = 0;
        int lotteryChanged = 0;
        Set<Long> attemptedWorkflowIds = new HashSet<>();
        var targets = offerRepository.lockSingleRecipientForcedTransfers(workflowId);
        for (var target : targets) {
            if (target.getWorkflowId() == null
                    || target.getCandidateId() == null
                    || target.getOfferId() == null
                    || !attemptedWorkflowIds.add(target.getWorkflowId())) {
                continue;
            }
            int candidateCount = target.getCandidateCount() == null
                    ? 1
                    : Math.max(1, target.getCandidateCount());
            String reason = candidateCount == 1
                    ? FORCE_REASON
                    : LOTTERY_FORCE_REASON;
            int changed = offerRepository.forceSingleRecipientAcceptedAfterNoResponse(
                    target.getWorkflowId(),
                    target.getCandidateId(),
                    target.getOfferId(),
                    now,
                    reason
            );
            if (changed <= 0) {
                continue;
            }
            if (candidateCount == 1) {
                singleChanged += changed;
            } else {
                lotteryChanged += changed;
            }
        }
        int changed = singleChanged + lotteryChanged;
        if (changed <= 0) {
            return 0;
        }
        var settings = shadowSettingsService.current();
        if (singleChanged > 0) {
            eventRepository.upsertSingleRecipientForcedTransferEvents(
                    now,
                    FORCE_REASON,
                    settings.groupNotificationsEnabled(),
                    settings.notificationGroupChatId(),
                    now
            );
        }
        if (lotteryChanged > 0) {
            eventRepository.upsertExhaustedQueueForcedTransferEvents(
                    now,
                    LOTTERY_FORCE_REASON,
                    settings.groupNotificationsEnabled(),
                    settings.notificationGroupChatId(),
                    now
            );
        }
        return changed;
    }
}
