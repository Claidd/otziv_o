package com.hunt.otziv.text_generator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.client.OpenAiApi;
import com.theokanning.openai.service.OpenAiService;
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
import java.util.concurrent.TimeUnit;

@Configuration
public class OpenAIConfig {

    @Value("${openai.api-key}")
    private String apiKey;

    @Bean
    public OpenAiService openAiService() {
        // Настраиваем прокси
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("vpn-proxy", 8888));
        Duration timeout = Duration.ofSeconds(40);

        // Кастомный клиент с прокси и авторизацией
        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .addInterceptor(new MyAuthInterceptor(apiKey))
                .build();

        // Retrofit + API
        ObjectMapper mapper = OpenAiService.defaultObjectMapper();
        Retrofit retrofit = OpenAiService.defaultRetrofit(client, mapper);
        OpenAiApi api = retrofit.create(OpenAiApi.class);

        return new OpenAiService(api, client.dispatcher().executorService());
    }

    /**
     * Кастомный Interceptor для авторизации
     */
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


