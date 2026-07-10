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
import com.hunt.otziv.u_users.services.KeycloakUserProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PerformerRegistrationService {

    private static final String PERFORMER_ROLE = "PERFORMER";
    private static final char[] GENERATED_CREDENTIAL_CHARS = buildGeneratedCredentialChars();

    private final KeycloakUserProvisioningService userProvisioningService;
    private final UserRepository userRepository;
    private final CityRepository cityRepository;
    private final PerformerProfileRepository performerProfileRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${telegram.bot.username:}")
    private String telegramBotUsername;

    @Transactional
    public RegisterPerformerResponse register(RegisterPerformerRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Данные исполнителя не переданы");
        }

        City city = cityRepository.findById(request.getCityId());
        if (city == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Город не найден");
        }

        String phone = normalizePhone(request.getPhoneNumber());
        if (phone.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите корректный телефон");
        }

        String username = "perf" + phone;
        String temporaryPassword = generateTemporaryPassword();
        String technicalEmail = username + "@performers.o-ogo.local";

        CreateKeycloakUserRequest createRequest = new CreateKeycloakUserRequest();
        createRequest.setUsername(username);
        createRequest.setEmail(technicalEmail);
        createRequest.setFio(trimToNull(request.getFio()));
        createRequest.setPhoneNumber("+" + phone);
        createRequest.setPassword(temporaryPassword);
        createRequest.setTemporaryPassword(true);
        createRequest.setEnabled(true);
        createRequest.setEmailVerified(false);
        createRequest.setRoles(new LinkedHashSet<>(Set.of(PERFORMER_ROLE)));

        CreatedKeycloakUserResponse created = userProvisioningService.createUser(createRequest);
        User user = userRepository.findById(created.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Пользователь создан, но не найден"));

        String token = "performer_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        PerformerProfile performer = PerformerProfile.builder()
                .user(user)
                .city(city)
                .gender(request.getGender() == null ? PerformerGender.NOT_SPECIFIED : request.getGender())
                .status(PerformerProfileStatus.ACTIVE)
                .telegramLinkToken(token)
                .registeredSource(trimToNull(request.getRegisteredSource()))
                .personalDataAcceptedAt(now)
                .rulesAcceptedAt(now)
                .honestReviewAcceptedAt(now)
                .lastActiveAt(now)
                .build();
        performerProfileRepository.save(performer);

        return RegisterPerformerResponse.builder()
                .userId(user.getId())
                .performerId(performer.getId())
                .username(user.getUsername())
                .temporaryPassword(temporaryPassword)
                .telegramLinkToken(token)
                .telegramLinkUrl(telegramLinkUrl(token))
                .status(performer.getStatus().name())
                .build();
    }

    private String telegramLinkUrl(String token) {
        if (!hasText(telegramBotUsername)) {
            return "";
        }
        return "https://t.me/" + telegramBotUsername.trim().replaceFirst("^@", "") + "?start=" + token;
    }

    private String normalizePhone(String value) {
        return value == null ? "" : value.replaceAll("\\D+", "");
    }

    private String generateTemporaryPassword() {
        StringBuilder result = new StringBuilder("T");
        for (int i = 0; i < 11; i++) {
            result.append(GENERATED_CREDENTIAL_CHARS[secureRandom.nextInt(GENERATED_CREDENTIAL_CHARS.length)]);
        }
        result.append("7!");
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
