package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.manager_control.repository.ManagerDailyControlConcreteItemRepository;
import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import com.hunt.otziv.manager_daily_summary.model.ManagerReviewCommitment;
import com.hunt.otziv.manager_daily_summary.repository.ManagerReviewCommitmentRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ManagerReportReviewCommitmentService {
    private final ManagerReviewCommitmentRepository repository;
    private final ManagerDailyControlConcreteItemRepository sourceRepository;
    @Value("${manager.summary.zone:Asia/Irkutsk}") private String zone = "Asia/Irkutsk";
    static final List<String> OPEN = List.of("PLANNED", "OVERDUE", "NEEDS_CONTEXT");

    @Transactional(readOnly = true)
    public List<com.hunt.otziv.manager_control.dto.ManagerReviewCommitmentView> forSource(Long sourceTaskId) {
        if (sourceTaskId == null) return List.of();
        var source = sourceRepository.findById(sourceTaskId).orElse(null);
        if (source == null || source.getControl() == null) return List.of();
        String type = source.getEntityType() == null ? "" : source.getEntityType();
        String family = type.startsWith("COMMON_INVOICE") ? "COMMON_INVOICE"
                : type.startsWith("CLIENT_CHAT") ? "CLIENT_CHAT" : null;
        var related = source.getEntityId() == null ? List.of(source)
                : family != null ? sourceRepository.findByEntityTypeStartingWithAndEntityId(family, source.getEntityId())
                : sourceRepository.findByEntityTypeAndEntityId(type, source.getEntityId());
        var ids = related.stream()
                .filter(row -> row.getControl() != null && java.util.Objects.equals(row.getControl().managerId(), source.getControl().managerId()))
                .map(row -> row.getId()).toList();
        return repository.findTop20BySourceTaskIdInOrderByCreatedAtDesc(ids.isEmpty() ? List.of(sourceTaskId) : ids).stream().map(row ->
                new com.hunt.otziv.manager_control.dto.ManagerReviewCommitmentView(row.getId(), row.getAction(),
                        row.getOwner(), row.getDueAt(), row.getVerification(), row.getStatus())).toList();
    }

    @Transactional
    public void record(ManagerReportReviewSession review, int questionIndex,
                       ManagerReportReviewQualityService.ReviewQuestion question,
                       List<ManagerReportReviewQualityService.ActionCommitment> promises) {
        if (review.isTestMode() || promises == null || promises.isEmpty()
                || repository.existsByReviewIdAndQuestionIndex(review.getId(), questionIndex)) return;
        int position = 0;
        for (var promise : promises) {
            if (!question.sourceTaskIds().contains(promise.sourceTaskId()))
                throw new IllegalArgumentException("Commitment source is outside the reviewed question");
            var source = sourceRepository.findByIdForUpdate(promise.sourceTaskId()).orElseThrow();
            if (source.getControl() == null
                    || !java.util.Objects.equals(source.getControl().managerId(), review.managerId()))
                throw new IllegalArgumentException("Commitment source belongs to another manager");
            LocalDateTime due = OffsetDateTime.parse(promise.dueAt()).atZoneSameInstant(ZoneId.of(zone)).toLocalDateTime();
            ManagerReviewCommitment row = new ManagerReviewCommitment();
            row.setReviewId(review.getId());
            row.setQuestionIndex(questionIndex);
            row.setPosition(position++);
            row.setManagerId(review.managerId());
            row.setSourceTaskId(source.getId());
            row.setAction(promise.action());
            row.setOwner(promise.owner());
            row.setVerification(promise.verification());
            String dependencies = String.join("; ", promise.dependencies());
            row.setDependencies(dependencies.length() > 2000 ? dependencies.substring(0, 1999) + "…" : dependencies);
            row.setDueAt(due);
            row.setCreatedAt(LocalDateTime.now());
            repository.save(row);
            // Keep the earliest promised check. Never mark a case resolved from an answer.
            LocalDateTime earliest = repository.findBySourceTaskIdAndStatusIn(source.getId(), OPEN).stream()
                    .map(ManagerReviewCommitment::getDueAt).min(LocalDateTime::compareTo).orElse(due);
            source.setFollowUpAt(earliest);
            sourceRepository.save(source);
        }
    }

    @Transactional(readOnly = true)
    public List<ManagerReportReviewQualityService.ReviewQuestion> dueQuestions(Long managerId) {
        if (managerId == null) return List.of();
        var grouped = repository.findByManagerIdAndStatusInAndDueAtLessThanEqualOrderByDueAtAsc(
                managerId, OPEN, LocalDateTime.now()).stream().collect(java.util.stream.Collectors.groupingBy(
                        ManagerReviewCommitment::getSourceTaskId, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> new ManagerReportReviewQualityService.ReviewQuestion(
                        "По карточке #" + entry.getKey() + " остались обещания: " + entry.getValue().stream()
                                .map(row -> "«" + row.getAction() + "» (срок " + row.getDueAt() + ")")
                                .collect(java.util.stream.Collectors.joining("; "))
                                + ". Какой результат получен, где он подтверждён и что мешает завершению, если проблема остаётся?",
                        List.of("результат ранее обещанного действия", "подтверждающее событие или честное указание отсутствия результата",
                                "следующее действие, ответственный и срок проверки, если проблема остаётся"),
                        List.of(entry.getKey()))).toList();
    }
}
