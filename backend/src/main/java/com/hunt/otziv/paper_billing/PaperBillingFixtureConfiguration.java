package com.hunt.otziv.paper_billing;

import java.net.URI;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestTemplate;

/** Explicit local profile routes only the bank's public-key lookup to the isolated fixture.
 * The fixture configuration refuses to start when a real bank token is present. */
@Configuration @Profile("paper-invoice-fixture")
public class PaperBillingFixtureConfiguration {
    @Bean
    Object paperBillingFixtureKeyRouting(@Qualifier("tochkaRestTemplate") RestTemplate rest,PaperBillingBankClient bank) {
        if (!bank.fixture()) throw new IllegalStateException("Local document fixture is not configured");
        rest.getInterceptors().add((request,body,execution) -> {
            if (request.getURI().toString().equals("https://enter.tochka.com/doc/openapi/static/keys/public")) {
                return execution.execute(new HttpRequestWrapper(request) {
                    @Override public URI getURI() { return URI.create("http://paper-invoice-fixture:8099/keys"); }
                },body);
            }
            return execution.execute(request,body);
        });
        return new Object();
    }
}
