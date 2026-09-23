package com.hunt.otziv.notification_media.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.hunt.otziv.notification_media.service.ContextualMediaFacts;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;
import com.hunt.otziv.u_users.api.DeferredUserAuthority;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class ApiWorkerMediaContextControllerTest {
    final DeferredUserAuthority authority=mock(DeferredUserAuthority.class);
    final ContextualMediaFacts facts=mock(ContextualMediaFacts.class);
    final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    final Authentication auth=mock(Authentication.class);
    final ApiWorkerMediaContextController controller=new ApiWorkerMediaContextController(authority,facts,events);

    @Test void clientCannotForgeSuccessfulPublication() {
        assertThatThrownBy(()->controller.context(new ApiWorkerMediaContextController.ContextRequest("REVIEW_PUBLISH","review",9L,"publish"),auth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(events,facts,authority);
    }
    @Test void foreignCardDoesNotPublishSignal() {
        when(authority.capture(auth)).thenReturn(new DeferredUserAuthority.Actor(4L,"worker",1,Set.of("ROLE_WORKER")));
        when(facts.ownedCard(4L,"review",9L)).thenReturn(Map.of());
        assertThatThrownBy(()->controller.context(new ApiWorkerMediaContextController.ContextRequest("UNSAVED_CHANGES","review",9L,null),auth))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(events);
    }
    @Test void recipientIsDerivedFromAuthenticatedActor() {
        when(authority.capture(auth)).thenReturn(new DeferredUserAuthority.Actor(4L,"worker",1,Set.of("ROLE_WORKER")));
        when(facts.ownedCard(4L,"review",9L)).thenReturn(Map.of("id",9L));
        controller.context(new ApiWorkerMediaContextController.ContextRequest("UNSAVED_CHANGES","review",9L,null),auth);
        verify(events).publishEvent(new StaffMediaSignal(4L,"UNSAVED_CHANGES","review",9L,null,null));
    }
}
