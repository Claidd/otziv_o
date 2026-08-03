package com.hunt.otziv.config.email.config;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MailConfig.class)
            .withPropertyValues(
                    "spring.mail.host=smtp.example.test",
                    "spring.mail.port=587",
                    "spring.mail.username=mailer@example.test",
                    String.join(".", "spring", "mail", "password") + "="
            );

    @Test
    void appliesFiniteDefaultTimeoutsAndConfiguredSecurityFlags() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.properties.mail.smtp.auth=false",
                        "spring.mail.properties.mail.smtp.starttls.enable=false",
                        "spring.mail.properties.mail.smtp.starttls.required=true"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    Properties properties = sender.getJavaMailProperties();

                    assertEquals("false", properties.getProperty("mail.smtp.auth"));
                    assertEquals("false", properties.getProperty("mail.smtp.starttls.enable"));
                    assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
                    assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
                    assertEquals("10000", properties.getProperty("mail.smtp.connectiontimeout"));
                    assertEquals("60000", properties.getProperty("mail.smtp.timeout"));
                    assertEquals("60000", properties.getProperty("mail.smtp.writetimeout"));
                });
    }

    @Test
    void appliesExplicitPositiveTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.properties.mail.smtp.connectiontimeout=11000",
                        "spring.mail.properties.mail.smtp.timeout=12000",
                        "spring.mail.properties.mail.smtp.writetimeout=13000",
                        "spring.mail.properties.mail.smtp.ssl.checkserveridentity=false"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    Properties properties = context.getBean(JavaMailSenderImpl.class).getJavaMailProperties();

                    assertEquals("11000", properties.getProperty("mail.smtp.connectiontimeout"));
                    assertEquals("12000", properties.getProperty("mail.smtp.timeout"));
                    assertEquals("13000", properties.getProperty("mail.smtp.writetimeout"));
                    assertEquals("false", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
                });
    }

    @Test
    void rejectsTimeoutThatWouldDisableJavaMailDeadline() {
        contextRunner
                .withPropertyValues("spring.mail.properties.mail.smtp.timeout=0")
                .run(context -> {
                    Throwable startupFailure = context.getStartupFailure();

                    assertNotNull(startupFailure);
                    assertTrue(rootCauseMessage(startupFailure).contains(
                            "mail.smtp.timeout must be a positive finite timeout in milliseconds"
                    ));
                });
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
