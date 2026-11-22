package com.hunt.otziv.archive.exception;

import com.hunt.otziv.archive.dto.ArchiveRecoveryWorkerOption;
import java.util.List;

/**
 * Returning an archived order to work needs a specialist who still works. The conflict carries
 * the specialists the person may choose, computed by the same rule the restore validates, so the
 * restore window never offers someone the server would then refuse.
 */
public class ArchiveRestoreSpecialistRequiredException extends ArchiveRestoreConflictException {

    public static final String CODE = "SPECIALIST_REQUIRED";

    private final transient List<ArchiveRecoveryWorkerOption> workers;

    public ArchiveRestoreSpecialistRequiredException(String message, List<ArchiveRecoveryWorkerOption> workers) {
        super(message);
        this.workers = workers == null ? List.of() : List.copyOf(workers);
    }

    public List<ArchiveRecoveryWorkerOption> workers() {
        return workers;
    }
}
