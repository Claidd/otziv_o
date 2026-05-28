package com.hunt.otziv.mobile_push.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MobilePushFirebaseProperties.class)
public class MobilePushFirebaseConfig {

    private final MobilePushFirebaseProperties properties;

    @Bean
    @ConditionalOnProperty(prefix = "otziv.mobile.push.firebase", name = "enabled", havingValue = "true")
    public FirebaseMessaging firebaseMessaging() throws IOException {
        FirebaseApp app = FirebaseApp.getApps().stream()
                .filter(candidate -> candidate.getName().equals(properties.getAppName()))
                .findFirst()
                .orElseGet(this::initializeApp);
        return FirebaseMessaging.getInstance(app);
    }

    private FirebaseApp initializeApp() {
        try {
            FirebaseOptions.Builder builder = FirebaseOptions.builder()
                    .setCredentials(credentials());

            if (StringUtils.hasText(properties.getProjectId())) {
                builder.setProjectId(properties.getProjectId().trim());
            }

            return FirebaseApp.initializeApp(builder.build(), properties.getAppName());
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось инициализировать Firebase для мобильных push-уведомлений", exception);
        }
    }

    private GoogleCredentials credentials() throws IOException {
        if (StringUtils.hasText(properties.getServiceAccountJson())) {
            byte[] json = properties.getServiceAccountJson().getBytes(StandardCharsets.UTF_8);
            try (InputStream inputStream = new ByteArrayInputStream(json)) {
                return GoogleCredentials.fromStream(inputStream);
            }
        }

        if (StringUtils.hasText(properties.getServiceAccountPath())) {
            try (InputStream inputStream = Files.newInputStream(Path.of(properties.getServiceAccountPath()))) {
                return GoogleCredentials.fromStream(inputStream);
            }
        }

        return GoogleCredentials.getApplicationDefault();
    }
}
