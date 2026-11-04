package com.hunt.otziv.manager.security;

import com.hunt.otziv.manager.service.ManagerAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies the manager-board company visibility policy to any route carrying a
 * {@code {companyId}} path variable it is registered for (paid reputation research,
 * reports, exports, review ideas). Admins see every company, owners and managers only
 * their own. Marketologs keep their existing cross-company research access.
 */
public class ScopedCompanyPathInterceptor implements HandlerInterceptor {

    private static final Set<String> SCOPED_ROLES = Set.of("ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER");

    private final ManagerAccessService managerAccessService;

    public ScopedCompanyPathInterceptor(ManagerAccessService managerAccessService) {
        this.managerAccessService = managerAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables) || !variables.containsKey("companyId")) {
            return true;
        }
        Long companyId;
        try {
            companyId = Long.valueOf(String.valueOf(variables.get("companyId")));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная компания");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean scoped = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(SCOPED_ROLES::contains);
        if (scoped) {
            managerAccessService.requireCompanyAccess(companyId, authentication);
        }
        return true;
    }
}
