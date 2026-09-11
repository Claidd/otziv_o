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
