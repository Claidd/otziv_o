package com.hunt.otziv.paper_billing;

import java.net.URI;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestTemplate;

/** Explicit local profile routes bank traffic to the isolated fixture, including ordinary checkout.
 * Only an empty token or the fixed non-secret local fixture marker may be present. */
@Configuration @Profile("paper-invoice-fixture")
public class PaperBillingFixtureConfiguration {
    @Bean
    Object paperBillingFixtureKeyRouting(@Qualifier("tochkaRestTemplate") RestTemplate rest,PaperBillingBankClient bank) {
        if (!bank.fixture()) throw new IllegalStateException("Local document fixture is not configured");
        rest.getInterceptors().add((request,body,execution) -> {
            if ("enter.tochka.com".equals(request.getURI().getHost())) {
                String path=request.getURI().getPath();
                String local=path.equals("/doc/openapi/static/keys/public")?"/keys":path.replaceFirst("^/(uapi|sandbox/v2)","");
                return execution.execute(new HttpRequestWrapper(request) {
                    @Override public URI getURI() { return URI.create("http://paper-invoice-fixture:8099"+local+(request.getURI().getRawQuery()==null?"":"?"+request.getURI().getRawQuery())); }
                    @Override public org.springframework.http.HttpHeaders getHeaders() {
                        var headers=new org.springframework.http.HttpHeaders();headers.putAll(request.getHeaders());headers.setBearerAuth("local-fixture");return headers;
                    }
                },body);
            }
            return execution.execute(request,body);
        });
        return new Object();
    }
}
