package com.hunt.otziv.security.credentials;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CredentialEncryptionProperties.class)
public class CredentialEncryptionConfiguration {

    @Bean
    CredentialCipher credentialCipher(CredentialEncryptionProperties properties) {
        return new CredentialCipher(properties);
    }
}
