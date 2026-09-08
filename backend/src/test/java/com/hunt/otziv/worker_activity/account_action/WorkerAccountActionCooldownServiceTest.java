package com.hunt.otziv.worker_activity.account_action;

import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkerAccountActionCooldownServiceTest {
    private final WorkerAccountActionCooldownRepository repository = mock(WorkerAccountActionCooldownRepository.class);
    private final WorkerAccountActionCooldownService service = new WorkerAccountActionCooldownService(repository);
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authenticate("ROLE_WORKER");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptedAttemptHasHeadersAndDoesNotChargeSameRequestFallbackTwice() {
        var state = WorkerAccountActionCooldownState.of(60, 61000, 1000);
        when(repository.admit("specialist"))
                .thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, state));

        service.admitCurrentAction();
        service.admitCurrentAction();

        verify(repository, times(1)).admit("specialist");
        assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEqualTo(state.availableAt().toString());
        assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("true");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void rejectionKeepsExistingDeadlineAndHasStructured429WithRetryAfter() {
        var state = WorkerAccountActionCooldownState.of(60, 61000, 2000);
        when(repository.admit("specialist"))
                .thenReturn(new WorkerAccountActionCooldownRepository.Admission(false, state));

        assertThatThrownBy(service::admitCurrentAction).isInstanceOfSatisfying(
                WorkerAccountActionCooldownException.class, exception -> {
                    var result = new WorkerAccountActionCooldownExceptionHandler().handle(exception);
                    assertThat(result.getStatusCode().value()).isEqualTo(429);
                    assertThat(result.getHeaders().getFirst("Retry-After")).isEqualTo("59");
                    assertThat(result.getBody().code()).isEqualTo("WORKER_ACCOUNT_ACTION_COOLDOWN");
                    assertThat(result.getBody().availableAt()).isEqualTo(state.availableAt());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitWorkerIsAdmittedEvenWhenAmbientActorIsAdminOrAbsent(boolean ambientAdmin) {
        if (ambientAdmin) authenticate("ROLE_ADMIN"); else SecurityContextHolder.clearContext();
        var worker = namedWorker("explicit-worker");
        var state = WorkerAccountActionCooldownState.of(60, 61000, 1000);
        when(repository.admit("explicit-worker"))
                .thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, state));

        service.admitAction(worker);

        verify(repository).admit("explicit-worker");
        verifyNoMoreInteractions(repository);
        assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEqualTo(state.availableAt().toString());
        assertThat(accepted().username()).isEqualTo("explicit-worker");
    }

    @Test
    void missingExplicitActorDoesNotFallBackToAmbientWorker() {
        service.admitAction(null);
        verifyNoInteractions(repository);
        assertThat(accepted()).isNull();
    }

    @Test
    void explicitStaffDoesNotUseTheAmbientWorker() {
        service.admitAction(new UsernamePasswordAuthenticationToken("explicit-admin", "unused",
                Arrays.asList(new SimpleGrantedAuthority("ROLE_WORKER"), new SimpleGrantedAuthority("ROLE_ADMIN"))));
        verifyNoInteractions(repository);
    }

    @Test
    void sameExplicitUsernameStillDeduplicatesFallbackAdmission() {
        var state = WorkerAccountActionCooldownState.of(60, 61000, 1000);
        when(repository.admit("worker-a")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, state));

        service.admitAction(namedWorker("worker-a"));
        service.admitAction(namedWorker("worker-a"));

        verify(repository).admit("worker-a");
        verifyNoMoreInteractions(repository);
        assertThat(accepted().username()).isEqualTo("worker-a");
    }

    @Test
    void differentActorInSameRequestRequiresOwnAdmissionAndOwnHeaders() {
        var first = WorkerAccountActionCooldownState.of(60, 61000, 1000);
        var second = WorkerAccountActionCooldownState.of(90, 92000, 2000);
        when(repository.admit("worker-a")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, first));
        when(repository.admit("worker-b")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, second));

        service.admitAction(namedWorker("worker-a"));
        service.admitAction(namedWorker("worker-b"));
        service.admitAction(namedWorker("worker-b"));

        verify(repository).admit("worker-a");
        verify(repository).admit("worker-b");
        verifyNoMoreInteractions(repository);
        assertThat(accepted().username()).isEqualTo("worker-b");
        assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEqualTo(second.availableAt().toString());
    }

    @Test
    void rejectedDifferentActorDoesNotInheritAdmissionOrLateResponseStateFromFirstActor() {
        var first = WorkerAccountActionCooldownState.of(60, 61000, 1000);
        var rejected = WorkerAccountActionCooldownState.of(90, 92000, 2000);
        when(repository.admit("worker-a")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true, first));
        when(repository.admit("worker-b")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(false, rejected));
        service.admitAction(namedWorker("worker-a"));

        assertThatThrownBy(() -> service.admitAction(namedWorker("worker-b")))
                .isInstanceOf(WorkerAccountActionCooldownException.class);
        var request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        new WorkerAccountActionCooldownResponseAdvice(repository).postHandle(request, response, new Object(), null);

        assertThat(accepted()).isNull();
        assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEqualTo(rejected.availableAt().toString());
        verify(repository).admit("worker-a");
        verify(repository).admit("worker-b");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void untypedRequestMarkerCannotBypassAdmission() {
        var request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        request.setAttribute(WorkerAccountActionCooldownService.ACCEPTED_REQUEST_ATTRIBUTE, Boolean.TRUE);
        when(repository.admit("specialist")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true,
                WorkerAccountActionCooldownState.of(60, 61000, 1000)));
        service.admitCurrentAction();
        verify(repository).admit("specialist");
        assertThat(accepted().username()).isEqualTo("specialist");
    }

    @Test
    void explicitAdmissionAlsoWorksWithoutAnHttpRequest() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        when(repository.admit("explicit-worker")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(true,
                WorkerAccountActionCooldownState.of(60, 61000, 1000)));
        service.admitAction(namedWorker("explicit-worker"));
        verify(repository).admit("explicit-worker");
    }

    @Test
    void zeroDisablesAdmissionEvenWhenPreviousPauseExists() {
        when(repository.admit("specialist")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(
                true, WorkerAccountActionCooldownState.of(0, 0, 1000)));
        service.admitCurrentAction();
        assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("false");
    }

    @Test
    void disabledFlagAdmitsActionAndReportsConfiguredDuration() {
        when(repository.admit("specialist")).thenReturn(new WorkerAccountActionCooldownRepository.Admission(
                true, WorkerAccountActionCooldownState.of(false, 180, 181000, 1000)));

        service.admitCurrentAction();

        assertThat(response.getHeader("X-Worker-Account-Action-Enabled")).isEqualTo("false");
        assertThat(response.getHeader("X-Worker-Account-Action-Duration-Seconds")).isEqualTo("180");
        assertThat(response.getHeader("X-Worker-Account-Action-Available-At")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER", "ROLE_OPERATOR"})
    void nonWorkersAreNotLimited(String role) {
        authenticate(role);
        service.admitCurrentAction();
        assertThat(service.currentState().enabled()).isFalse();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "ROLE_OWNER", "ROLE_MANAGER"})
    void staffPrivilegeOverridesWorkerRole(String role) {
        authenticate("ROLE_WORKER", role);
        service.admitCurrentAction();
        assertThat(service.currentState().enabled()).isFalse();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "3601", "60.5"})
    void invalidStoredDurationUsesSafeDefault(String duration) {
        assertThat(WorkerAccountActionCooldownRepository.parseDuration(duration)).isEqualTo(60);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "2", "60", "off"})
    void absentOrMalformedEnabledSettingKeepsLegacyEnabledDefault(String enabled) {
        assertThat(WorkerAccountActionCooldownRepository.parseEnabled(enabled)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "0", "no", "NO", " false "})
    void explicitFalseSettingDisablesThePolicy(String enabled) {
        assertThat(WorkerAccountActionCooldownRepository.parseEnabled(enabled)).isFalse();
    }

    @Test
    void disabledStateIgnoresDeadlineAndPositiveRemainingSecondsRoundUp() {
        assertThat(WorkerAccountActionCooldownState.of(60, 1001, 1000).remainingSeconds()).isEqualTo(1);
        assertThat(WorkerAccountActionCooldownState.of(0, 1001, 1000).remainingSeconds()).isZero();
        assertThat(WorkerAccountActionCooldownState.of(60, 1000, 1000).availableAt()).isNull();
        var disabled = WorkerAccountActionCooldownState.of(false, 180, 181000, 1000);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.durationSeconds()).isEqualTo(180);
        assertThat(disabled.remainingSeconds()).isZero();
        assertThat(disabled.availableAt()).isNull();
        assertThat(WorkerAccountActionCooldownState.of(true, 0, 1001, 1000).enabled()).isFalse();
    }

    private void authenticate(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "specialist", "unused", Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private UsernamePasswordAuthenticationToken namedWorker(String username) {
        return new UsernamePasswordAuthenticationToken(username, "unused", Arrays.asList(new SimpleGrantedAuthority("ROLE_WORKER")));
    }

    private WorkerAccountActionCooldownService.AcceptedAction accepted() {
        var request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        return (WorkerAccountActionCooldownService.AcceptedAction) request.getAttribute(WorkerAccountActionCooldownService.ACCEPTED_REQUEST_ATTRIBUTE);
    }
}
