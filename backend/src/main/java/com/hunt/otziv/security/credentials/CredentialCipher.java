package com.hunt.otziv.security.credentials;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CredentialCipher {

    static final String ENVELOPE_PREFIX = "enc:v1:";
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int AES_256_KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom secureRandom;

    public CredentialCipher(CredentialEncryptionProperties properties) {
        this(properties, new SecureRandom());
    }

    CredentialCipher(CredentialEncryptionProperties properties, SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
        String encodedActiveKey = trimToNull(properties.getActiveKeyBase64());
        String configuredPreviousKeys = trimToNull(properties.getPreviousKeys());
        if (encodedActiveKey == null) {
            if (properties.isRequired()) {
                throw new IllegalStateException(
                        "otziv.credential-encryption.active-key-base64 is required when credential encryption is required"
                );
            }
            if (configuredPreviousKeys != null) {
                throw new IllegalStateException(
                        "Credential encryption previous keys cannot be configured without an active key"
                );
            }
            this.activeKeyId = null;
            this.keys = Map.of();
            return;
        }

        String configuredActiveKeyId = requireValidKeyId(properties.getActiveKeyId(), "active key id");
        Map<String, SecretKeySpec> configuredKeys = new LinkedHashMap<>();
        configuredKeys.put(configuredActiveKeyId, decodeKey(encodedActiveKey, configuredActiveKeyId));
        parsePreviousKeys(configuredPreviousKeys, configuredKeys, configuredActiveKeyId);
        this.activeKeyId = configuredActiveKeyId;
        this.keys = Map.copyOf(configuredKeys);
    }

    public boolean isEnabled() {
        return activeKeyId != null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (plaintext.startsWith("enc:")) {
            if (!plaintext.startsWith(ENVELOPE_PREFIX)) {
                throw new IllegalStateException("Unsupported encrypted credential envelope version");
            }
            if (usesActiveKey(plaintext)) {
                decrypt(plaintext);
                return plaintext;
            }
            plaintext = decrypt(plaintext);
        }
        if (!isEnabled()) {
            return plaintext;
        }

        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        String header = ENVELOPE_PREFIX + activeKeyId;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return header + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential encryption failed", exception);
        }
    }

    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            return storedValue;
        }
        if (!storedValue.startsWith("enc:")) {
            return storedValue;
        }
        if (!storedValue.startsWith(ENVELOPE_PREFIX)) {
            throw new IllegalStateException("Unsupported encrypted credential envelope version");
        }

        String[] parts = storedValue.split(":", 4);
        if (parts.length != 4 || parts[2].isBlank() || parts[3].isBlank()) {
            throw new IllegalStateException("Malformed encrypted credential envelope");
        }
        String keyId = requireValidKeyId(parts[2], "envelope key id");
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Credential encryption key is unavailable: " + keyId);
        }

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[3]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Malformed encrypted credential payload", exception);
        }
        if (payload.length <= NONCE_BYTES) {
            throw new IllegalStateException("Malformed encrypted credential payload");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[payload.length - NONCE_BYTES];
        System.arraycopy(payload, 0, nonce, 0, nonce.length);
        System.arraycopy(payload, nonce.length, ciphertext, 0, ciphertext.length);
        String header = ENVELOPE_PREFIX + keyId;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(header.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalStateException("Encrypted credential authentication failed", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Credential decryption failed", exception);
        }
    }

    public boolean needsReencryption(String storedValue) {
        return storedValue != null
                && !storedValue.isEmpty()
                && isEnabled()
                && !usesActiveKey(storedValue);
    }

    String activeEnvelopePrefix() {
        return isEnabled() ? ENVELOPE_PREFIX + activeKeyId + ":" : null;
    }

    private boolean usesActiveKey(String storedValue) {
        return isEnabled() && storedValue.startsWith(activeEnvelopePrefix());
    }

    private static void parsePreviousKeys(
            String configuredPreviousKeys,
            Map<String, SecretKeySpec> configuredKeys,
            String activeKeyId
    ) {
        if (configuredPreviousKeys == null) {
            return;
        }
        for (String entry : configuredPreviousKeys.split(";")) {
            String trimmedEntry = entry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }
            int separator = trimmedEntry.indexOf('=');
            if (separator <= 0 || separator == trimmedEntry.length() - 1) {
                throw new IllegalStateException(
                        "Credential encryption previous keys must use keyId=base64 entries separated by semicolons"
                );
            }
            String keyId = requireValidKeyId(trimmedEntry.substring(0, separator), "previous key id");
            if (activeKeyId.equals(keyId) || configuredKeys.containsKey(keyId)) {
                throw new IllegalStateException("Duplicate credential encryption key id: " + keyId);
            }
            configuredKeys.put(keyId, decodeKey(trimmedEntry.substring(separator + 1), keyId));
        }
    }

    private static SecretKeySpec decodeKey(String encodedKey, String keyId) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Credential encryption key is not valid Base64: " + keyId, exception);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException("Credential encryption key must contain exactly 32 bytes: " + keyId);
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private static String requireValidKeyId(String value, String label) {
        String normalized = trimToNull(value);
        if (normalized == null || !KEY_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "Credential encryption " + label + " must match " + KEY_ID_PATTERN.pattern()
            );
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
