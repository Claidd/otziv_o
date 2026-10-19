package com.hunt.otziv.notification_media.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.hunt.otziv.notification_media.service.WorkerMediaContextService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ApiWorkerMediaContextControllerTest {
    final WorkerMediaContextService contextService=mock(WorkerMediaContextService.class);
    final Authentication auth=mock(Authentication.class);
    final ApiWorkerMediaContextController controller=new ApiWorkerMediaContextController(contextService);

    @Test void clientCannotForgeSuccessfulPublication() {
        assertThatThrownBy(()->controller.context(new ApiWorkerMediaContextController.ContextRequest("REVIEW_PUBLISH","review",9L,"publish"),auth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(contextService);
    }
    @Test void validContextIsDelegated() {
        controller.context(new ApiWorkerMediaContextController.ContextRequest("UNSAVED_CHANGES","review",9L,null),auth);
        verify(contextService).record(auth,"UNSAVED_CHANGES","review",9L,null);
    }
}
