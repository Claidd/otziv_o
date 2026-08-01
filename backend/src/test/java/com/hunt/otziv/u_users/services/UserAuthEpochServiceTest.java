package com.hunt.otziv.u_users.services;

import com.hunt.otziv.mobile_push.repository.MobilePushTokenRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuthEpochServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private MobilePushTokenRepository pushTokenRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deactivationIncrementsEpochRecordsActorAndRevokesPush() {
        authenticate("admin");
        when(userRepository.findIdByUsername("admin")).thenReturn(Optional.of(99L));
        User user = User.builder()
                .id(7L)
                .username("worker")
                .active(true)
                .authEpoch(4L)
                .build();

        new UserAuthEpochService(userRepository, pushTokenRepository).deactivated(user);

        assertFalse(user.isActive());
        assertEquals(5L, user.getAuthEpoch());
        assertNotNull(user.getDeactivatedAt());
        assertEquals(99L, user.getDeactivatedByUserId());
        assertEquals(UserAuthEpochService.USER_DEACTIVATED, user.getDeactivationReason());
        verify(pushTokenRepository).revokeAllActiveForUser(
                org.mockito.ArgumentMatchers.eq(7L),
                any(Instant.class),
                org.mockito.ArgumentMatchers.eq(UserAuthEpochService.USER_DEACTIVATED),
                org.mockito.ArgumentMatchers.eq(99L)
        );
    }

    @Test
    void passwordChangeIncrementsEpochAndClearsStaleActiveMetadata() {
        User user = User.builder()
                .id(8L)
                .username("manager")
                .active(true)
                .authEpoch(11L)
                .deactivatedAt(LocalDateTime.now().minusDays(1))
                .deactivatedByUserId(3L)
                .deactivationReason("stale")
                .build();

        new UserAuthEpochService(userRepository, pushTokenRepository).passwordChanged(user);

        assertEquals(12L, user.getAuthEpoch());
        assertNull(user.getDeactivatedAt());
        assertNull(user.getDeactivatedByUserId());
        assertNull(user.getDeactivationReason());
        verify(pushTokenRepository).revokeAllActiveForUser(
                org.mockito.ArgumentMatchers.eq(8L),
                any(Instant.class),
                org.mockito.ArgumentMatchers.eq(UserAuthEpochService.PASSWORD_CHANGED),
                org.mockito.ArgumentMatchers.isNull()
        );
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of())
        );
    }
}
