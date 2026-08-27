package com.hunt.otziv.config.jwt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.l_lead.dto.LeadDtoTransfer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JwtAuthFilterTest {

    private static final String SECRET = "01234567890123456789012345678901";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JwtService jwtService = new JwtService();
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        filter = new JwtAuthFilter(objectMapper, jwtService, LeadTokenReplayGuard.inMemoryForTests());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsModernDedicatedHeaderOnceAndRejectsReplay() throws Exception {
        LeadDtoTransfer dto = lead();
        String token = jwtService.generateToken(dto);
        FilterChain firstChain = mock(FilterChain.class);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();

        filter.doFilter(request(dto, LeadIntegrationHeaders.TOKEN, token), firstResponse, firstChain);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        verify(firstChain).doFilter(any(HttpServletRequest.class), same(firstResponse));

        FilterChain replayChain = mock(FilterChain.class);
        MockHttpServletResponse replayResponse = new MockHttpServletResponse();
        filter.doFilter(request(dto, LeadIntegrationHeaders.TOKEN, token), replayResponse, replayChain);

        assertThat(replayResponse.getStatus()).isEqualTo(403);
        verify(replayChain, never()).doFilter(any(), any());
    }

    @Test
    void acceptsLegacyBearerOnlyDuringTheBoundedRolloutWindow() throws Exception {
        ReflectionTestUtils.setField(filter, "legacyBearerEnabled", true);
        ReflectionTestUtils.setField(
                filter,
                "legacyBearerAcceptUntil",
                Instant.now().plusSeconds(60).toString()
        );
        LeadDtoTransfer dto = lead();
        String token = jwtService.generateLegacyTransferToken(dto);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(dto, "Authorization", "Bearer " + token), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(HttpServletRequest.class), same(response));
    }

    @Test
    void rejectsLegacyBearerAfterTheBoundedRolloutWindow() throws Exception {
        ReflectionTestUtils.setField(filter, "legacyBearerEnabled", true);
        ReflectionTestUtils.setField(
                filter,
                "legacyBearerAcceptUntil",
                Instant.now().minusSeconds(60).toString()
        );
        LeadDtoTransfer dto = lead();
        String token = jwtService.generateLegacyTransferToken(dto);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(dto, "Authorization", "Bearer " + token), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void neverDowngradesAnInvalidDedicatedTokenToLegacyBearer() throws Exception {
        LeadDtoTransfer dto = lead();
        MockHttpServletRequest request = request(dto, LeadIntegrationHeaders.TOKEN, "invalid");
        request.addHeader("Authorization", "Bearer " + jwtService.generateLegacyTransferToken(dto));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void releasesReplayReservationWhenDownstreamThrows() throws Exception {
        LeadDtoTransfer dto = lead();
        String token = jwtService.generateToken(dto);
        FilterChain failedChain = mock(FilterChain.class);
        doThrow(new jakarta.servlet.ServletException("temporary failure"))
                .when(failedChain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(
                request(dto, LeadIntegrationHeaders.TOKEN, token),
                new MockHttpServletResponse(),
                failedChain
        )).isInstanceOf(jakarta.servlet.ServletException.class);

        FilterChain retryChain = mock(FilterChain.class);
        MockHttpServletResponse retryResponse = new MockHttpServletResponse();
        filter.doFilter(request(dto, LeadIntegrationHeaders.TOKEN, token), retryResponse, retryChain);

        assertThat(retryResponse.getStatus()).isEqualTo(200);
        verify(retryChain).doFilter(any(HttpServletRequest.class), same(retryResponse));
    }

    @Test
    void releasesReplayReservationAfterDownstreamServerError() throws Exception {
        LeadDtoTransfer dto = lead();
        String token = jwtService.generateToken(dto);
        FilterChain failedChain = mock(FilterChain.class);
        doAnswer(invocation -> {
            ((MockHttpServletResponse) invocation.getArgument(1)).setStatus(503);
            return null;
        }).when(failedChain).doFilter(any(), any());

        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        filter.doFilter(request(dto, LeadIntegrationHeaders.TOKEN, token), failedResponse, failedChain);
        assertThat(failedResponse.getStatus()).isEqualTo(503);

        FilterChain retryChain = mock(FilterChain.class);
        MockHttpServletResponse retryResponse = new MockHttpServletResponse();
        filter.doFilter(contextRequest(dto, token), retryResponse, retryChain);

        assertThat(retryResponse.getStatus()).isEqualTo(200);
        verify(retryChain).doFilter(any(HttpServletRequest.class), same(retryResponse));
    }

    private MockHttpServletRequest request(LeadDtoTransfer dto, String header, String value) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/leads/import");
        request.setContentType("application/json");
        request.setContent(objectMapper.writeValueAsBytes(dto));
        request.addHeader(header, value);
        return request;
    }

    private MockHttpServletRequest contextRequest(LeadDtoTransfer dto, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/api/leads/import;source=vps");
        request.setContextPath("/app");
        request.setContentType("application/json");
        request.setContent(objectMapper.writeValueAsBytes(dto));
        request.addHeader(LeadIntegrationHeaders.TOKEN, token);
        return request;
    }

    private LeadDtoTransfer lead() {
        return LeadDtoTransfer.builder()
                .telephoneLead("79001234567")
                .cityLead("Irkutsk")
                .createDate(LocalDate.of(2026, 8, 3))
                .companyName("Canonical company")
                .build();
    }
}
