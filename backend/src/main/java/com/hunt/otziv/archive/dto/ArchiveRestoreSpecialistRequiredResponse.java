package com.hunt.otziv.archive.dto;

import java.util.List;

/** Conflict body of an archive restore that needs a new specialist: the usual message and code plus the choices. */
public record ArchiveRestoreSpecialistRequiredResponse(
        String message,
        String code,
        List<ArchiveRecoveryWorkerOption> workers
) {
}
