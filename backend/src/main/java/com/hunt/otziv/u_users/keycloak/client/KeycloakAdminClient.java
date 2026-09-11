package com.hunt.otziv.u_users.keycloak.client;

import com.hunt.otziv.u_users.keycloak.config.KeycloakAdminProperties;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class KeycloakAdminClient {

    private static final String PASSWORD_CREDENTIAL_TYPE = "password";

    private final KeycloakAdminProperties properties;
    private final RestClient restClient = boundedClient();

    private static RestClient boundedClient() {
        var http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** No successful-authority cache: logout must affect the next request. */
    public Map<String, Object> introspectAccessToken(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        form.add("token_type_hint", "access_token");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        return restClient.post().uri(realmUri("protocol", "openid-connect", "token", "introspect"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public List<SessionView> userSessions(String subject) {
        SessionView[] result = restClient.get().uri(adminUri("users", subject, "sessions"))
                .headers(this::setBearerAuth).retrieve().body(SessionView[].class);
        return result == null ? List.of() : List.of(result);
    }

    public List<SessionView> offlineSessions(String subject, String clientUuid) {
        SessionView[] result = restClient.get().uri(adminUri("users", subject, "offline-sessions", clientUuid))
                .headers(this::setBearerAuth).retrieve().body(SessionView[].class);
        return result == null ? List.of() : List.of(result);
    }

    public List<CredentialView> credentials(String subject) {
        CredentialView[] result = restClient.get().uri(adminUri("users", subject, "credentials"))
                .headers(this::setBearerAuth).retrieve().body(CredentialView[].class);
        return result == null ? List.of() : List.of(result);
    }

    /** Includes grants created by offline_access even when interactive consent is disabled. */
    public List<String> offlineClientUuids(String subject) {
        List<Map<String, Object>> consents = userConsents(subject);
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> consent : consents) {
            if (consent.get("additionalGrants") instanceof List<?> grants) {
                for (Object raw : grants) {
                    if (raw instanceof Map<?, ?> grant && "Offline Token".equals(grant.get("key"))
                            && grant.get("client") instanceof String id && !id.isBlank()) ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    public Optional<String> offlineClientUuid(String subject, String clientId) {
        for (var consent : userConsents(subject)) {
            if (!clientId.equals(consent.get("clientId"))) continue;
            if (consent.get("additionalGrants") instanceof List<?> grants) {
                for (Object raw : grants) {
                    if (raw instanceof Map<?, ?> grant && "Offline Token".equals(grant.get("key"))
                            && grant.get("client") instanceof String id && !id.isBlank()) return Optional.of(id);
                }
            }
        }
        return Optional.empty();
    }

    private List<Map<String,Object>> userConsents(String subject) {
        var result = restClient.get().uri(adminUri("users", subject, "consents"))
                .headers(this::setBearerAuth).retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String,Object>>>() {});
        if (result == null) throw new IllegalStateException("Keycloak consent lookup unavailable");
        return result;
    }

    /** Idempotent exact-session deletion cannot invalidate a subsequent fresh login. */
    public void deleteSession(String sessionId, boolean offline) {
        URI uri = UriComponentsBuilder.fromUri(adminUri("sessions", sessionId))
                .queryParam("isOffline", offline).build().toUri();
        try {
            restClient.delete().uri(uri).headers(this::setBearerAuth).retrieve().toBodilessEntity();
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() != 404) throw error;
        }
    }

    public Set<String> currentRealmRoleNames(String subject) {
        KeycloakRoleRepresentation[] result = restClient.get()
                .uri(adminUri("users", subject, "role-mappings", "realm", "composite"))
                .headers(this::setBearerAuth).retrieve().body(KeycloakRoleRepresentation[].class);
        if (result == null) throw new IllegalStateException("Keycloak roles lookup unavailable");
        return Arrays.stream(result).map(KeycloakRoleRepresentation::name)
                .collect(java.util.stream.Collectors.toSet());
    }

    public String currentSecurityGeneration(String subject) {
        SecurityGenerationView result = restClient.get()
                .uri(realmUri("otziv-security", "generation", subject))
                .headers(this::setBearerAuth).retrieve().body(SecurityGenerationView.class);
        if (result == null || result.protocolVersion() != 1 || !subject.equals(result.subject())
                || result.generation() == null || !result.generation().matches("v1:[0-9]{1,19}:[0-9]{1,19}")) {
            throw new IllegalStateException("Issuer security generation evidence is unavailable");
        }
        return result.generation();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityGenerationView(int protocolVersion, String subject, String generation) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionView(String id, String userId, long start, Map<String, String> clients) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CredentialView(String id, String type, Long createdDate) {}

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
                .map(this::getOrCreateRealmRole)
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

    public Set<String> getAssignedRealmRoleNames(String keycloakUserId) {
        try {
            KeycloakRoleRepresentation[] roles = restClient.get()
                    .uri(adminUri("users", keycloakUserId, "role-mappings", "realm"))
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .body(KeycloakRoleRepresentation[].class);

            if (roles == null || roles.length == 0) {
                return Set.of();
            }

            return Arrays.stream(roles)
                    .map(KeycloakRoleRepresentation::name)
                    .filter(this::hasText)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to read assigned Keycloak realm roles", e);
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
            if (e.getStatusCode().value() == NOT_FOUND.value()) {
                throw new ResponseStatusException(NOT_FOUND, "Keycloak user not found", e);
            }
            if (e.getStatusCode().value() == CONFLICT.value()) {
                throw new ResponseStatusException(CONFLICT, "Keycloak user update conflicts with existing account", e);
            }
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

    public void logoutUserSessions(String keycloakUserId) {
        try {
            restClient.post()
                    .uri(adminUri("users", keycloakUserId, "logout"))
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to logout Keycloak user sessions", e);
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

    public void deleteUserStrict(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            return;
        }

        try {
            restClient.delete()
                    .uri(adminUri("users", keycloakUserId))
                    .headers(this::setBearerAuth)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw keycloakException("Failed to delete Keycloak user", e);
        }
    }

    private KeycloakRoleRepresentation getOrCreateRealmRole(String roleName) {
        try {
            return getRealmRole(roleName);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != NOT_FOUND.value()) {
                throw keycloakException("Failed to read Keycloak realm role: " + roleName, e);
            }
            createRealmRoleIfMissing(roleName);
            return getRealmRole(roleName);
        }
    }

    private KeycloakRoleRepresentation getRealmRole(String roleName) {
        return restClient.get()
                .uri(adminUri("roles", roleName))
                .headers(this::setBearerAuth)
                .retrieve()
                .body(KeycloakRoleRepresentation.class);
    }

    private void createRealmRoleIfMissing(String roleName) {
        try {
            restClient.post()
                    .uri(adminUri("roles"))
                    .headers(this::setBearerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "name", roleName,
                            "description", "Managed by otziv backend"
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == CONFLICT.value()) {
                return;
            }
            throw keycloakException("Failed to create Keycloak realm role: " + roleName, e);
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
                .fromUriString(properties.getServerUrl())
                .pathSegment("admin", "realms", properties.getRealm());

        for (String pathSegment : pathSegments) {
            builder.pathSegment(pathSegment);
        }

        return builder.build().toUri();
    }

    private URI realmUri(String... pathSegments) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(properties.getServerUrl())
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
