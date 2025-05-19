package com.hunt.otziv.text_generator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.client.OpenAiApi;
import com.theokanning.openai.service.OpenAiService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;

import java.io.IOException;
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
    public OpenAiService openAiService() {
        Proxy proxy = null;

        if (proxyHost != null && !proxyHost.isBlank()) {
            log.info("📦 Используем прокси {}:{}", proxyHost, proxyPort);
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
        } else {
            log.info("🚫 Прокси не задан — используем прямое соединение");
        }

        Duration timeout = Duration.ofSeconds(40);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .addInterceptor(new MyAuthInterceptor(apiKey));

        if (proxy != null) {
            builder.proxy(proxy);
        }

        OkHttpClient client = builder.build();
        Retrofit retrofit = OpenAiService.defaultRetrofit(client, OpenAiService.defaultObjectMapper());
        OpenAiApi api = retrofit.create(OpenAiApi.class);

        return new OpenAiService(api, client.dispatcher().executorService());
    }

    public static class MyAuthInterceptor implements Interceptor {
        private final String apiKey;

        public MyAuthInterceptor(String apiKey) {
            this.apiKey = apiKey;
        }

        @NotNull
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build();
            return chain.proceed(request);
        }
    }
}





