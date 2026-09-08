package com.hunt.otziv.external_review_checks.service;

import com.hunt.otziv.external_review_checks.config.ExternalReviewCheckProperties;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ExternalReviewWorkerAdmissionProtocolTest {
    static Stream<String> readinessBodies(){return Stream.of("{}","{\"ok\":true}","{\"ok\":\"true\",\"state\":\"ready\"}","{\"ok\":true,\"state\":\"busy\"}","not-json");}
    @ParameterizedTest @MethodSource("readinessBodies")
    void malformedOrPartialReadinessNeverAdmits(String body){
        var transport=new RestTemplate();var server=MockRestServiceServer.bindTo(transport).build();
        var client=client(transport);
        server.expect(requestTo("http://worker.test/ready")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThat(client.isReady()).isFalse();server.verify();
    }
    static Stream<String> ambiguousBodies(){return Stream.of("{}","{\"status\":\"ERROR\",\"code\":\"timeout\"}","{\"status\":\"ERROR\",\"code\":\"worker_busy\"}","not-json"," ".repeat(4097));}
    @ParameterizedTest @MethodSource("ambiguousBodies")
    void markerDoesNotTurnUnknown503IntoKnownUnconsumed(String body){
        var transport=new RestTemplate();var server=MockRestServiceServer.bindTo(transport).build();
        var client=client(transport);
        server.expect(requestTo("http://worker.test/api/external-review-checks/verify"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("X-Otziv-Admission","rejected-v1").body(body));
        assertThatThrownBy(()->client.verify(null)).isInstanceOf(HttpStatusCodeException.class);server.verify();
    }
    private ExternalReviewWorkerClient client(RestTemplate transport){
        var properties=new ExternalReviewCheckProperties();properties.setWorkerBaseUrl("http://worker.test");
        var runtime=mock(ExternalReviewCheckRuntimeSwitch.class);when(runtime.isEnabled()).thenReturn(true);
        return new ExternalReviewWorkerClient(transport,transport,properties,runtime);
    }
}
