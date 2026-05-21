package com.hunt.otziv.whatsapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.time.Duration;
import java.util.Locale;

@Configuration
public class RestTemplateConfig {
    @Bean
    @Primary
    public RestTemplate restTemplate(
            @Value("${app.http-client.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.http-client.read-timeout:30s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return new RestTemplate(requestFactory);
    }

    @Bean(name = "whatsAppRestTemplate")
    public RestTemplate whatsAppRestTemplate(
            @Value("${app.http-client.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.http-client.read-timeout:30s}") Duration readTimeout,
            @Value("${whatsapp.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${whatsapp.proxy.host:}") String proxyHost,
            @Value("${whatsapp.proxy.port:8888}") int proxyPort
    ) {
        SimpleClientHttpRequestFactory requestFactory;
        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank()) {
            requestFactory = new SelectiveProxyClientHttpRequestFactory(proxyHost, proxyPort);
        } else {
            requestFactory = new SimpleClientHttpRequestFactory();
        }
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return new RestTemplate(requestFactory);
    }

    @Bean(name = "maxBotRestTemplate")
    public RestTemplate maxBotRestTemplate(
            @Value("${app.http-client.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.http-client.read-timeout:30s}") Duration readTimeout,
            @Value("${max.bot.proxy.enabled:false}") boolean proxyEnabled,
            @Value("${max.bot.proxy.host:}") String proxyHost,
            @Value("${max.bot.proxy.port:8888}") int proxyPort
    ) {
        SimpleClientHttpRequestFactory requestFactory;
        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank()) {
            requestFactory = new SelectiveProxyClientHttpRequestFactory(proxyHost, proxyPort);
        } else {
            requestFactory = new SimpleClientHttpRequestFactory();
        }
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return new RestTemplate(requestFactory);
    }

    private static class SelectiveProxyClientHttpRequestFactory extends SimpleClientHttpRequestFactory {
        private final Proxy proxy;

        private SelectiveProxyClientHttpRequestFactory(String proxyHost, int proxyPort) {
            this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        }

        @Override
        protected HttpURLConnection openConnection(URL url, Proxy ignoredProxy) throws IOException {
            if (shouldBypassProxy(url)) {
                return (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            }
            return (HttpURLConnection) url.openConnection(proxy);
        }

        private boolean shouldBypassProxy(URL url) {
            String host = url.getHost();
            if (host == null || host.isBlank()) {
                return true;
            }

            String normalized = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(normalized) || normalized.endsWith(".localhost") || normalized.equals("host.docker.internal")) {
                return true;
            }
            if (!normalized.contains(".")) {
                return true;
            }
            return isPrivateIpv4(normalized);
        }

        private boolean isPrivateIpv4(String host) {
            String[] parts = host.split("\\.");
            if (parts.length != 4) {
                return false;
            }

            int[] octets = new int[4];
            for (int i = 0; i < parts.length; i++) {
                try {
                    octets[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            return octets[0] == 10
                    || octets[0] == 127
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168);
        }
    }
}

