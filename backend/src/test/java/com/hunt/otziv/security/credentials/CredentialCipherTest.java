package com.hunt.otziv.security.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    @Test
    void encryptsWithRandomizedAuthenticatedEnvelopeAndDecrypts() {
        CredentialCipher cipher = cipher("primary", key('A'), null, true);

        String first = cipher.encrypt("third-party-password");
        String second = cipher.encrypt("third-party-password");

        assertThat(first).startsWith("enc:v1:primary:");
        assertThat(second).startsWith("enc:v1:primary:");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("third-party-password");
        assertThat(cipher.decrypt(second)).isEqualTo("third-party-password");
    }

    @Test
    void readsLegacyPlaintextAndLeavesItPlainWhenEncryptionIsOptionalAndUnconfigured() {
        CredentialEncryptionProperties properties = new CredentialEncryptionProperties();
        CredentialCipher cipher = new CredentialCipher(properties);

        assertThat(cipher.isEnabled()).isFalse();
        assertThat(cipher.decrypt("legacy-password")).isEqualTo("legacy-password");
        assertThat(cipher.encrypt("legacy-password")).isEqualTo("legacy-password");
    }

    @Test
    void rotationReadsPreviousKeyAndReencryptsWithActiveKey() {
        CredentialCipher oldCipher = cipher("old-2026", key('O'), null, true);
        String oldEnvelope = oldCipher.encrypt("rotated-secret");
        CredentialCipher rotatedCipher = cipher(
                "current-2026",
                key('N'),
                "old-2026=" + key('O'),
                true
        );

        assertThat(rotatedCipher.decrypt(oldEnvelope)).isEqualTo("rotated-secret");
        assertThat(rotatedCipher.needsReencryption(oldEnvelope)).isTrue();

        String currentEnvelope = rotatedCipher.encrypt(oldEnvelope);
        assertThat(currentEnvelope).startsWith("enc:v1:current-2026:");
        assertThat(rotatedCipher.decrypt(currentEnvelope)).isEqualTo("rotated-secret");
        assertThat(rotatedCipher.needsReencryption(currentEnvelope)).isFalse();
    }

    @Test
    void rejectsTamperedCiphertext() {
        CredentialCipher cipher = cipher("primary", key('A'), null, true);
        String envelope = cipher.encrypt("third-party-password");
        int payloadStart = envelope.lastIndexOf(':') + 1;
        char replacement = envelope.charAt(payloadStart) == 'A' ? 'B' : 'A';
        String tampered = envelope.substring(0, payloadStart)
                + replacement
                + envelope.substring(payloadStart + 1);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void rejectsUnknownKeyInsteadOfReturningCiphertextOrGarbage() {
        String envelope = cipher("old", key('O'), null, true).encrypt("password");
        CredentialCipher current = cipher("current", key('N'), null, true);

        assertThatThrownBy(() -> current.decrypt(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key is unavailable")
                .hasMessageContaining("old");
    }

    @Test
    void failsFastWhenEncryptionIsRequiredWithoutAKey() {
        CredentialEncryptionProperties properties = new CredentialEncryptionProperties();
        properties.setRequired(true);

        assertThatThrownBy(() -> new CredentialCipher(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-key-base64 is required");
    }

    @Test
    void rejectsKeysThatAreNotExactly256Bits() {
        assertThatThrownBy(() -> cipher(
                "primary",
                Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8)),
                null,
                true
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }

    private static CredentialCipher cipher(
            String activeKeyId,
            String activeKey,
            String previousKeys,
            boolean required
    ) {
        CredentialEncryptionProperties properties = new CredentialEncryptionProperties();
        properties.setRequired(required);
        properties.setActiveKeyId(activeKeyId);
        properties.setActiveKeyBase64(activeKey);
        properties.setPreviousKeys(previousKeys);
        return new CredentialCipher(properties);
    }

    private static String key(char value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).repeat(32).getBytes(StandardCharsets.UTF_8));
    }
}
