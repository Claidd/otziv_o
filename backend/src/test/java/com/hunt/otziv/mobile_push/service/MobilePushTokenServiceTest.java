package com.hunt.otziv.mobile_push.service;

import com.hunt.otziv.mobile_push.dto.MobilePushTokenRequest;
import com.hunt.otziv.mobile_push.dto.MobilePushTokenRevokeRequest;
import com.hunt.otziv.mobile_push.model.MobilePushToken;
import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobilePushTokenServiceTest {

    @Mock
    private MobilePushTokenRepository tokenRepository;
    @Mock
    private UserService userService;
    @Mock
    private MobilePushSenderService senderService;
    @InjectMocks
    private MobilePushTokenService service;

    @Test
    void reRegisteringTokenTransfersOwnershipAndResetsRevocationAtCurrentEpoch() {
        User previousUser = User.builder().id(1L).username("old-user").authEpoch(2L).build();
        User currentUser = User.builder().id(2L).username("new-user").active(true).authEpoch(8L).build();
        MobilePushToken existing = new MobilePushToken();
        existing.setUser(previousUser);
        existing.setToken("fcm-token");
        existing.setActive(false);
        existing.setAuthEpoch(2L);
        existing.setRevokedAt(Instant.now().minusSeconds(60));
        existing.setRevokedReason("USER_LOGOUT");
        existing.setRevokedByUserId(1L);

        when(userService.findByUserName("new-user")).thenReturn(Optional.of(currentUser));
        when(tokenRepository.findByToken("fcm-token")).thenReturn(Optional.of(existing));

        service.register(principal("new-user"), new MobilePushTokenRequest(
                "fcm-token",
                "android",
                "device-2",
                "1.0.62"
        ));

        assertSame(currentUser, existing.getUser());
        assertTrue(existing.isActive());
        assertEquals(8L, existing.getAuthEpoch());
        assertNull(existing.getRevokedAt());
        assertNull(existing.getRevokedReason());
        assertNull(existing.getRevokedByUserId());
        verify(tokenRepository).save(existing);
    }

    @Test
    void revokeIsBoundToCurrentUserAndIdempotentWhenRepositoryFindsNoActiveToken() {
        User currentUser = User.builder().id(22L).username("current-user").active(true).build();
        when(userService.findByUserName("current-user")).thenReturn(Optional.of(currentUser));
        when(tokenRepository.revokeActiveOwnedToken(
                eq(22L),
                eq("foreign-or-already-revoked-token"),
                any(Instant.class),
                eq("USER_LOGOUT"),
                eq(22L)
        )).thenReturn(0);

        MobilePushTokenRevokeRequest request = new MobilePushTokenRevokeRequest(
                "foreign-or-already-revoked-token"
        );
        service.revokeCurrent(principal("current-user"), request);
        service.revokeCurrent(principal("current-user"), request);

        verify(tokenRepository, times(2)).revokeActiveOwnedToken(
                eq(22L),
                eq("foreign-or-already-revoked-token"),
                any(Instant.class),
                eq("USER_LOGOUT"),
                eq(22L)
        );
    }

    @Test
    void inactiveUserCannotRegisterButCanRevokeOwnedToken() {
        User inactiveUser = User.builder().id(31L).username("inactive-user").active(false).authEpoch(3L).build();
        when(userService.findByUserName("inactive-user")).thenReturn(Optional.of(inactiveUser));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.register(principal("inactive-user"), new MobilePushTokenRequest(
                        "new-token",
                        "android",
                        null,
                        null
                ))
        );

        assertEquals(403, error.getStatusCode().value());
        verify(tokenRepository, never()).findByToken("new-token");

        service.revokeCurrent(
                principal("inactive-user"),
                new MobilePushTokenRevokeRequest("old-token")
        );
        verify(tokenRepository).revokeActiveOwnedToken(
                eq(31L),
                eq("old-token"),
                any(Instant.class),
                eq("USER_LOGOUT"),
                eq(31L)
        );
    }

    private Principal principal(String username) {
        return () -> username;
    }
}
