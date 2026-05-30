package com.hunt.otziv.client_messages;

import com.hunt.otziv.client_messages.service.ClientMessageMaintenancePreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientMessageMaintenancePreviewServiceTest {

    @Test
    void statusChangingMaintenanceBatchesDoNotShareOneTransaction() throws NoSuchMethodException {
        assertFalse(ClientMessageMaintenancePreviewService.class
                .getMethod("applyPaymentOverdue")
                .isAnnotationPresent(Transactional.class));
        assertFalse(ClientMessageMaintenancePreviewService.class
                .getMethod("completePublishedPublicationOrders")
                .isAnnotationPresent(Transactional.class));
    }
}
