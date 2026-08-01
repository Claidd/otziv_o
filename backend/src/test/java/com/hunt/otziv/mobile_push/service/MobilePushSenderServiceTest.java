package com.hunt.otziv.mobile_push.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobilePushSenderServiceTest {

    @Test
    void epochMismatchExcludedByRepositoryIsNotSent() {
        MobilePushTokenRepository repository = mock(MobilePushTokenRepository.class);
        when(repository.findDeliverableByUserId(41L)).thenReturn(List.of());
        MobilePushSenderService service = new MobilePushSenderService(Optional.<FirebaseMessaging>empty(), repository);

        var response = service.sendToUser(
                User.builder().id(41L).active(true).authEpoch(9L).build(),
                "title",
                "body",
                "/tabs/home"
        );

        assertFalse(response.configured());
        assertEquals(0, response.tokens());
        verify(repository).findDeliverableByUserId(41L);
    }
}
