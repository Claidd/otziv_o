package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.performers.dto.RegisterPerformerRequest;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.CreatedKeycloakUserResponse;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.KeycloakUserProvisioningService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformerRegistrationServiceTest {

    @Mock private KeycloakUserProvisioningService provisioningService;
    @Mock private UserRepository userRepository;
    @Mock private CityRepository cityRepository;
    @Mock private PerformerProfileRepository performerProfileRepository;

    @Test
    void registrationStaysDisabledPendingAndRecordsExplicitVersionedConsents() {
        PerformerRegistrationService service = new PerformerRegistrationService(
                provisioningService,
                userRepository,
                cityRepository,
                performerProfileRepository
        );
        RegisterPerformerRequest request = new RegisterPerformerRequest();
        request.setPhoneNumber("+7 900 000-00-01");
        request.setCityId(5L);
        request.setFio("Иван Иванов");
        request.setPersonalDataConsentAccepted(true);
        request.setRulesConsentAccepted(true);
        request.setHonestReviewConsentAccepted(true);
        City city = new City();
        User user = User.builder().id(41L).username("perf79000000001").build();
        when(cityRepository.findById(5L)).thenReturn(city);
        when(provisioningService.createUser(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CreatedKeycloakUserResponse.builder().id(41L).username(user.getUsername()).build());
        when(userRepository.findById(41L)).thenReturn(Optional.of(user));

        var response = service.register(request);

        ArgumentCaptor<CreateKeycloakUserRequest> keycloakRequest = ArgumentCaptor.forClass(CreateKeycloakUserRequest.class);
        verify(provisioningService).createUser(keycloakRequest.capture());
        assertThat(keycloakRequest.getValue().isTemporaryPassword()).isTrue();
        assertThat(keycloakRequest.getValue().isEnabled()).isFalse();
        assertThat(keycloakRequest.getValue().getPassword()).isNotBlank();
        assertThat(keycloakRequest.getValue().getUsername())
                .startsWith("performer_")
                .doesNotContain("79000000001");
        ArgumentCaptor<PerformerProfile> performer = ArgumentCaptor.forClass(PerformerProfile.class);
        verify(performerProfileRepository).save(performer.capture());
        assertThat(performer.getValue().getStatus()).isEqualTo(PerformerProfileStatus.NEW);
        assertThat(performer.getValue().getPersonalDataAcceptedAt()).isNotNull();
        assertThat(performer.getValue().getPersonalDataConsentVersion())
                .isEqualTo(PerformerRegistrationService.PERSONAL_DATA_CONSENT_VERSION);
        assertThat(performer.getValue().getRulesAcceptedAt()).isNotNull();
        assertThat(performer.getValue().getRulesConsentVersion())
                .isEqualTo(PerformerRegistrationService.RULES_CONSENT_VERSION);
        assertThat(performer.getValue().getHonestReviewAcceptedAt()).isNotNull();
        assertThat(performer.getValue().getHonestReviewConsentVersion())
                .isEqualTo(PerformerRegistrationService.HONEST_REVIEW_CONSENT_VERSION);
        assertThat(performer.getValue().getRegistrationExpiresAt())
                .isAfter(performer.getValue().getPersonalDataAcceptedAt());
        assertThat(response.status()).isEqualTo("NEW");
        assertThat(response.temporaryPassword()).isNull();
        assertThat(response.telegramLinkToken()).isNull();
        assertThat(response.requiresAdminApproval()).isTrue();
    }

    @Test
    void registrationFailsClosedWithoutEveryExplicitConsent() {
        PerformerRegistrationService service = new PerformerRegistrationService(
                provisioningService,
                userRepository,
                cityRepository,
                performerProfileRepository
        );
        RegisterPerformerRequest request = new RegisterPerformerRequest();
        request.setPhoneNumber("+7 900 000-00-01");
        request.setCityId(5L);
        request.setFio("Иван Иванов");
        request.setPersonalDataConsentAccepted(true);
        request.setRulesConsentAccepted(false);
        request.setHonestReviewConsentAccepted(true);

        assertThatThrownBy(() -> service.register(request))
                .hasMessageContaining("явно принять все условия");
        verify(provisioningService, never()).createUser(org.mockito.ArgumentMatchers.any());
    }
}
