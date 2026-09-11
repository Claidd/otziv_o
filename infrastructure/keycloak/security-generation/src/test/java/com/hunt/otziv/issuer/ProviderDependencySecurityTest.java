package com.hunt.otziv.issuer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import jakarta.json.Json;
import jakarta.json.JsonException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;
import org.junit.jupiter.api.Test;

/** Bounded behavioral regressions: tiny payloads, loopback HTTP only, no service credentials. */
class ProviderDependencySecurityTest {
    private ObjectMapper constrainedMapper() {
        return new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(16).build()).build());
    }

    @Test void xmlDurationRespectsNumericInputLimit() {
        var error = assertThrows(Exception.class, () -> constrainedMapper().readValue(
                "\"PT" + "7".repeat(64) + "H\"", Duration.class));
        assertTrue(error.getMessage().contains("exceeds the maximum allowed"), error.toString());
    }

    @Test void xmlCalendarRespectsNumericInputLimit() {
        var error = assertThrows(Exception.class, () -> constrainedMapper().readValue(
                "\"" + "2".repeat(64) + "-01-01T00:00:00Z\"", XMLGregorianCalendar.class));
        assertTrue(error.getMessage().contains("exceeds the maximum allowed"), error.toString());
    }

    @Test void ordinaryXmlDurationAndCalendarStillDeserialize() throws Exception {
        assertEquals(12, constrainedMapper().readValue("\"PT12H\"", Duration.class).getHours());
        assertEquals(2026, new ObjectMapper().readValue("\"2026-09-08T00:00:00Z\"", XMLGregorianCalendar.class).getYear());
    }

    @Test void jsonConsumedCharacterLimitIncludesWhitespace() {
        var factory = Json.createReaderFactory(Map.of("org.eclipse.parsson.maxParsingLimit", 128));
        try (var reader = factory.createReader(new StringReader(" ".repeat(1024) + "{\"ok\":true}"))) {
            assertThrows(JsonException.class, reader::readObject);
        }
    }

    @Test void ordinaryJsonRemainsUsableUnderExplicitLimit() {
        var factory = Json.createReaderFactory(Map.of("org.eclipse.parsson.maxParsingLimit", 4096));
        try (var reader = factory.createReader(new StringReader("{\"ok\":true}"))) {
            assertTrue(reader.readObject().getBoolean("ok"));
        }
    }

    @Test void crossOriginRedirectStripsSensitiveHeaders() throws Exception {
        redirectHeaders(false);
    }

    @Test void sameOriginRedirectPreservesAuthorizedRequest() throws Exception {
        redirectHeaders(true);
    }

    private void redirectHeaders(boolean sameOrigin) throws Exception {
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var target = sameOrigin ? source : HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        var correlation = new AtomicReference<String>();
        String fixtureAuthorization = "Fixture " + java.util.UUID.randomUUID();
        String fixtureCookie = "correlation=" + java.util.UUID.randomUUID();
        target.createContext("/target", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Fixture-Correlation"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        source.createContext("/source", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        Vertx vertx = Vertx.vertx();
        try {
            source.start();
            if (!sameOrigin) target.start();
            var response = vertx.createHttpClient().request(HttpMethod.GET, source.getAddress().getPort(), "127.0.0.1", "/source")
                    .compose(request -> request.setFollowRedirects(true)
                            .putHeader("Authorization", fixtureAuthorization)
                            .putHeader("Cookie", fixtureCookie)
                            .putHeader("X-Fixture-Correlation", "public-correlation")
                            .send())
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(204, response.statusCode());
            assertEquals("public-correlation", correlation.get());
            if (sameOrigin) {
                assertEquals(fixtureAuthorization, authorization.get());
                // The patched upstream handler deliberately strips cookies even on same-origin redirects.
            } else {
                assertNull(authorization.get(), "Cross-origin redirects must not forward Authorization");
                assertNull(cookie.get(), "Cross-origin redirects must not forward Cookie");
            }
        } finally {
            source.stop(0);
            if (!sameOrigin) target.stop(0);
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
