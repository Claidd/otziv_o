package com.hunt.otziv.monitoring;

import com.hunt.otziv.l_lead.service.LeadCommandMetrics;
import com.hunt.otziv.performers.service.PerformerNotificationMetrics;
import com.hunt.otziv.u_users.service.UserSessionRevocationMetrics;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

/** Full application/filter chains, actual servlet HTTP and actual Flyway/MySQL queue projections. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "otziv.monitoring.enabled=true","otziv.monitoring.shared-secret=fixture-monitoring-application-0000000000",
    "telegram.bot.sending-enabled=false","telegram.bot.registration-enabled=false",
    "max.bot.webhook-auto-register-enabled=false","max.bot.long-polling-enabled=false"})
@ActiveProfiles("test") @Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MonitoringApplicationMySqlIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql@sha256:8b879a3959bc59adcb7281a41950d39cf8c9b3fb23b87b9b62318ce884a7c383")
        .withDatabaseName("otziv").withUsername("root").withPassword(UUID.randomUUID().toString()).withCommand("--restrict-fk-on-non-standard-key=OFF");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);registry.add("spring.datasource.driver-class-name",MYSQL::getDriverClassName);
    }
    @Value("${local.server.port}") int port;
    @Autowired MonitoringRuntimeService service;
    @Autowired LeadCommandMetrics lead;
    @Autowired PerformerNotificationMetrics performers;
    @Autowired UserSessionRevocationMetrics sessions;
    @Autowired RuntimeRequestWindow window;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    HttpResponse<String> request(String method,String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(10));
        if(token!=null)builder.header("X-Otziv-Monitor-Token",token);
        return http.send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realHttpAuthenticatesOnlyExactReadRouteAndSerializesCurrentMysqlProjections() throws Exception {
        lead.sample();performers.refresh();sessions.refresh();service.sampleProjections();
        assertThat(service.snapshot().queues()).allMatch(q->q.state().equals("AVAILABLE"))
            .extracting(q->q.name()).containsExactlyInAnyOrder("integration_outbox", "workload", "lead",
                "performer", "session_revocation", "common_invoice", "manager_client", "whatsapp_reply");
        String path=MonitoringSecurityConfiguration.PATH,token="fixture-monitoring-application-0000000000";
        assertThat(request("GET",path,null).statusCode()).isEqualTo(401);
        assertThat(request("GET",path,"wrong-fixture").statusCode()).isEqualTo(401);
        var success=request("GET",path,token);assertThat(success.statusCode()).isEqualTo(200);
        assertThat(success.body()).contains("otziv-runtime-observed-v1","NO_TRAFFIC","\"dispatchEnabled\":false").doesNotContain(token);
        assertThat(request("POST",path,token).statusCode()).isEqualTo(405);
        assertThat(request("GET","/api/internal/not-monitoring",token).statusCode()).isNotEqualTo(200);
        assertThat(window.snapshot().samples()).isGreaterThan(0);
    }
}
