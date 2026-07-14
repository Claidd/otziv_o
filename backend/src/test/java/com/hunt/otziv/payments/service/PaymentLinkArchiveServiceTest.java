package com.hunt.otziv.payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.repository.PaymentLinkArchiveRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentLinkArchiveServiceTest {

    @Mock
    private PaymentLinkArchiveRepository repository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private TbankPaymentProperties properties;

    @Test
    void archivesAndVerifiesPreparedOrderPaymentLinksBeforeDeletingThem() {
        List<Long> ids = List.of(10L, 11L);
        when(repository.findLiveIdsForPreparedOrderArchiveCandidates()).thenReturn(ids);
        when(repository.countArchivedIds(ids)).thenReturn(2);
        when(repository.deleteLiveIds(ids)).thenReturn(2);

        int deleted = service().archiveForPreparedOrderArchiveCandidates(77L);

        assertEquals(2, deleted);
        verify(repository).archiveIds(eq(ids), any(LocalDateTime.class), eq("ORDER_ARCHIVED"), eq(77L));
        verify(repository).deleteLiveIds(ids);
    }

    @Test
    void refusesToDeleteWhenArchiveCopyIsIncomplete() {
        List<Long> ids = List.of(10L, 11L);
        when(repository.findLiveIdsForPreparedOrderArchiveCandidates()).thenReturn(ids);
        when(repository.countArchivedIds(ids)).thenReturn(1);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service().archiveForPreparedOrderArchiveCandidates(77L)
        );

        assertEquals("Payment link archive verification failed: selected=2, archived=1", exception.getMessage());
        verify(repository, never()).deleteLiveIds(ids);
    }

    private PaymentLinkArchiveService service() {
        return new PaymentLinkArchiveService(repository, appSettingService, properties);
    }
}
