package com.hunt.otziv.text_generator.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;

@Slf4j
@Configuration
public class OpenAIConfig {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${proxy.host:}")
    private String proxyHost;

    @Value("${proxy.port:8888}")
    private int proxyPort;

    @Bean
    public OpenAIClient openAIClient() {
        Proxy proxy = null;

        if (proxyHost != null && !proxyHost.isBlank()) {
            log.info("📦 Используем прокси {}:{}", proxyHost, proxyPort);
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        } else {
            log.info("🚫 Прокси не задан — используем прямое соединение");
        }

        Duration timeout = Duration.ofSeconds(40);

        // The application already retries text generation (AutoTextServiceImpl); SDK retries on top
        // of that multiplied one click into up to nine synchronous, paid gpt-4o calls.
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(0);

        if (proxy != null) {
            builder.proxy(proxy);
        }

        return builder.build();
    }
}





