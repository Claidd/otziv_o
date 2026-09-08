package com.hunt.otziv.r_review.bot.service;

import com.hunt.otziv.b_bots.model.Bot;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.model.Filial;
import com.hunt.otziv.c_companies.repository.CompanyOrganizationIdentityRepository;
import com.hunt.otziv.p_products.worker_access.service.WorkerAssignmentMutationGuardService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewBotReuseRepairServiceTest {
    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final CompanyOrganizationIdentityRepository identities = mock(CompanyOrganizationIdentityRepository.class);
    private final ReviewBotChangeService changes = mock(ReviewBotChangeService.class);
    private final BusinessAuditService audit = mock(BusinessAuditService.class);
    private final ReviewBotReuseRepairService service = new ReviewBotReuseRepairService(reviews, identities, changes,
            mock(WorkerAssignmentMutationGuardService.class), audit, mock(EntityManager.class));
    private Review review;

    @BeforeEach
    void setUp() {
        review = Review.builder().id(192020L).rowVersion(7).bot(Bot.builder().id(864177L).build())
                .filial(Filial.builder().company(Company.builder().id(2514L).build()).build()).build();
        when(reviews.findByIdForBotChange(192020L)).thenReturn(Optional.of(review));
        when(identities.relatedCompanyIds(2514L)).thenReturn(Set.of(2514L,2132L));
        when(reviews.findUsedBotIdsByCompanyId(2132L)).thenReturn(Set.of(864177L));
    }

    @Test
    void replacesOnlyConfirmedUnpublishedConflictUsingStandardService() {
        doAnswer(call -> { review.setBot(Bot.builder().id(999999L).build()); return null; }).when(changes).changeBot(192020L);
        assertThat(service.repair(192020L,864177L,7).botId()).isEqualTo(999999L);
        verify(changes).changeBot(192020L);
        verify(audit).recordRequiredInCurrentTransaction(eq("REVIEW_BOT_REUSE_REPAIRED"),eq("REVIEW"),eq(192020L),
                isNull(),eq(192020L),eq(864177L),eq(999999L),anyString());
    }

    @Test
    void publishedReviewIsNeverChanged() {
        review.setPublish(true);
        assertThatThrownBy(() -> service.repair(192020L,864177L,7)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(changes,audit);
    }

    @Test
    void refusesStaleBotOrVersion() {
        assertThatThrownBy(() -> service.repair(192020L,123L,7)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.repair(192020L,864177L,6)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(changes,audit);
    }

    @Test
    void conflictMustStillExist() {
        when(reviews.findUsedBotIdsByCompanyId(2132L)).thenReturn(Set.of());
        assertThatThrownBy(() -> service.repair(192020L,864177L,7)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(changes,audit);
    }
}
