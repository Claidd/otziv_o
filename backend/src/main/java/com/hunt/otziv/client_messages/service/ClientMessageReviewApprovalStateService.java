package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.client_messages.api.ReviewApprovalReminderStates;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Application API for common billing's reminder threshold workflow. */
@Service
@RequiredArgsConstructor
public class ClientMessageReviewApprovalStateService implements ReviewApprovalReminderStates {

    private final ScheduledClientMessageStateRepository stateRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Candidate> candidatesAfter(long afterId, int limit) {
        return stateRepository.findReviewApprovalCandidates(afterId, PageRequest.of(0, limit)).stream()
                .map(candidate -> new Candidate(candidate.getStateId(), candidate.getInvoiceId()))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Evidence> lockEligible(Long stateId, int requiredReminders) {
        return stateRepository.findByIdForUpdate(stateId)
                .filter(state -> state.getScenario() == ClientMessageScenario.REVIEW_CHECK_REMINDER
                        && state.getStatus() == ScheduledMessageStateStatus.ACTIVE
                        && state.getSentCount() >= requiredReminders
                        && state.getOrderId() != null
                        && state.getDeliveryStatus() == null)
                .map(state -> new Evidence(state.getOrderId(), state.getSentCount(), state.getTargetKey()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Long stateId) {
        ScheduledClientMessageState state = stateRepository.findByIdForUpdate(stateId).orElseThrow();
        state.setStatus(ScheduledMessageStateStatus.DONE);
        state.setNextAttemptAt(null);
        state.setLockedUntil(null);
        state.setLastErrorCode(null);
        state.setLastErrorMessage(null);
        stateRepository.save(state);
    }
}
