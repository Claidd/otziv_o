package com.hunt.otziv.config.email.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {
    @Value("${spring.mail.host}")
    private String host;
    @Value("${spring.mail.port}")
    private int port;
    @Value("${spring.mail.username}")
    private String username;
    @Value("${spring.mail.password}")
    private String password;
    @Value("${spring.mail.properties.mail.smtp.auth:true}")
    private boolean smtpAuth;
    @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}")
    private boolean startTlsEnabled;
    @Value("${spring.mail.properties.mail.smtp.starttls.required:false}")
    private boolean startTlsRequired;
    @Value("${spring.mail.properties.mail.smtp.ssl.checkserveridentity:true}")
    private boolean checkServerIdentity;
    @Value("${spring.mail.properties.mail.smtp.connectiontimeout:10000}")
    private int connectionTimeoutMillis;
    @Value("${spring.mail.properties.mail.smtp.timeout:60000}")
    private int readTimeoutMillis;
    @Value("${spring.mail.properties.mail.smtp.writetimeout:60000}")
    private int writeTimeoutMillis;

    @Bean
    public JavaMailSender mailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);

        /*Настройки для отправки*/
        Properties props = mailSender.getJavaMailProperties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.auth", Boolean.toString(smtpAuth));
        props.setProperty("mail.smtp.starttls.enable", Boolean.toString(startTlsEnabled));
        props.setProperty("mail.smtp.starttls.required", Boolean.toString(startTlsRequired));
        props.setProperty("mail.smtp.ssl.checkserveridentity", Boolean.toString(checkServerIdentity));
        props.setProperty(
                "mail.smtp.connectiontimeout",
                Integer.toString(requirePositiveTimeout("mail.smtp.connectiontimeout", connectionTimeoutMillis))
        );
        props.setProperty(
                "mail.smtp.timeout",
                Integer.toString(requirePositiveTimeout("mail.smtp.timeout", readTimeoutMillis))
        );
        props.setProperty(
                "mail.smtp.writetimeout",
                Integer.toString(requirePositiveTimeout("mail.smtp.writetimeout", writeTimeoutMillis))
        );
//        props.put("mail.debug", "true");

        return mailSender;
    }

    private static int requirePositiveTimeout(String propertyName, int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalStateException(propertyName + " must be a positive finite timeout in milliseconds");
        }
        return timeoutMillis;
    }
}
