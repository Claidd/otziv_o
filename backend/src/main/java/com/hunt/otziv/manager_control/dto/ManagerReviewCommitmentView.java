package com.hunt.otziv.manager_control.dto;

import java.time.LocalDateTime;

public record ManagerReviewCommitmentView(
        Long id, String action, String owner, LocalDateTime dueAt, String verification, String status
) { }
