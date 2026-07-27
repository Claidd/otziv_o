package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDispute;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewDisputeStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssueStatus;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewDisputeRepository;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReportReviewIssueRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewIssueService {

    private static final List<ManagerReportReviewDisputeStatus> UNRESOLVED_DISPUTES = List.of(
            ManagerReportReviewDisputeStatus.DRAFT,
            ManagerReportReviewDisputeStatus.OPEN,
            ManagerReportReviewDisputeStatus.NEEDS_CONTEXT
    );

    private final ManagerReportReviewIssueRepository issueRepository;
    private final ManagerReportReviewDisputeRepository disputeRepository;
    private final ManagerReportReviewQualityService qualityService;
    private final ManagerReportReviewTaskContextService taskContextService;

    @Transactional
    public List<ManagerReportReviewIssue> ensureIssues(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions
    ) {
        if (review == null || review.getId() == null || questions == null) {
            return List.of();
        }
        List<ManagerReportReviewIssue> existing =
                issueRepository.findByReview_IdOrderByQuestionIndexAsc(review.getId());
        Set<Integer> existingIndexes = existing.stream()
                .map(ManagerReportReviewIssue::getQuestionIndex)
                .collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < questions.size(); index++) {
            if (existingIndexes.contains(index)) continue;
            ManagerReportReviewQualityService.ReviewQuestion question = questions.get(index);
            ManagerReportReviewIssue issue = new ManagerReportReviewIssue();
            issue.setReview(review);
            issue.setQuestionIndex(index);
            issue.setTitle(title(index, question.question()));
            issue.setQuestionText(clean(question.question()));
            issue.setQuestionJson(qualityService.questionsJson(List.of(question)));
            issue.setStatus(ManagerReportReviewIssueStatus.PENDING);
            issueRepository.save(issue);
        }
        return issueRepository.findByReview_IdOrderByQuestionIndexAsc(review.getId());
    }

    @Transactional(readOnly = true)
    public List<ManagerReportReviewIssue> issues(ManagerReportReviewSession review) {
        if (review == null || review.getId() == null) return List.of();
        return issueRepository.findByReview_IdOrderByQuestionIndexAsc(review.getId());
    }

    @Transactional(readOnly = true)
    public List<ManagerReportReviewIssue> selectableIssues(ManagerReportReviewSession review) {
        return issues(review).stream()
                .filter(issue -> issue.getStatus() != ManagerReportReviewIssueStatus.WITHDRAWN)
                .filter(issue -> issue.getStatus() != ManagerReportReviewIssueStatus.DISPUTE_PENDING)
                .filter(issue -> issue.getStatus() != ManagerReportReviewIssueStatus.DISPUTED)
                .filter(issue -> issue.getStatus() != ManagerReportReviewIssueStatus.NEEDS_CONTEXT)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ManagerReportReviewIssue> nextPending(
            ManagerReportReviewSession review,
            int fromIndex
    ) {
        List<ManagerReportReviewIssue> pending = issues(review).stream()
                .filter(issue -> issue.getStatus() == ManagerReportReviewIssueStatus.PENDING)
                .toList();
        return pending.stream()
                .filter(issue -> issue.getQuestionIndex() >= Math.max(0, fromIndex))
                .findFirst()
                .or(() -> pending.stream().findFirst());
    }

    @Transactional
    public int withdrawResolvedSourceIssues(
            ManagerReportReviewSession review,
            List<ManagerReportReviewQualityService.ReviewQuestion> questions
    ) {
        if (review == null || review.getId() == null || questions == null || questions.isEmpty()) {
            return 0;
        }
        List<ManagerReportReviewIssue> issues = ensureIssues(review, questions);
        Set<Long> requestedIds = questions.stream()
                .flatMap(question -> question.sourceTaskIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        if (requestedIds.isEmpty()) return 0;

        List<ManagerReportReviewIssue> withdrawn = new ArrayList<>();
        for (ManagerReportReviewIssue issue : issues) {
            if (issue.getStatus() != ManagerReportReviewIssueStatus.PENDING
                    || issue.getQuestionIndex() < 0
                    || issue.getQuestionIndex() >= questions.size()) {
                continue;
            }
            List<Long> sourceIds = questions.get(issue.getQuestionIndex()).sourceTaskIds();
            if (!sourceIds.isEmpty()
                    && sourceIds.stream().allMatch(taskContextService::resolvedSatisfactorily)) {
                issue.setStatus(ManagerReportReviewIssueStatus.WITHDRAWN);
                withdrawn.add(issue);
            }
        }
        if (!withdrawn.isEmpty()) {
            issueRepository.saveAll(withdrawn);
        }
        return withdrawn.size();
    }

    @Transactional(readOnly = true)
    public boolean isPending(ManagerReportReviewSession review, int questionIndex) {
        if (review == null || review.getId() == null || questionIndex < 0) return false;
        return issueRepository.findByReview_IdAndQuestionIndex(review.getId(), questionIndex)
                .map(issue -> issue.getStatus() == ManagerReportReviewIssueStatus.PENDING)
                .orElse(false);
    }

    @Transactional
    public void markAnswered(ManagerReportReviewSession review, int questionIndex) {
        if (review == null || review.getId() == null) return;
        issueRepository.findByReview_IdAndQuestionIndex(review.getId(), questionIndex)
                .ifPresent(issue -> {
                    issue.setStatus(ManagerReportReviewIssueStatus.ANSWERED);
                    issue.setAnsweredAt(LocalDateTime.now());
                    issueRepository.save(issue);
                });
    }

    @Transactional
    public ManagerReportReviewDispute beginDispute(
            ManagerReportReviewSession review,
            Long issueId
    ) {
        if (review == null || review.getId() == null || issueId == null) {
            throw new IllegalArgumentException("Не выбрано замечание для спора");
        }
        if (unresolvedDispute(review).isPresent()) {
            throw new IllegalStateException("Сначала дождитесь решения по уже открытому спору");
        }
        ManagerReportReviewIssue issue = issueRepository.findById(issueId)
                .filter(value -> value.getReview() != null
                        && review.getId().equals(value.getReview().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Замечание не относится к этому аудиту"));
        if (!selectableIssues(review).stream().map(ManagerReportReviewIssue::getId).toList().contains(issueId)) {
            throw new IllegalStateException("Это замечание уже оспаривается или отозвано");
        }
        ManagerReportReviewDispute dispute = new ManagerReportReviewDispute();
        dispute.setIssue(issue);
        dispute.setStatus(ManagerReportReviewDisputeStatus.DRAFT);
        dispute.setPreviousIssueStatus(issue.getStatus());
        dispute.setPreviousSessionStatus(review.getStatus());
        dispute.setRequestedAt(LocalDateTime.now());
        disputeRepository.save(dispute);
        issue.setStatus(ManagerReportReviewIssueStatus.DISPUTE_PENDING);
        issueRepository.save(issue);
        return dispute;
    }

    @Transactional(readOnly = true)
    public Optional<ManagerReportReviewDispute> draftDispute(ManagerReportReviewSession review) {
        return dispute(review, List.of(ManagerReportReviewDisputeStatus.DRAFT));
    }

    @Transactional(readOnly = true)
    public Optional<ManagerReportReviewDispute> openDispute(ManagerReportReviewSession review) {
        return dispute(review, List.of(
                ManagerReportReviewDisputeStatus.OPEN,
                ManagerReportReviewDisputeStatus.NEEDS_CONTEXT
        ));
    }

    @Transactional(readOnly = true)
    public Optional<ManagerReportReviewDispute> unresolvedDispute(ManagerReportReviewSession review) {
        return dispute(review, UNRESOLVED_DISPUTES);
    }

    @Transactional
    public ManagerReportReviewDispute submitDispute(
            ManagerReportReviewSession review,
            String managerText
    ) {
        ManagerReportReviewDispute dispute = draftDispute(review)
                .orElseThrow(() -> new IllegalStateException("Сначала выберите замечание"));
        dispute.setManagerText(limit(managerText, 2000));
        dispute.setSubmittedAt(LocalDateTime.now());
        dispute.setStatus(ManagerReportReviewDisputeStatus.OPEN);
        disputeRepository.save(dispute);
        ManagerReportReviewIssue issue = dispute.getIssue();
        issue.setStatus(ManagerReportReviewIssueStatus.DISPUTED);
        issueRepository.save(issue);
        return dispute;
    }

    @Transactional(readOnly = true)
    public boolean hasPendingQuestions(ManagerReportReviewSession review) {
        return countIssues(review, List.of(ManagerReportReviewIssueStatus.PENDING)) > 0;
    }

    @Transactional(readOnly = true)
    public boolean hasUnresolvedDisputes(ManagerReportReviewSession review) {
        return review != null && review.getId() != null
                && disputeRepository.countByIssue_Review_IdAndStatusIn(
                review.getId(), UNRESOLVED_DISPUTES) > 0;
    }

    @Transactional(readOnly = true)
    public long answeredCount(ManagerReportReviewSession review) {
        return countIssues(review, List.of(ManagerReportReviewIssueStatus.ANSWERED));
    }

    @Transactional(readOnly = true)
    public long validIssueCount(ManagerReportReviewSession review) {
        return countIssues(review, List.of(
                ManagerReportReviewIssueStatus.PENDING,
                ManagerReportReviewIssueStatus.ANSWERED,
                ManagerReportReviewIssueStatus.DISPUTE_PENDING,
                ManagerReportReviewIssueStatus.DISPUTED,
                ManagerReportReviewIssueStatus.NEEDS_CONTEXT
        ));
    }

    @Transactional(readOnly = true)
    public long withdrawnCount(ManagerReportReviewSession review) {
        return countIssues(review, List.of(ManagerReportReviewIssueStatus.WITHDRAWN));
    }

    @Transactional(readOnly = true)
    public List<ManagerReportReviewDispute> disputes(ManagerReportReviewSession review) {
        if (review == null || review.getId() == null) return List.of();
        return disputeRepository.findByIssue_Review_IdOrderByCreatedAtAsc(review.getId());
    }

    private Optional<ManagerReportReviewDispute> dispute(
            ManagerReportReviewSession review,
            Collection<ManagerReportReviewDisputeStatus> statuses
    ) {
        if (review == null || review.getId() == null) return Optional.empty();
        return disputeRepository.findFirstByIssue_Review_IdAndStatusInOrderByCreatedAtDesc(
                review.getId(),
                statuses
        );
    }

    private long countIssues(
            ManagerReportReviewSession review,
            Collection<ManagerReportReviewIssueStatus> statuses
    ) {
        return review == null || review.getId() == null
                ? 0
                : issueRepository.countByReview_IdAndStatusIn(review.getId(), statuses);
    }

    private String title(int index, String question) {
        String value = clean(question).replace('\n', ' ');
        String prefix = "Замечание " + (index + 1) + ": ";
        int maxText = Math.max(12, 200 - prefix.length());
        return prefix + (value.length() <= maxText ? value : value.substring(0, maxText - 1) + "…");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int max) {
        String text = clean(value);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
