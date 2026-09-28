package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.repository.CompanyRepository;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerControlArchiveRecoveryTest {
    @Mock CompanyRepository companyRepository;
    @InjectMocks ManagerControlProblemExamples examples;

    @Test
    void archivedRecoveryKeepsCompanyNameAndLinksToArchivedOrder() {
        when(companyRepository.findById(20L)).thenReturn(Optional.empty());
        var task = ReviewRecoveryTask.builder().id(1238L).archiveOrderId(10L).archiveCompanyId(20L)
                .archiveCompanyTitle("Баргузин").scheduledDate(LocalDate.now().minusDays(2))
                .status(ReviewRecoveryTaskStatus.PLANNED).build();
        var card = examples.recoveryTaskExample(task, LocalDate.now());
        assertThat(card.title()).isEqualTo("Баргузин");
        assertThat(card.subtitle()).contains("Восстановление #1238");
        assertThat(card.targetUrl()).isEqualTo("/manager/archive?mode=archive&archiveOrderId=10");
    }
}
