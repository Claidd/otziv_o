package com.hunt.otziv.security.credentials;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.r_review.model.Review;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CredentialRevealServiceTest {

    @Mock
    private BusinessAuditService businessAuditService;

    @Test
    void revealsPasswordOnlyAfterStrictAuditWithoutWritingSecretToAudit() {
        CredentialRevealService service = new CredentialRevealService(businessAuditService);
        Review review = review(42L, "login-value", "secret-value");

        CredentialRevealResponse response = service.revealReview(
                review,
                new CredentialRevealRequest("password", "worker-board", "menu", "publish")
        );

        assertEquals("secret-value", response.value());
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(businessAuditService).recordStrict(
                eq("CREDENTIAL_REVEAL"),
                eq("review"),
                eq(42L),
                isNull(),
                eq(42L),
                isNull(),
                isNull(),
                details.capture()
        );
        assertTrue(details.getValue().contains("field=password"));
        assertTrue(!details.getValue().contains("secret-value"));
    }

    @Test
    void auditFailurePreventsCredentialDisclosure() {
        CredentialRevealService service = new CredentialRevealService(businessAuditService);
        Review review = review(43L, "login", "secret");
        RuntimeException auditFailure = new RuntimeException("audit unavailable");
        doThrow(auditFailure).when(businessAuditService).recordStrict(
                anyString(),
                anyString(),
                any(),
                nullable(Long.class),
                nullable(Long.class),
                isNull(),
                isNull(),
                anyString()
        );

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> service.revealReview(review, new CredentialRevealRequest("password", null, null, null))
        );

        assertEquals(auditFailure, thrown);
    }

    @Test
    void badReviewTaskUsesFrozenSnapshotCredential() {
        CredentialRevealService service = new CredentialRevealService(businessAuditService);
        BadReviewTask task = BadReviewTask.builder()
                .id(51L)
                .sourceReview(review(52L, "current-login", "current-password"))
                .botLoginSnapshot("snapshot-login")
                .botPasswordSnapshot("snapshot-password")
                .build();

        assertEquals(
                "snapshot-password",
                service.revealBadReviewTask(
                        task,
                        new CredentialRevealRequest("password", null, null, null)
                ).value()
        );
    }

    private Review review(Long id, String login, String password) {
        Review review = new Review();
        review.setId(id);
        review.setBot(Bot.builder()
                .id(100L + id)
                .login(login)
                .password(password)
                .build());
        return review;
    }
}
