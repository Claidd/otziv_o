package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.performers.repository.ReviewPerformerAssignmentRepository;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import com.hunt.otziv.u_users.keycloak.client.KeycloakAdminClient;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.services.UserAuthEpochService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPerformerRegistrationLifecycleTest {

    @Mock private PerformerProfileRepository performerProfileRepository;
    @Mock private ReviewPerformerAssignmentRepository assignmentRepository;
    @Mock private CityRepository cityRepository;
    @Mock private PerformerAssignmentMapper assignmentMapper;
    @Mock private PerformerAssignmentService assignmentService;
    @Mock private PerformerRolloutService rolloutService;
    @Mock private PerformerAssignmentScreenshotStorage screenshotStorage;
    @Mock private UserRepository userRepository;
    @Mock private KeycloakAdminClient keycloakAdminClient;
    @Mock private UserAuthEpochService authEpochService;
    @Mock private ContractorPaymentProfileService contractorPaymentProfileService;

    @Test
    void pendingApplicationCannotActivateWithoutExplicitManualPhoneVerification() {
        AdminPerformerService service = service();
        PerformerProfile performer = validPending();
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(performer));

        assertThatThrownBy(() -> service.updateStatus(7L, PerformerProfileStatus.ACTIVE, "", false, "admin"))
                .hasMessageContaining("вручную проверьте телефон");

        verify(keycloakAdminClient, never()).updateUser(any(), any(), any());
        verify(authEpochService, never()).reactivated(any());
        assertThat(performer.getStatus()).isEqualTo(PerformerProfileStatus.NEW);
    }

    @Test
    void verifiedCurrentApplicationActivatesBothLocalAndKeycloakAccounts() {
        AdminPerformerService service = service();
        PerformerProfile performer = validPending();
        User moderator = User.builder().id(2L).username("admin").active(true).build();
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(performer));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(moderator));
        doAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setActive(true);
            return null;
        }).when(authEpochService).reactivated(any(User.class));

        var response = service.updateStatus(
                7L,
                PerformerProfileStatus.ACTIVE,
                "контрольный звонок",
                true,
                "admin"
        );

        ArgumentCaptor<UpdateKeycloakUserRequest> request = ArgumentCaptor.forClass(UpdateKeycloakUserRequest.class);
        verify(keycloakAdminClient).updateUser(
                org.mockito.ArgumentMatchers.eq("kc-41"),
                org.mockito.ArgumentMatchers.eq("performer_random"),
                request.capture()
        );
        assertThat(request.getValue().isEnabled()).isTrue();
        verify(authEpochService).reactivated(performer.getUser());
        verify(userRepository).save(performer.getUser());
        assertThat(performer.getStatus()).isEqualTo(PerformerProfileStatus.ACTIVE);
        assertThat(performer.getPhoneVerifiedAt()).isNotNull();
        assertThat(performer.getPhoneVerificationMethod()).isEqualTo("ADMIN_MANUAL");
        assertThat(performer.getPhoneVerificationNote()).isEqualTo("контрольный звонок");
        assertThat(performer.getModeratedBy()).isSameAs(moderator);
        assertThat(response.activationReady()).isFalse();
    }

    @Test
    void expiredNeverApprovedRejectedApplicationCannotBeActivated() {
        AdminPerformerService service = service();
        PerformerProfile expired = validPending();
        expired.setStatus(PerformerProfileStatus.REJECTED);
        expired.setRegistrationExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.updateStatus(
                7L,
                PerformerProfileStatus.ACTIVE,
                "контрольный звонок",
                true,
                "admin"
        )).hasMessageContaining("Срок заявки истёк");

        verify(keycloakAdminClient, never()).updateUser(any(), any(), any());
    }

    @Test
    void changingPendingApplicationToAnotherStatusCannotBypassActivationChecks() {
        AdminPerformerService service = service();
        PerformerProfile bypassAttempt = validPending();
        bypassAttempt.setStatus(PerformerProfileStatus.PAUSED);
        bypassAttempt.setRegistrationExpiresAt(LocalDateTime.now().minusMinutes(1));
        bypassAttempt.setRulesConsentVersion(null);
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(bypassAttempt));

        assertThatThrownBy(() -> service.updateStatus(
                7L,
                PerformerProfileStatus.ACTIVE,
                "контрольный звонок",
                true,
                "admin"
        )).hasMessageContaining("Срок заявки истёк");

        verify(keycloakAdminClient, never()).updateUser(any(), any(), any());
        assertThat(bypassAttempt.getStatus()).isEqualTo(PerformerProfileStatus.PAUSED);
    }

    @Test
    void previouslyApprovedPerformerCanReactivateAfterRegistrationTtl() {
        AdminPerformerService service = service();
        PerformerProfile approved = validPending();
        approved.setStatus(PerformerProfileStatus.PAUSED);
        approved.setPhoneVerifiedAt(LocalDateTime.now().minusDays(1));
        approved.setRegistrationExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(approved));

        service.updateStatus(
                7L,
                PerformerProfileStatus.ACTIVE,
                "повторный контрольный звонок",
                true,
                "admin"
        );

        verify(keycloakAdminClient).updateUser(any(), any(), any());
        assertThat(approved.getStatus()).isEqualTo(PerformerProfileStatus.ACTIVE);
        assertThat(approved.getPhoneVerificationNote()).isEqualTo("повторный контрольный звонок");
    }

    @Test
    void factualLegacyApprovedMarkerAllowsManualReactivationWithoutInventedEvidence() {
        AdminPerformerService service = service();
        PerformerProfile legacy = validPending();
        legacy.setStatus(PerformerProfileStatus.PAUSED);
        legacy.setLegacyApprovedBeforeSecureLifecycle(true);
        legacy.setRegistrationExpiresAt(null);
        legacy.setPersonalDataAcceptedAt(null);
        legacy.setPersonalDataConsentVersion(null);
        legacy.setRulesAcceptedAt(null);
        legacy.setRulesConsentVersion(null);
        legacy.setHonestReviewAcceptedAt(null);
        legacy.setHonestReviewConsentVersion(null);
        legacy.setTelegramLinkedAt(null);
        legacy.getUser().setTelegramChatId(null);
        when(performerProfileRepository.findById(7L)).thenReturn(Optional.of(legacy));

        var response = service.updateStatus(
                7L,
                PerformerProfileStatus.ACTIVE,
                "личный контрольный звонок владельцу старого профиля",
                true,
                "admin"
        );

        verify(keycloakAdminClient).updateUser(any(), any(), any());
        assertThat(legacy.getStatus()).isEqualTo(PerformerProfileStatus.ACTIVE);
        assertThat(legacy.getPhoneVerifiedAt()).isNotNull();
        assertThat(legacy.getPersonalDataAcceptedAt()).isNull();
        assertThat(response.legacyApprovedBeforeSecureLifecycle()).isTrue();
        assertThat(response.activationWarning()).contains("исторические согласия");
    }

    private AdminPerformerService service() {
        return new AdminPerformerService(
                performerProfileRepository,
                assignmentRepository,
                cityRepository,
                assignmentMapper,
                assignmentService,
                rolloutService,
                screenshotStorage,
                userRepository,
                keycloakAdminClient,
                authEpochService,
                contractorPaymentProfileService
        );
    }

    private PerformerProfile validPending() {
        LocalDateTime now = LocalDateTime.now();
        User user = User.builder()
                .id(41L)
                .username("performer_random")
                .email("performer_random@performers.o-ogo.local")
                .fio("Иван Иванов")
                .phoneNumber("+79000000001")
                .keycloakId("kc-41")
                .active(false)
                .build();
        user.setTelegramChatId(777L);
        return PerformerProfile.builder()
                .id(7L)
                .user(user)
                .status(PerformerProfileStatus.NEW)
                .telegramLinkedAt(now.minusMinutes(1))
                .registrationExpiresAt(now.plusHours(1))
                .personalDataAcceptedAt(now.minusMinutes(2))
                .personalDataConsentVersion(PerformerRegistrationService.PERSONAL_DATA_CONSENT_VERSION)
                .rulesAcceptedAt(now.minusMinutes(2))
                .rulesConsentVersion(PerformerRegistrationService.RULES_CONSENT_VERSION)
                .honestReviewAcceptedAt(now.minusMinutes(2))
                .honestReviewConsentVersion(PerformerRegistrationService.HONEST_REVIEW_CONSENT_VERSION)
                .build();
    }
}
