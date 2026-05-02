package com.hunt.otziv.u_users.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CurrentUserController {

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (authentication == null || !authentication.isAuthenticated()) {
            response.put("authenticated", false);
            return response;
        }

        response.put("authenticated", true);
        response.put("name", authentication.getName());
        response.put("principalType", authentication.getPrincipal().getClass().getName());
        response.put("authorities", authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList());

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            addJwtDetails(response, jwtAuthentication.getToken());
        } else if (authentication.getPrincipal() instanceof Jwt jwt) {
            addJwtDetails(response, jwt);
        }

        return response;
    }

    private void addJwtDetails(Map<String, Object> response, Jwt jwt) {
        response.put("subject", jwt.getSubject());
        response.put("issuer", jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
        response.put("issuedAt", jwt.getIssuedAt());
        response.put("expiresAt", jwt.getExpiresAt());
        response.put("preferredUsername", jwt.getClaimAsString("preferred_username"));
        response.put("email", jwt.getClaimAsString("email"));
        response.put("clientId", jwt.getClaimAsString("client_id"));
        response.put("authorizedParty", jwt.getClaimAsString("azp"));
        response.put("realmRoles", extractRealmRoles(jwt));
    }

    private List<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }

        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> values)) {
            return List.of();
        }

        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .sorted()
                .toList();
    }
}
