package com.hunt.otziv.config.audit.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class RestApiAuditInterceptorTest {

    private static final String PATTERN = "/api/test/audit/{reviewId}/text";

    private final ListAppender<ILoggingEvent> accessLog = new ListAppender<>();
    private final RestApiAuditInterceptor interceptor = new RestApiAuditInterceptor(new RestApiAuditActionResolver());
    private Level previousLevel;

    @BeforeEach
    void attach() {
        SecurityContextHolder.clearContext();
        Logger logger = accessLogger();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        accessLog.start();
        logger.addAppender(accessLog);
    }

    @AfterEach
    void detach() {
        Logger logger = accessLogger();
        logger.detachAppender(accessLog);
        logger.setLevel(previousLevel);
        SecurityContextHolder.clearContext();
    }

    @Test
    void unresolvedExceptionIsLoggedAsServerErrorInsteadOfDefaultOk() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.afterCompletion(request(), response, handler(), new IllegalStateException("commit failed"));

        assertThat(accessLog.list).hasSize(1);
        String line = accessLog.list.getFirst().getFormattedMessage();
        assertThat(line)
                .startsWith("PUT " + PATTERN + " | httpStatus=500 |")
                .endsWith("| error=IllegalStateException")
                .doesNotContain("httpStatus=200");
    }

    @Test
    void successfulRequestKeepsItsRealStatus() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(204);

        interceptor.afterCompletion(request(), response, handler(), null);

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.getFirst().getFormattedMessage())
                .startsWith("PUT " + PATTERN + " | httpStatus=204 |")
                .doesNotContain("error=");
    }

    @Test
    void errorStatusAlreadySetBeforeTheExceptionIsKept() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(404);

        interceptor.afterCompletion(request(), response, handler(), new IllegalStateException("late failure"));

        assertThat(accessLog.list).hasSize(1);
        assertThat(accessLog.list.getFirst().getFormattedMessage())
                .startsWith("PUT " + PATTERN + " | httpStatus=404 |");
    }

    @Test
    void loggedStatusReplacesOnlyNonErrorStatusesOfFailedRequests() {
        assertThat(RestApiAuditInterceptor.loggedStatus(200, new RuntimeException())).isEqualTo(500);
        assertThat(RestApiAuditInterceptor.loggedStatus(302, new RuntimeException())).isEqualTo(500);
        assertThat(RestApiAuditInterceptor.loggedStatus(409, new RuntimeException())).isEqualTo(409);
        assertThat(RestApiAuditInterceptor.loggedStatus(503, new RuntimeException())).isEqualTo(503);
        assertThat(RestApiAuditInterceptor.loggedStatus(200, null)).isEqualTo(200);
        assertThat(RestApiAuditInterceptor.loggedStatus(404, null)).isEqualTo(404);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/test/audit/5/text");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, PATTERN);
        return request;
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        // Deliberately not a @RestController: test classes sit inside the scanned
        // package, so a stereotype here could leak a bean into Spring context tests.
        // A ResponseEntity return type is enough for the interceptor's REST check.
        return new HandlerMethod(new AuditedEndpoint(), AuditedEndpoint.class.getMethod("save"));
    }

    private static Logger accessLogger() {
        return (Logger) LoggerFactory.getLogger("ACCESS_LOGGER");
    }

    static class AuditedEndpoint {
        public ResponseEntity<Void> save() {
            return ResponseEntity.noContent().build();
        }
    }
}
