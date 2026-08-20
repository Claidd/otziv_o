package com.hunt.otziv.workload_shadow.service;

import com.hunt.otziv.workload_shadow.repository.WorkloadShadowEventRepository;
import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkloadForcedSingleRecipientService {

    static final String FORCE_REASON = "Принудительная передача: единственный "
            + "получатель не принял предложение, других вариантов нет";

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
        int changed = 0;
        var targets = offerRepository.lockSingleRecipientForcedTransfers(workflowId);
        for (var target : targets) {
            if (target.getWorkflowId() == null
                    || target.getCandidateId() == null
                    || target.getOfferId() == null) {
                continue;
            }
            changed += offerRepository.forceSingleRecipientAcceptedAfterNoResponse(
                    target.getWorkflowId(),
                    target.getCandidateId(),
                    target.getOfferId(),
                    now,
                    FORCE_REASON
            );
        }
        if (changed <= 0) {
            return 0;
        }
        var settings = shadowSettingsService.current();
        eventRepository.upsertSingleRecipientForcedTransferEvents(
                now,
                FORCE_REASON,
                settings.groupNotificationsEnabled(),
                settings.notificationGroupChatId(),
                now
        );
        return changed;
    }
}
