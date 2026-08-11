package com.hunt.otziv.performers.service;

import com.hunt.otziv.c_cities.model.City;
import com.hunt.otziv.c_cities.repository.CityRepository;
import com.hunt.otziv.performers.dto.RegisterPerformerRequest;
import com.hunt.otziv.performers.dto.RegisterPerformerResponse;
import com.hunt.otziv.performers.model.PerformerGender;
import com.hunt.otziv.performers.model.PerformerProfile;
import com.hunt.otziv.performers.model.PerformerProfileStatus;
import com.hunt.otziv.performers.repository.PerformerProfileRepository;
import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.CreatedKeycloakUserResponse;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.service.KeycloakUserProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PerformerRegistrationService {

    public static final String PERSONAL_DATA_CONSENT_VERSION = "privacy-2026-08-03";
    public static final String RULES_CONSENT_VERSION = "performer-rules-2026-08-03";
    public static final String HONEST_REVIEW_CONSENT_VERSION = "honest-review-2026-08-03";

    private static final String PERFORMER_ROLE = "PERFORMER";
    private static final Duration MAX_PENDING_REGISTRATION_TTL = Duration.ofDays(7);
    private static final char[] GENERATED_CREDENTIAL_CHARS = buildGeneratedCredentialChars();

    private final KeycloakUserProvisioningService userProvisioningService;
    private final UserRepository userRepository;
    private final CityRepository cityRepository;
    private final PerformerProfileRepository performerProfileRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${telegram.bot.username:}")
    private String telegramBotUsername;

    @Value("${performer.registration.pending-ttl:PT48H}")
    private Duration pendingRegistrationTtl = Duration.ofHours(48);

    @Transactional
    public RegisterPerformerResponse register(RegisterPerformerRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные исполнителя не переданы");
        }
        requireExplicitConsents(request);

        City city = cityRepository.findById(request.getCityId());
        if (city == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город не найден");
        }

        String phone = normalizePhone(request.getPhoneNumber());
        if (phone.length() < 10 || phone.length() > 15) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите корректный телефон");
        }

        // A phone number is not an identity proof. Never reserve a predictable
        // username derived from an unverified number: every pending application
        // gets an opaque account id and expires independently.
        String username = "performer_" + UUID.randomUUID().toString().replace("-", "");
        String undisclosedBootstrapPassword = generateTemporaryPassword();
        String technicalEmail = username + "@performers.o-ogo.local";

        CreateKeycloakUserRequest createRequest = new CreateKeycloakUserRequest();
        createRequest.setUsername(username);
        createRequest.setEmail(technicalEmail);
        createRequest.setFio(trimToNull(request.getFio()));
        createRequest.setPhoneNumber("+" + phone);
        createRequest.setPassword(undisclosedBootstrapPassword);
        // Pending public applications cannot authenticate. A moderator must
        // verify the phone out of band, activate the account and issue a fresh
        // credential through the existing privileged user-management flow.
        createRequest.setTemporaryPassword(true);
        createRequest.setEnabled(false);
        createRequest.setEmailVerified(false);
        createRequest.setRoles(new LinkedHashSet<>(Set.of(PERFORMER_ROLE)));

        CreatedKeycloakUserResponse created = userProvisioningService.createUser(createRequest);
        User user = userRepository.findById(created.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Пользователь создан, но не найден"));

        LocalDateTime acceptedAt = LocalDateTime.now();
        LocalDateTime registrationExpiresAt = acceptedAt.plus(validatedPendingRegistrationTtl());
        String token = "performer_" + UUID.randomUUID().toString().replace("-", "");
        PerformerProfile performer = PerformerProfile.builder()
                .user(user)
                .city(city)
                .gender(request.getGender() == null ? PerformerGender.NOT_SPECIFIED : request.getGender())
                // Telegram linking (or an administrator) performs the real activation step.
                .status(PerformerProfileStatus.NEW)
                .telegramLinkToken(token)
                .registeredSource(trimToNull(request.getRegisteredSource()))
                .personalDataAcceptedAt(acceptedAt)
                .personalDataConsentVersion(PERSONAL_DATA_CONSENT_VERSION)
                .rulesAcceptedAt(acceptedAt)
                .rulesConsentVersion(RULES_CONSENT_VERSION)
                .honestReviewAcceptedAt(acceptedAt)
                .honestReviewConsentVersion(HONEST_REVIEW_CONSENT_VERSION)
                .registrationExpiresAt(registrationExpiresAt)
                .lastActiveAt(null)
                .build();
        performerProfileRepository.save(performer);

        return RegisterPerformerResponse.builder()
                .userId(user.getId())
                .performerId(performer.getId())
                .username(user.getUsername())
                // Never disclose the bootstrap credential/token separately.
                // The one-time Telegram token exists only inside the HTTPS URL.
                .temporaryPassword(null)
                .telegramLinkToken(null)
                .telegramLinkUrl(telegramLinkUrl(token))
                .status(performer.getStatus().name())
                .registrationExpiresAt(registrationExpiresAt)
                .requiresAdminApproval(true)
                .build();
    }

    private void requireExplicitConsents(RegisterPerformerRequest request) {
        if (!Boolean.TRUE.equals(request.getPersonalDataConsentAccepted())
                || !Boolean.TRUE.equals(request.getRulesConsentAccepted())
                || !Boolean.TRUE.equals(request.getHonestReviewConsentAccepted())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Необходимо явно принять все условия регистрации");
        }
    }

    private Duration validatedPendingRegistrationTtl() {
        if (pendingRegistrationTtl == null
                || pendingRegistrationTtl.isZero()
                || pendingRegistrationTtl.isNegative()
                || pendingRegistrationTtl.compareTo(MAX_PENDING_REGISTRATION_TTL) > 0) {
            throw new IllegalStateException("performer.registration.pending-ttl must be greater than zero and at most 7 days");
        }
        return pendingRegistrationTtl;
    }

    private String telegramLinkUrl(String token) {
        if (!hasText(telegramBotUsername)) {
            return "";
        }
        return "https://t.me/" + telegramBotUsername.trim().replaceFirst("^@", "") + "?start=" + token;
    }

    private String normalizePhone(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D+", "");
        if (digits.length() == 10) {
            return "7" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }
        return digits;
    }

    private String generateTemporaryPassword() {
        StringBuilder result = new StringBuilder("Ta7!");
        for (int i = 0; i < 12; i++) {
            result.append(GENERATED_CREDENTIAL_CHARS[secureRandom.nextInt(GENERATED_CREDENTIAL_CHARS.length)]);
        }
        return result.toString();
    }

    private static char[] buildGeneratedCredentialChars() {
        StringBuilder chars = new StringBuilder();
        appendRange(chars, 'A', 'Z', "IO");
        appendRange(chars, 'a', 'z', "l");
        appendRange(chars, '2', '9', "");
        return chars.toString().toCharArray();
    }

    private static void appendRange(StringBuilder target, char first, char last, String excluded) {
        for (char current = first; current <= last; current++) {
            if (excluded.indexOf(current) < 0) {
                target.append(current);
            }
        }
    }

    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
