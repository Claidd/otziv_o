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
    private final ManagerReportReviewQualityService qualityService;
    private final ManagerWorkerDailyProgressService workerProgress;
    @Value("${manager.summary.zone:Asia/Irkutsk}") private String zone = "Asia/Irkutsk";
    static final List<String> OPEN = List.of("PLANNED", "OVERDUE", "NEEDS_CONTEXT");
    // Older servers must never send nullable worker targets through their control-task job.
    static final List<String> WORKER_OPEN = List.of("WORKER_PLANNED", "WORKER_OVERDUE", "WORKER_NEEDS_CONTEXT");
    static final List<String> ALL_OPEN = java.util.stream.Stream.concat(OPEN.stream(),WORKER_OPEN.stream()).toList();

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
                managerId, OPEN, LocalDateTime.now()).stream().filter(row->row.getSourceTaskId()!=null).collect(java.util.stream.Collectors.groupingBy(
                        ManagerReviewCommitment::getSourceTaskId, java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        var result=new java.util.ArrayList<>(grouped.entrySet().stream().map(entry -> new ManagerReportReviewQualityService.ReviewQuestion(
                        "По карточке #" + entry.getKey() + " остались обещания: " + entry.getValue().stream()
                                .map(row -> "«" + row.getAction() + "» (срок " + row.getDueAt() + ")")
                                .collect(java.util.stream.Collectors.joining("; "))
                                + ". Какой результат получен, где он подтверждён и что мешает завершению, если проблема остаётся?",
                        List.of("результат ранее обещанного действия", "подтверждающее событие или честное указание отсутствия результата",
                                "следующее действие, ответственный и срок проверки, если проблема остаётся"),
                        List.of(entry.getKey()))).toList());
        var workerGoals=repository.findDueWorkerGoals(managerId,WORKER_OPEN,LocalDateTime.now(),LocalDateTime.now().minusDays(1))
                .stream().collect(java.util.stream.Collectors.groupingBy(row->row.getWorkerId()+":"+row.getGoalDate(),
                        java.util.LinkedHashMap::new,java.util.stream.Collectors.toList()));
        for(var rows:workerGoals.values()) {
            var first=rows.getFirst();
            if(!authorizedGoal(managerId,first.getWorkerId(),first.getGoalDate())) continue;
            result.add(new ManagerReportReviewQualityService.ReviewQuestion(
                    "Специалист "+first.getWorkerName()+" за "+first.getGoalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    +": ранее принят план по недобору "+first.getGoalRemaining()+" задач. "
                    +rows.stream().map(row->"«"+row.getAction()+"»"+(row.getDueAt()==null?"":" (срок "+row.getDueAt()+")"))
                        .collect(java.util.stream.Collectors.joining("; "))
                    +". Какой результат получен именно по этому оставшемуся объёму и где он подтверждён? "
                    +"Если результат ещё не получен, укажите препятствие и следующий шаг. Итоги нового дня не подтверждают закрытие прежнего долга.",
                    List.of("результат помощи специалисту по исходному недобору или честное указание отсутствия результата",
                            "проверка именно оставшегося объёма работ и источник результата",
                            "следующее действие и способ проверки, если долг остаётся")));
        }
        return List.copyOf(result);
    }

    @Transactional
    public void recordWorkerGoal(ManagerReportReviewSession review,int questionIndex,
            ManagerReportReviewQualityService.ReviewQuestion question,ManagerReportReviewQualityService.Assessment assessment,
            String evidenceContext) {
        if(review.isTestMode() || !assessment.accepted()
                || repository.existsByReviewIdAndQuestionIndex(review.getId(),questionIndex)) return;
        var target=qualityService.workerGoalTarget(evidenceContext).orElse(null);
        if(target==null) return;
        var date=java.time.LocalDate.parse(target.date());
        if(!question.sourceTaskIds().isEmpty() || !question.sourceChatItemIds().isEmpty()
                || !question.question().contains(target.workerName()) || !authorizedGoal(review.managerId(),target.workerId(),date))
            throw new IllegalArgumentException("Worker goal is outside the manager's reviewed scope");
        var plans=assessment.commitments();
        if(plans.isEmpty()) {
            // Preserve literal accepted wording even if the provider omitted optional plan metadata.
            String action=assessment.claims().stream().filter(c->c.kind()==ManagerReportReviewQualityService.ClaimKind.PROMISE
                    && c.status()==ManagerReportReviewQualityService.ClaimStatus.PLAN)
                    .map(ManagerReportReviewQualityService.ClaimAssessment::answerQuote).distinct()
                    .collect(java.util.stream.Collectors.joining(" "));
            String verification=assessment.criteria().stream().filter(c->c.status()==ManagerReportReviewQualityService.CriterionStatus.SATISFIED)
                    .filter(c->c.feedbackTopic()==ManagerReportReviewQualityService.FeedbackTopic.VERIFICATION)
                    .map(ManagerReportReviewQualityService.CriterionAssessment::answerQuote).reduce((a,b)->b).orElse("");
            if(action.isBlank() || verification.isBlank()) return;
            plans=List.of(new ManagerReportReviewQualityService.ActionCommitment(null,action,review.getManagerName(),null,verification,List.of()));
        }
        for(var previous:repository.findByManagerIdAndWorkerIdAndGoalDateAndStatusIn(review.managerId(),target.workerId(),date,WORKER_OPEN)) {
            previous.setStatus("REPLANNED");repository.save(previous);
        }
        int position=0;
        for(var plan:plans) {
            if(plan.sourceTaskId()!=null) throw new IllegalArgumentException("Worker goal cannot target a control task");
            var row=new ManagerReviewCommitment();
            row.setReviewId(review.getId());row.setQuestionIndex(questionIndex);row.setPosition(position++);
            row.setManagerId(review.managerId());row.setWorkerId(target.workerId());row.setWorkerName(target.workerName());
            row.setStatus("WORKER_PLANNED");
            row.setGoalDate(date);row.setGoalRemaining(target.remaining());
            row.setAction(limit(plan.action()));row.setOwner(plan.owner());row.setVerification(limit(plan.verification()));
            row.setDueAt(plan.dueAt()==null || plan.dueAt().isBlank()?null:
                    OffsetDateTime.parse(plan.dueAt()).atZoneSameInstant(ZoneId.of(zone)).toLocalDateTime());
            row.setDependencies(limit(String.join("; ",plan.dependencies())));row.setCreatedAt(LocalDateTime.now());
            repository.save(row);
        }
    }

    private boolean authorizedGoal(Long managerId,Long workerId,java.time.LocalDate date) {
        return managerId!=null && workerId!=null && date!=null
                && workerProgress.missedPreviousDayGoals(managerId,date.plusDays(1)).stream().anyMatch(goal->workerId.equals(goal.workerId()));
    }

    @Transactional
    public void withdrawWorkerGoal(ManagerReportReviewSession review,
            com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewIssue issue,String ownerComment) {
        var questions=qualityService.readQuestions(issue.getQuestionJson());
        if(questions.size()!=1 || !questions.getFirst().sourceTaskIds().isEmpty()
                || !questions.getFirst().sourceChatItemIds().isEmpty()) return;
        String text=questions.getFirst().question();
        if(!text.startsWith("Специалист ")) return;
        var dateMatch=java.util.regex.Pattern.compile("(?U)\\bза (\\d{2}\\.\\d{2}\\.\\d{4})\\b").matcher(text);
        if(!dateMatch.find()) return;
        var date=java.time.LocalDate.parse(dateMatch.group(1),java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        var candidates=workerProgress.missedPreviousDayGoals(review.managerId(),date.plusDays(1)).stream()
                .filter(goal->text.contains(goal.workerName())).toList();
        if(candidates.size()!=1) return;
        for(var row:repository.findByManagerIdAndWorkerIdAndGoalDateAndStatusIn(review.managerId(),candidates.getFirst().workerId(),date,WORKER_OPEN)) {
            row.setStatus("OWNER_WITHDRAWN");row.setResolvedAt(LocalDateTime.now());
            row.setResolutionNote(limit("Замечание снято владельцем в аудите №"+review.getId()+": "+ownerComment));
            repository.save(row);
        }
    }

    private String limit(String value) {return value==null?"":value.length()>2000?value.substring(0,1999)+"…":value;}
}
