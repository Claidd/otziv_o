package com.hunt.otziv.p_products.worker_access.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerIpIntelligenceClientTest {

    private HttpServer server;

    @Test
    void restartReusesOnlyFreshObservationsWithoutExtendingTheirExpiry() throws Exception {
        var properties = new WorkerCellularAccessProperties();
        properties.setIpIntelligenceEnabled(true);
        properties.setIpIntelligenceBaseUrl("http://127.0.0.1:1/");
        properties.setIpIntelligenceCacheTtl(Duration.ofHours(1));
        var store = org.mockito.Mockito.mock(WorkerIpClassificationStore.class);
        var now = java.time.Instant.now();
        var risk = new WorkerIpIntelligenceClient.IpIntelligence(true, true, true, "test", "ipquery");
        var entry = new WorkerIpClassificationStore.Entry(risk, now.minusSeconds(1800), now.plusSeconds(1800));
        org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(entry));
        for (int i = 0; i < 2; i++) {
            var client = new WorkerIpIntelligenceClient(properties, new ObjectMapper(), store);
            try { assertEquals(risk, client.lookup("203.0.113.10")); }
            finally { client.close(); }
        }
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).find(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofHours(1)));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shortenedPolicyRejectsPersistedObservationAndStoreFailureFallsBackToProvider() throws Exception {
        AtomicInteger requests = successfulProvider();
        var properties = new WorkerCellularAccessProperties();
        properties.setIpIntelligenceEnabled(true);
        properties.setIpIntelligenceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setIpIntelligenceCacheTtl(Duration.ofMinutes(1));
        var store = org.mockito.Mockito.mock(WorkerIpClassificationStore.class);
        var now = java.time.Instant.now();
        var old = new WorkerIpClassificationStore.Entry(
                new WorkerIpIntelligenceClient.IpIntelligence(true, false, true, "old", "ipquery"),
                now.minusSeconds(120), now.plusSeconds(3600));
        org.mockito.Mockito.when(store.find(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(old)).thenThrow(new IllegalStateException("offline"));
        org.mockito.Mockito.doThrow(new IllegalStateException("offline")).when(store)
                .save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        var client = new WorkerIpIntelligenceClient(properties, new ObjectMapper(), store);
        try {
            assertTrue(client.lookup("203.0.113.10").mobile());
            assertTrue(client.lookup("203.0.113.11").mobile());
            assertEquals(2, requests.get());
        } finally { client.close(); }
    }

    @Test
    void providerChangeInvalidatesMemoryAsWellAsPersistentIdentity() throws Exception {
        AtomicInteger requests = successfulProvider();
        var properties = new WorkerCellularAccessProperties();
        properties.setIpIntelligenceEnabled(true);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setIpIntelligenceBaseUrl(base + "/first/");
        var client = new WorkerIpIntelligenceClient(properties, new ObjectMapper());
        try {
            assertTrue(client.lookup("203.0.113.10").known());
            properties.setIpIntelligenceBaseUrl(base + "/second/");
            assertTrue(client.lookup("203.0.113.10").known());
            assertEquals(2, requests.get());
        } finally { client.close(); }
    }

    @Test
    void prefetchReturnsWhileProviderIsPendingAndSharesTheForegroundLookup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"risk\":{\"is_mobile\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var client = client();
        try (var pool = Executors.newSingleThreadExecutor()) {
            client.prefetch("203.0.113.10");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) client.prefetch("203.0.113.10");
            var foreground = pool.submit(() -> client.lookup("203.0.113.10"));
            release.countDown();
            assertTrue(foreground.get(3, TimeUnit.SECONDS).known());
            assertEquals(1, requests.get());
        } finally { release.countDown(); client.close(); }
    }

    private AtomicInteger successfulProvider() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"risk\":{\"is_mobile\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        return requests;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesMobileRiskSignalsAndCachesSuccessfulLookup() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = """
                    {
                      "isp": {"org": "T2 Mobile LLC"},
                      "risk": {
                        "is_mobile": true,
                        "is_vpn": false,
                        "is_tor": false,
                        "is_proxy": false,
                        "is_datacenter": false
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        WorkerIpIntelligenceClient client = client();
        WorkerIpIntelligenceClient.IpIntelligence first = client.lookup("203.0.113.10");
        WorkerIpIntelligenceClient.IpIntelligence second = client.lookup("203.0.113.10");

        assertTrue(first.known());
        assertTrue(first.mobile());
        assertFalse(first.risky());
        assertEquals("T2 Mobile LLC", first.organization());
        assertEquals(first, second);
        assertEquals(1, requests.get());
    }

    private WorkerIpIntelligenceClient client() {
        WorkerCellularAccessProperties properties = new WorkerCellularAccessProperties();
        properties.setIpIntelligenceEnabled(true);
        properties.setIpIntelligenceBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setIpIntelligenceTimeout(Duration.ofSeconds(2));
        properties.setIpIntelligenceCacheTtl(Duration.ofHours(1));
        return new WorkerIpIntelligenceClient(properties, new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentMissesShareProviderCallIncludingFailures(boolean success) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            providerEntered.countDown();
            try { releaseProvider.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] body = (success ? "{\"risk\":{\"is_mobile\":true,\"is_vpn\":true}}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(success ? 200 : 503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        WorkerIpIntelligenceClient client = client();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Future<WorkerIpIntelligenceClient.IpIntelligence>>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return client.lookup("203.0.113.10");
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS));
            releaseProvider.countDown();
            for (var call : calls) {
                var result = call.get(5, TimeUnit.SECONDS);
                assertEquals(success, result.known());
                assertEquals(success, result.risky());
            }
            assertEquals(success, client.lookup("203.0.113.10").known());
            assertEquals(1, requests.get());
            assertEquals(success, client.lookup("203.0.113.11").known());
            assertEquals(2, requests.get());
        } finally {
            start.countDown();
            releaseProvider.countDown();
        }
    }
}
