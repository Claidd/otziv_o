package com.hunt.otziv.external_review_checks.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ExternalReviewWorkerHttpConfig {

    static final String INTERNAL_AUTH_HEADER = "X-Otziv-Internal-Token";

    @Bean
    @Qualifier("externalReviewWorkerRestTemplate")
    public RestTemplate externalReviewWorkerRestTemplate(ExternalReviewCheckProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                ExternalReviewTimeoutPolicy.workerConnectTimeout(properties)
        );
        requestFactory.setReadTimeout(
                ExternalReviewTimeoutPolicy.workerReadTimeout(properties)
        );
        configureProxy(requestFactory, properties.getProxy());

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        long responseLimit = boundedResponseLimit(properties.getWorkerMaxResponseBytes());
        restTemplate.getInterceptors().add((request, body, execution) -> {
            addWorkerAuthorization(request.getHeaders(), properties.getWorkerSharedSecret());
            addProxyAuthorization(request.getHeaders(), properties.getProxy());
            ClientHttpResponse response = execution.execute(request, body);
            return new SizeLimitedClientHttpResponse(response, responseLimit);
        });
        return restTemplate;
    }

    private void addWorkerAuthorization(HttpHeaders headers, String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            return;
        }
        headers.set(INTERNAL_AUTH_HEADER, configuredSecret);
    }

    private void configureProxy(
            SimpleClientHttpRequestFactory requestFactory,
            ExternalReviewCheckProperties.Proxy properties
    ) {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        String host = properties.getHost() == null ? "" : properties.getHost().trim();
        int port = properties.getPort();
        if (host.isBlank() || port < 1 || port > 65_535) {
            throw new IllegalStateException("external-review-check.proxy host/port are invalid");
        }
        requestFactory.setProxy(new java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(host, port)
        ));
    }

    private void addProxyAuthorization(
            HttpHeaders headers,
            ExternalReviewCheckProperties.Proxy properties
    ) {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        String username = properties.getUsername() == null ? "" : properties.getUsername();
        if (username.isBlank()) {
            return;
        }
        String password = properties.getPassword() == null ? "" : properties.getPassword();
        String credentials = username + ":" + password;
        headers.set(
                "Proxy-Authorization",
                "Basic " + Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    private long boundedResponseLimit(long configuredLimit) {
        return Math.max(1_024L, Math.min(configuredLimit, 64L * 1024L * 1024L));
    }

    private static final class SizeLimitedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final long limit;
        private InputStream body;

        private SizeLimitedClientHttpResponse(ClientHttpResponse delegate, long limit) throws IOException {
            this.delegate = delegate;
            this.limit = limit;
            long contentLength = delegate.getHeaders().getContentLength();
            if (contentLength > limit) {
                delegate.close();
                throw new IOException("External review worker response exceeds configured limit");
            }
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = new SizeLimitedInputStream(delegate.getBody(), limit);
            }
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        private SizeLimitedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            if (skipped > 0) {
                record(skipped);
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void record(long count) throws IOException {
            consumed += count;
            if (consumed > limit) {
                throw new IOException("External review worker response exceeds configured limit");
            }
        }
    }
}
