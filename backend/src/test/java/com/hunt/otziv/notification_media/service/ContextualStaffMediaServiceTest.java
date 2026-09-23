package com.hunt.otziv.notification_media.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import com.hunt.otziv.config.settings.api.ContextualMediaSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextualStaffMediaServiceTest {
    final ContextualMediaFacts facts=mock(ContextualMediaFacts.class);
    final NotificationMediaDeliveryService delivery=mock(NotificationMediaDeliveryService.class);
    final ThematicNotificationDispatchStore dispatch=mock(ThematicNotificationDispatchStore.class);
    final ContextualMediaSettings settings=mock(ContextualMediaSettings.class);
    final ContextualStaffMediaService service=new ContextualStaffMediaService(facts,delivery,dispatch,settings);
    final StaffMediaSignal signal=new StaffMediaSignal(4,"REVIEW_PUBLISH","review",9L,9L,"publish");

    void enabled() {
        when(settings.enabled()).thenReturn(true);
        when(settings.maxPerDay()).thenReturn(2);
        when(facts.recipient(4)).thenReturn(Map.of("worker_telegram_group_chat_id",-100L));
        when(facts.ownedCard(4,"review",9L)).thenReturn(Map.of("published",true));
    }
    @Test void disabledFeatureDoesNotReadRecipientOrSend() {
        service.onSignal(signal);
        verifyNoInteractions(facts,delivery,dispatch);
    }
    @Test void budgetOrDuplicateSuppressesPhoto() {
        enabled();
        when(dispatch.claim(anyString(),eq(4L),any(),eq(2))).thenReturn(false);
        service.onSignal(signal);
        verifyNoInteractions(delivery);
    }
    @Test void successfulPhotoMarksClaimAndUsesAssignedGroup() {
        enabled();
        when(dispatch.claim(anyString(),eq(4L),any(),eq(2))).thenReturn(true);
        when(delivery.sendMediaOnly(anyString(),eq(-100L),eq(4L),anyString(),isNull())).thenReturn(true);
        service.onSignal(signal);
        verify(delivery).sendMediaOnly(eq("WORKER_PUBLICATION_DONE"),eq(-100L),eq(4L),contains("#9"),isNull());
        verify(dispatch).markSent(eq("WORKER_PUBLICATION_DONE"),eq(4L),any());
    }
    @Test void ambiguousFailureKeepsClaimAndCannotFailBusinessCommand() {
        enabled();
        when(dispatch.claim(anyString(),eq(4L),any(),eq(2))).thenReturn(true);
        when(delivery.sendMediaOnly(anyString(),anyLong(),anyLong(),anyString(),isNull())).thenThrow(new IllegalStateException("timeout"));
        service.onSignal(signal);
        verify(dispatch,never()).release(anyString(),anyLong(),any());
    }
    @Test void clientReportedNetworkBlockRequiresServerEvidence() {
        enabled();
        service.onSignal(new StaffMediaSignal(4,"NETWORK_BLOCKED",null,null,null,null));
        verifyNoInteractions(delivery,dispatch);
    }
}
