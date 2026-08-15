package com.hunt.otziv.manager_daily_summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.servlet.FilterChain;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ManagerReportReviewRestrictionFilterTest {

    private ManagerReportReviewAccessPolicy accessPolicy;
    private ManagerReportReviewCheckInService checkInService;
    private UserRepository userRepository;
    private ManagerReportReviewRestrictionFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        accessPolicy = mock(ManagerReportReviewAccessPolicy.class);
        checkInService = mock(ManagerReportReviewCheckInService.class);
        userRepository = mock(UserRepository.class);
        filter = new ManagerReportReviewRestrictionFilter(
                accessPolicy,
                checkInService,
                userRepository,
                new ObjectMapper()
        );
        chain = mock(FilterChain.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "manager",
                        "token",
                        List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksBusinessApiForRestrictedManager() throws Exception {
        User manager = User.builder().id(17L).username("manager").active(true).build();
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(accessPolicy.state(manager)).thenReturn(new ManagerReportReviewAccessPolicy.AccessState(
                true,
                true,
                41L,
                LocalDate.of(2026, 7, 25),
                LocalDateTime.now(),
                "PLAN_PENDING",
                3,
                1,
                LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(2),
                "Завершите разбор"
        ));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(423);
        assertThat(response.getContentAsString()).contains("MANAGER_REPORT_REVIEW_REQUIRED", "\"reviewId\":41");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void leavesPersonalCabinetApiAvailable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cabinet/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(userRepository, never()).findByUsername("manager");
    }

    @Test
    void leavesContractorPaymentSelfSummaryAvailable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contractor-payments/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(userRepository, never()).findByUsername("manager");
    }

    @Test
    void exposesPublicByLinkApisWithAnonymousRightsForRestrictedManager() throws Exception {
        stubRestrictedManager();
        Authentication originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        for (String path : List.of(
                "/api/review-check/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/api/review-check;source=link/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/api/review-capability",
                "/api/payments/public/payment-token"
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<Authentication> downstreamAuthentication = new AtomicReference<>();
            FilterChain observingChain = (ignoredRequest, ignoredResponse) -> downstreamAuthentication.set(
                    SecurityContextHolder.getContext().getAuthentication()
            );

            filter.doFilter(request, response, observingChain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(downstreamAuthentication.get()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(originalAuthentication);
        }
    }

    @Test
    void restrictedManagerUsesAnonymousRightsOnLegacyReviewLinkIncludingPayOk() throws Exception {
        stubRestrictedManager();
        Authentication originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        for (String path : List.of(
                "/review/editReviews/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/review/editReviews/2ca03e9b-8768-4c84-b222-d718c15c80c9/publish",
                "/review/editReviewses/2ca03e9b-8768-4c84-b222-d718c15c80c9",
                "/review/editReviews/2ca03e9b-8768-4c84-b222-d718c15c80c9/payOk"
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            AtomicReference<Authentication> downstreamAuthentication = new AtomicReference<>();
            FilterChain observingChain = (ignoredRequest, ignoredResponse) -> downstreamAuthentication.set(
                    SecurityContextHolder.getContext().getAuthentication()
            );

            filter.doFilter(request, new MockHttpServletResponse(), observingChain);

            assertThat(downstreamAuthentication.get()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(originalAuthentication);
        }
    }

    @Test
    void legacyReviewNearMissRemainsOutsideAnonymousDowngrade() throws Exception {
        stubRestrictedManager();
        Authentication originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        AtomicReference<Authentication> downstreamAuthentication = new AtomicReference<>();
        FilterChain observingChain = (ignoredRequest, ignoredResponse) -> downstreamAuthentication.set(
                SecurityContextHolder.getContext().getAuthentication()
        );

        filter.doFilter(
                new MockHttpServletRequest("POST", "/review/editReviewsAdmin/17"),
                new MockHttpServletResponse(),
                observingChain
        );

        assertThat(downstreamAuthentication.get()).isSameAs(originalAuthentication);
    }

    @Test
    void keepsAuthenticatedRightsOnPublicLinkWhenManagerIsNotRestricted() throws Exception {
        User manager = User.builder().id(17L).username("manager").active(true).build();
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(accessPolicy.state(manager)).thenReturn(new ManagerReportReviewAccessPolicy.AccessState(
                false,
                false,
                null,
                null,
                null,
                "OPEN",
                0,
                0,
                null,
                null,
                ""
        ));
        Authentication originalAuthentication = SecurityContextHolder.getContext().getAuthentication();
        AtomicReference<Authentication> downstreamAuthentication = new AtomicReference<>();
        FilterChain observingChain = (ignoredRequest, ignoredResponse) -> downstreamAuthentication.set(
                SecurityContextHolder.getContext().getAuthentication()
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/review-check/2ca03e9b-8768-4c84-b222-d718c15c80c9"
        );

        filter.doFilter(request, new MockHttpServletResponse(), observingChain);

        assertThat(downstreamAuthentication.get()).isSameAs(originalAuthentication);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(originalAuthentication);
    }

    @Test
    void blocksWorkActionsEvenWhenTheirEndpointIsUnderCabinet() throws Exception {
        User manager = User.builder().id(17L).username("manager").active(true).build();
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(accessPolicy.state(manager)).thenReturn(new ManagerReportReviewAccessPolicy.AccessState(
                true,
                true,
                41L,
                LocalDate.of(2026, 7, 25),
                LocalDateTime.now(),
                "PLAN_PENDING",
                3,
                1,
                LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(2),
                "Завершите разбор"
        ));
        MockHttpServletRequest request =
                new MockHttpServletRequest("PUT", "/api/cabinet/manual-payment-tasks/9/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(423);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void enforcesRestrictionBehindANonRootServletContext() throws Exception {
        stubRestrictedManager();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ctx/api/orders");
        request.setContextPath("/ctx");
        request.setServletPath("/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(423);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void nearMissOfPublicReviewPathRemainsRestricted() throws Exception {
        stubRestrictedManager();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/review-check-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(423);
        verify(chain, never()).doFilter(request, response);
    }

    private void stubRestrictedManager() {
        User manager = User.builder().id(17L).username("manager").active(true).build();
        when(userRepository.findByUsername("manager")).thenReturn(Optional.of(manager));
        when(accessPolicy.state(manager)).thenReturn(new ManagerReportReviewAccessPolicy.AccessState(
                true,
                true,
                41L,
                LocalDate.of(2026, 7, 25),
                LocalDateTime.now(),
                "PLAN_PENDING",
                3,
                1,
                LocalDateTime.now().minusHours(3),
                LocalDateTime.now().minusHours(2),
                "Завершите разбор"
        ));
    }
}
