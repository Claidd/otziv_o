package com.hunt.otziv.u_users.keycloak;

import com.hunt.otziv.u_users.dto.CreateKeycloakUserRequest;
import com.hunt.otziv.u_users.dto.UpdateKeycloakUserRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
@RequiredArgsConstructor
public class KeycloakAdminClient {

    private static final String PASSWORD_CREDENTIAL_TYPE = "password";

    private final KeycloakAdminProperties properties;
    private final RestClient restClient = RestClient.create();

    private String adminToken;
    private Instant adminTokenExpiresAt = Instant.EPOCH;

    public String createUser(CreateKeycloakUserRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", request.getUsername());
        payload.put("enabled", request.isEnabled());
        payload.put("emailVerified", request.isEmailVerified());

        if (hasText(request.getEmail())) {
            payload.put("email", request.getEmail());
        }
        if (hasText(request.getFio())) {
            payload.put("firstName", request.getFio());
        }

        payload.put("credentials", List.of(Map.of(
                "type", PASSWORD_CREDENTIAL_TYPE,
                "value", request.getPassword(),
                "temporary", request.isTemporaryPassword()
        )));

        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(adminUri("users"))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            URI location = response.getHeaders().getLocation();
            if (location == null) {
                throw new ResponseStatusException(BAD_GATEWAY, "Keycloak did not return created user location");
            }

            return extractUserId(location);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == CONFLICT.value()) {
                throw new ResponseStatusException(CONFLICT, "Keycloak user already exists", e);
            }
            throw keycloakException("Failed to create Keycloak user", e);
        }
    }

    public Optional<String> findUserIdByUsername(String username) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUri(adminUri("users"))
                    .queryParam("username", username)
                    .queryParam("exact", true)
                    .build()
                    .toUri();

            KeycloakUserRepresentation[] users = restClient.get()
                    .uri(uri)
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .body(KeycloakUserRepresentation[].class);

            if (users == null || users.length == 0) {
                return Optional.empty();
            }

            return Arrays.stream(users)
                    .filter(user -> username.equalsIgnoreCase(user.username()))
                    .map(KeycloakUserRepresentation::id)
                    .findFirst();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to find Keycloak user by username", e);
        }
    }

    public void assignRealmRoles(String keycloakUserId, Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }

        List<KeycloakRoleRepresentation> roles = roleNames.stream()
                .map(this::getRealmRole)
                .toList();

        try {
            restClient.post()
                    .uri(adminUri("users", keycloakUserId, "role-mappings", "realm"))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(roles)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to assign Keycloak realm roles", e);
        }
    }

    public void removeRealmRoles(String keycloakUserId, Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) {
            return;
        }

        List<KeycloakRoleRepresentation> roles = roleNames.stream()
                .map(this::getRealmRole)
                .toList();

        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(adminUri("users", keycloakUserId, "role-mappings", "realm"))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(roles)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to remove Keycloak realm roles", e);
        }
    }

    public void updateUser(String keycloakUserId, String username, UpdateKeycloakUserRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("enabled", request.isEnabled());

        if (hasText(request.getEmail())) {
            payload.put("email", request.getEmail());
        }
        if (hasText(request.getFio())) {
            payload.put("firstName", request.getFio());
        }

        try {
            restClient.put()
                    .uri(adminUri("users", keycloakUserId))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to update Keycloak user", e);
        }
    }

    public void resetPassword(String keycloakUserId, String password, boolean temporary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", PASSWORD_CREDENTIAL_TYPE);
        payload.put("value", password);
        payload.put("temporary", temporary);

        try {
            restClient.put()
                    .uri(adminUri("users", keycloakUserId, "reset-password"))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to reset Keycloak user password", e);
        }
    }

    public void deleteUser(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            return;
        }

        try {
            restClient.delete()
                    .uri(adminUri("users", keycloakUserId))
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ignored) {
            // Best-effort rollback for external Keycloak state.
        }
    }

    private KeycloakRoleRepresentation getRealmRole(String roleName) {
        try {
            return restClient.get()
                    .uri(adminUri("roles", roleName))
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .body(KeycloakRoleRepresentation.class);
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to read Keycloak realm role: " + roleName, e);
        }
    }

    private synchronized String getAdminToken() {
        if (adminToken != null && Instant.now().isBefore(adminTokenExpiresAt.minusSeconds(30))) {
            return adminToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        try {
            KeycloakTokenResponse tokenResponse = restClient.post()
                    .uri(realmUri("protocol", "openid-connect", "token"))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KeycloakTokenResponse.class);

            if (tokenResponse == null || tokenResponse.access_token() == null) {
                throw new ResponseStatusException(BAD_GATEWAY, "Keycloak token response is empty");
            }

            adminToken = tokenResponse.access_token();
            adminTokenExpiresAt = Instant.now().plusSeconds(tokenResponse.expires_in());
            return adminToken;
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to get Keycloak admin token", e);
        }
    }

    private void setBearerAuth(HttpHeaders headers) {
        headers.setBearerAuth(getAdminToken());
    }

    private URI adminUri(String... pathSegments) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(properties.getServerUrl())
                .pathSegment("admin", "realms", properties.getRealm());

        for (String pathSegment : pathSegments) {
            builder.pathSegment(pathSegment);
        }

        return builder.build().toUri();
    }

    private URI realmUri(String... pathSegments) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(properties.getServerUrl())
                .pathSegment("realms", properties.getRealm());

        for (String pathSegment : pathSegments) {
            builder.pathSegment(pathSegment);
        }

        return builder.build().toUri();
    }

    private String extractUserId(URI location) {
        String path = location.getPath();
        int slashIndex = path.lastIndexOf('/');
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseStatusException keycloakException(String message, RestClientResponseException e) {
        return new ResponseStatusException(BAD_GATEWAY, message + ": " + e.getStatusText(), e);
    }

    private record KeycloakTokenResponse(String access_token, long expires_in) {
    }

    private record KeycloakUserRepresentation(
            String id,
            String username
    ) {
    }

    private record KeycloakRoleRepresentation(
            String id,
            String name,
            String description,
            Boolean composite,
            Boolean clientRole,
            String containerId
    ) {
    }
}
