package com.hunt.otziv.p_products.worker_access.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.p_products.worker_access.config.WorkerCellularAccessProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
}
