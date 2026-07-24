package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.p_products.review.PublicationApprovalException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonBillingPublicationApprovalFailureMarkerTest {

    @Mock
    private CommonInvoiceRepository invoiceRepository;

    @Test
    void createsManagerAttentionWithProblemAndSolution() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(44L);
        invoice.setStatus(CommonInvoiceStatus.READY);
        when(invoiceRepository.findById(44L)).thenReturn(Optional.of(invoice));

        new CommonBillingPublicationApprovalFailureMarker(invoiceRepository).markAttention(
                44L,
                new PublicationApprovalException(
                        101L,
                        "есть пустой текст",
                        "заполните текст и повторите одобрение"
                )
        );

        ArgumentCaptor<CommonInvoice> captor = ArgumentCaptor.forClass(CommonInvoice.class);
        verify(invoiceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(captor.getValue().getLastError())
                .startsWith(CommonBillingPublicationApprovalFailureMarker.ERROR_PREFIX)
                .contains("order=101")
                .contains("problem=есть пустой текст")
                .contains("solution=заполните текст и повторите одобрение");
    }
}
