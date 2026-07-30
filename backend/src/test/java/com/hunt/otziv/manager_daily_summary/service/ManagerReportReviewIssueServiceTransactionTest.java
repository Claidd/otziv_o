package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.manager_daily_summary.model.ManagerReportReviewSession;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class ManagerReportReviewIssueServiceTransactionTest {

    @Test
    void businessValidationDoesNotMarkOuterTelegramTransactionForRollback() throws Exception {
        assertValidationExceptionsDoNotRollBack(
                ManagerReportReviewIssueService.class.getDeclaredMethod(
                        "beginDispute",
                        ManagerReportReviewSession.class,
                        Long.class
                )
        );
        assertValidationExceptionsDoNotRollBack(
                ManagerReportReviewIssueService.class.getDeclaredMethod(
                        "submitDispute",
                        ManagerReportReviewSession.class,
                        String.class
                )
        );
    }

    private void assertValidationExceptionsDoNotRollBack(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor())
                .contains(IllegalArgumentException.class, IllegalStateException.class);
    }
}
