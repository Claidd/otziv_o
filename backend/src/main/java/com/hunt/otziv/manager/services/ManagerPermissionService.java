package com.hunt.otziv.manager.services;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class ManagerPermissionService {

    public boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || role == null || authentication.getAuthorities() == null) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    public boolean hasAnyRole(Authentication authentication, String... roles) {
        if (roles == null) {
            return false;
        }

        for (String role : roles) {
            if (hasRole(authentication, role)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasOnlyWorkerRole(Authentication authentication) {
        return hasRole(authentication, "WORKER") && !hasAnyRole(authentication, "ADMIN", "OWNER", "MANAGER");
    }

    public String primaryReviewRole(Authentication authentication) {
        if (hasRole(authentication, "ADMIN")) {
            return "ROLE_ADMIN";
        }

        if (hasRole(authentication, "OWNER")) {
            return "ROLE_OWNER";
        }

        if (hasRole(authentication, "MANAGER")) {
            return "ROLE_MANAGER";
        }

        if (hasRole(authentication, "WORKER")) {
            return "ROLE_WORKER";
        }

        return "anonymous";
    }
}
