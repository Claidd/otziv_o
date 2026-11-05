package com.hunt.otziv.manager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.manager.service.ManagerAccessService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;

class ScopedCompanyPathInterceptorTest {

    private final ManagerAccessService access = mock(ManagerAccessService.class);
    private final ScopedCompanyPathInterceptor interceptor = new ScopedCompanyPathInterceptor(access);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void managerCannotReachAnotherManagersCompanyResearch() {
        authenticate("ROLE_MANAGER");
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(access).requireCompanyAccess(eq(42L), any());

        assertThatThrownBy(() -> interceptor.preHandle(request(42L), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void marketologKeepsCrossCompanyResearch() throws Exception {
        authenticate("ROLE_MARKETOLOG");

        assertThat(interceptor.preHandle(request(42L), new MockHttpServletResponse(), new Object())).isTrue();
        verify(access, never()).requireCompanyAccess(any(), any());
    }

    @Test
    void routesWithoutCompanyAreUntouched() throws Exception {
        authenticate("ROLE_MANAGER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/reputation/status");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verify(access, never()).requireCompanyAccess(any(), any());
    }

    private static MockHttpServletRequest request(Long companyId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/ai/reputation/companies/" + companyId + "/deep-research/jobs");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("companyId", String.valueOf(companyId)));
        return request;
    }

    private static void authenticate(String role) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority(role)));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
