package com.hunt.otziv.l_lead.service;

import com.hunt.otziv.l_lead.model.DeviceToken;
import com.hunt.otziv.l_lead.model.Telephone;
import com.hunt.otziv.l_lead.repository.DeviceTokenRepository;
import com.hunt.otziv.l_lead.repository.TelephoneRepository;
import com.hunt.otziv.u_users.model.Operator;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenServiceImplTest {

    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private TelephoneRepository telephoneRepository;

    @Test
    void storesOnlyDigestAndCreatesThirtyDaySecureCookie() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        ReflectionTestUtils.setField(service, "secureCookie", true);
        ReflectionTestUtils.setField(service, "tokenTtlDays", 30);
        Telephone telephone = Telephone.builder()
                .id(10L)
                .telephoneOperator(Operator.builder().id(7L).build())
                .build();
        when(telephoneRepository.findByIdWithOperator(10L)).thenReturn(Optional.of(telephone));
        when(deviceTokenRepository.existsByTelephone_Id(10L)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        LocalDateTime before = LocalDateTime.now();
        String bearerToken = service.createDeviceToken(10L, response);
        LocalDateTime after = LocalDateTime.now();

        Cookie cookie = response.getCookie("device_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(bearerToken);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
        ArgumentCaptor<DeviceToken> saved = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
        assertThat(saved.getValue().getToken())
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(DeviceTokenServiceImpl.hashToken(bearerToken))
                .isNotEqualTo(bearerToken);
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(before.plusDays(30), after.plusDays(30));
        verify(deviceTokenRepository).deleteExpiredOrInactiveByTelephoneId(eq(10L), any(LocalDateTime.class));
    }

    @Test
    void inactiveTokenCannotResolveTelephone() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        String digest = DeviceTokenServiceImpl.hashToken("inactive");
        when(deviceTokenRepository.findActiveUnexpiredByStoredToken(eq(digest), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());

        assertThat(service.getTelephoneIdByToken("inactive")).isNull();
        verify(deviceTokenRepository, never()).findActiveLegacyByStoredToken(any());
    }

    @Test
    void resolvesCurrentBearerOnlyThroughItsDigest() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        String bearerToken = "test-bearer-value";
        Telephone telephone = Telephone.builder()
                .id(10L)
                .telephoneOperator(Operator.builder().id(7L).build())
                .build();
        DeviceToken stored = DeviceToken.builder()
                .token(DeviceTokenServiceImpl.hashToken(bearerToken))
                .telephone(telephone)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .active(true)
                .build();
        when(deviceTokenRepository.findActiveUnexpiredByStoredToken(
                eq(DeviceTokenServiceImpl.hashToken(bearerToken)),
                any(LocalDateTime.class)
        )).thenReturn(Optional.of(stored));

        assertThat(service.getTelephoneIdByToken(bearerToken))
                .extracting("telephoneID", "operatorID")
                .containsExactly(10L, 7L);
        verify(deviceTokenRepository, never()).findActiveLegacyByStoredToken(any());
    }

    @Test
    void expiredLegacyTokenIsRejectedWithoutRotation() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        String legacyToken = legacyUuidToken();
        DeviceToken expired = DeviceToken.builder()
                .token(legacyToken)
                .telephone(Telephone.builder().id(10L).build())
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .active(true)
                .build();
        when(deviceTokenRepository.findActiveUnexpiredByStoredToken(
                eq(DeviceTokenServiceImpl.hashToken(legacyToken)),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());
        when(deviceTokenRepository.findActiveLegacyByStoredToken(legacyToken))
                .thenReturn(Optional.of(expired));

        assertThat(service.getTelephoneIdByToken(legacyToken)).isNull();
        verify(deviceTokenRepository, never()).rotateLegacyToken(any(), any(), any(), any());
    }

    @Test
    void legacyUuidIsRotatedAndResolvedByDigest() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        ReflectionTestUtils.setField(service, "tokenTtlDays", 30);
        String legacyToken = legacyUuidToken();
        String digest = DeviceTokenServiceImpl.hashToken(legacyToken);
        Telephone telephone = Telephone.builder()
                .id(10L)
                .telephoneOperator(Operator.builder().id(7L).build())
                .build();
        DeviceToken legacy = DeviceToken.builder()
                .token(legacyToken)
                .telephone(telephone)
                .active(true)
                .build();
        DeviceToken rotated = DeviceToken.builder()
                .token(digest)
                .telephone(telephone)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .active(true)
                .build();
        when(deviceTokenRepository.findActiveUnexpiredByStoredToken(eq(digest), any(LocalDateTime.class)))
                .thenReturn(Optional.empty(), Optional.of(rotated));
        when(deviceTokenRepository.findActiveLegacyByStoredToken(legacyToken))
                .thenReturn(Optional.of(legacy));

        assertThat(service.getTelephoneIdByToken(legacyToken))
                .extracting("telephoneID", "operatorID")
                .containsExactly(10L, 7L);
        verify(deviceTokenRepository).rotateLegacyToken(
                eq(legacyToken),
                eq(digest),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
    }

    @Test
    void storedDigestCannotBeReplayedAsBearerToken() {
        DeviceTokenServiceImpl service = new DeviceTokenServiceImpl(deviceTokenRepository, telephoneRepository);
        String storedDigest = DeviceTokenServiceImpl.hashToken("real-bearer-token");
        when(deviceTokenRepository.findActiveUnexpiredByStoredToken(
                eq(DeviceTokenServiceImpl.hashToken(storedDigest)),
                any(LocalDateTime.class)
        )).thenReturn(Optional.empty());

        assertThat(service.getTelephoneIdByToken(storedDigest)).isNull();
        verify(deviceTokenRepository, never()).findActiveLegacyByStoredToken(any());
    }

    private static String legacyUuidToken() {
        return UUID.randomUUID().toString();
    }
}
