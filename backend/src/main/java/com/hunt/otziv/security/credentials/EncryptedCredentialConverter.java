package com.hunt.otziv.security.credentials;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

@Component
@Converter(autoApply = false)
public class EncryptedCredentialConverter implements AttributeConverter<String, String> {

    private final CredentialCipher credentialCipher;

    public EncryptedCredentialConverter(CredentialCipher credentialCipher) {
        this.credentialCipher = credentialCipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return credentialCipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return credentialCipher.decrypt(dbData);
    }
}
