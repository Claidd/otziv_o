package com.hunt.otziv.manager_daily_summary.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A promise is kept separately from evidence that the underlying problem is resolved. */
@Entity
@Getter
@Setter
@Table(name = "manager_review_commitments", uniqueConstraints = @UniqueConstraint(
        name = "uk_manager_commitment_answer", columnNames = {"review_id", "question_index", "position"}))
public class ManagerReviewCommitment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "review_id", nullable = false) private Long reviewId;
    @Column(name = "question_index", nullable = false) private int questionIndex;
    @Column(nullable = false) private int position;
    @Column(name = "manager_id", nullable = false) private Long managerId;
    @Column(name = "source_task_id", nullable = false) private Long sourceTaskId;
    @Column(name = "action_text", nullable = false, length = 2000) private String action;
    @Column(name = "responsible", nullable = false, length = 220) private String owner;
    @Column(nullable = false, length = 2000) private String verification;
    @Column(length = 2000) private String dependencies;
    @Column(name = "due_at", nullable = false) private LocalDateTime dueAt;
    @Column(nullable = false, length = 32) private String status = "PLANNED";
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "checked_at") private LocalDateTime checkedAt;
}
