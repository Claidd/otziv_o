package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.dto.ExternalReviewWorkerResponse;
import com.hunt.otziv.external_review_checks.model.ExternalReviewCheckStatus;
import java.util.Locale;
import java.util.Objects;

/** Pure trust-boundary classification shared by S3 and database finalization. */
final class ExternalReviewWorkerResponsePolicy {

    private ExternalReviewWorkerResponsePolicy() {
    }

    static Outcome evaluate(ExternalReviewWorkerResponse response, Long expectedCheckId) {
        if (response == null) {
            return error("worker_response_missing");
        }
        if (!Objects.equals(expectedCheckId, response.checkId())) {
            return error("worker_check_id_mismatch");
        }
        String status = response.status();
        if (status == null || status.isBlank()) {
            return error("worker_status_missing");
        }
        try {
            ExternalReviewCheckStatus parsed = ExternalReviewCheckStatus.valueOf(
                    status.trim().toUpperCase(Locale.ROOT)
            );
            if (parsed == ExternalReviewCheckStatus.PENDING
                    || parsed == ExternalReviewCheckStatus.CHECKING) {
                return error("worker_status_non_terminal");
            }
            if (parsed == ExternalReviewCheckStatus.ERROR) {
                return error("worker_status_error");
            }
            return new Outcome(parsed, null, true);
        } catch (IllegalArgumentException exception) {
            return error("worker_status_unknown");
        }
    }

    private static Outcome error(String code) {
        return new Outcome(ExternalReviewCheckStatus.ERROR, code, false);
    }

    record Outcome(
            ExternalReviewCheckStatus status,
            String errorCode,
            boolean evidenceAccepted
    ) {
    }
}
