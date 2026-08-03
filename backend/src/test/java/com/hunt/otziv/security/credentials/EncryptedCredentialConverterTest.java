package com.hunt.otziv.security.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EncryptedCredentialConverterTest {

    @Test
    void transparentlyEncryptsDatabaseValueAndRestoresEntityValue() {
        CredentialEncryptionProperties properties = new CredentialEncryptionProperties();
        properties.setRequired(true);
        properties.setActiveKeyId("test");
        properties.setActiveKeyBase64(Base64.getEncoder().encodeToString(
                "converter-test-key-material-32!!".getBytes(StandardCharsets.UTF_8)
        ));
        EncryptedCredentialConverter converter = new EncryptedCredentialConverter(new CredentialCipher(properties));

        String databaseValue = converter.convertToDatabaseColumn("password");

        assertThat(databaseValue).startsWith("enc:v1:test:").doesNotContain("password");
        assertThat(converter.convertToEntityAttribute(databaseValue)).isEqualTo("password");
    }
}
