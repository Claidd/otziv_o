package com.hunt.otziv.client_messages;

import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.client_messages.dto.ClientMessageMaintenanceApplyResponse;
import com.hunt.otziv.client_messages.service.ClientMessageMaintenancePreviewService;
import com.hunt.otziv.common_billing.api.CommonInvoicePaymentOperations;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.status.service.OrderStatusTransitionService;
import com.hunt.otziv.payments.api.SpecialistPaymentReviewGate;
import com.hunt.otziv.review_recovery.api.ReviewRecoveryMessageHold;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The «apply» buttons of the monitor change orders and states in bulk: they must skip what the background autoresponder
 * skips, and judge archive offers by the autoresponder's own lists.
 */
class ClientMessageMaintenanceGuardsTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AppSettingService settings = mock(AppSettingService.class);
    private final OrderStatusTransitionService transitions = mock(OrderStatusTransitionService.class);
    private final SpecialistPaymentReviewGate paymentReviewGate = mock(SpecialistPaymentReviewGate.class);
    private final ReviewRecoveryMessageHold recoveryHold = mock(ReviewRecoveryMessageHold.class);
    private final CommonInvoicePaymentOperations commonInvoices = mock(CommonInvoicePaymentOperations.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CommonInvoicePaymentOperations> commonInvoicesProvider = mock(ObjectProvider.class);
    private final ClientMessageMaintenancePreviewService service = spy(new ClientMessageMaintenancePreviewService(
            jdbc, settings, mock(OrderRepository.class), mock(BadReviewTaskService.class), transitions,
            paymentReviewGate, recoveryHold, commonInvoicesProvider));

    @Test
    void overdueTransferSkipsCheckedPaymentsRecoveryAndCommonInvoicesLikeTheBackgroundEscalation() throws Exception {
        doReturn(null).when(service).preview();
        when(settings.getInt(eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS), anyInt())).thenReturn(30);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(1L, 2L, 3L, 4L));
        when(paymentReviewGate.hasPendingReview(1L)).thenReturn(true);
        when(recoveryHold.shouldPauseActiveOrder(2L)).thenReturn(true);
        when(commonInvoicesProvider.getIfAvailable()).thenReturn(commonInvoices);
        when(commonInvoices.isOrderInActiveCommonInvoice(3L)).thenReturn(true);
        when(transitions.changeStatusForOrder(4L, "Не оплачено")).thenReturn(true);

        ClientMessageMaintenanceApplyResponse response = service.applyPaymentOverdue();

        assertThat(response.changed()).isEqualTo(1);
        assertThat(response.message()).contains("пропущено").endsWith(": 3");
        verify(transitions, never()).changeStatusForOrder(1L, "Не оплачено");
        verify(transitions, never()).changeStatusForOrder(2L, "Не оплачено");
        verify(transitions, never()).changeStatusForOrder(3L, "Не оплачено");
        assertThat(sql.getValue())
                .contains("o.paper_invoice_automation_hold = 0")
                .contains("NOT (o.order_complete = 1 AND o.order_pay_day IS NOT NULL)");
    }

    @Test
    void archiveOfferBlockUsesAutoresponderListsAndNeverClosesADeliveryInProgress() {
        doReturn(null).when(service).preview();
        when(settings.getString(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        when(jdbc.update(sql.capture(), params.capture())).thenReturn(0);

        service.blockInvalidArchiveOffers();

        assertThat(params.getValue().getValue("openRequestStatuses")).isEqualTo(List.of("PENDING", "FAILED"));
        assertThat(params.getValue().getValue("closedStatuses")).isEqualTo(List.of("Оплачено", "Архив", "Бан"));
        assertThat(sql.getValue())
                .contains("s.delivery_status IS NULL")
                .contains("COALESCE(s.delivery_envelope, '') = ''")
                .contains("s.locked_until IS NULL OR s.locked_until < NOW(6)")
                .contains("'state_transaction_outcome_uncertain'")
                .contains("s.next_attempt_at = NULL");
    }
}
