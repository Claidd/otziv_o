package com.hunt.otziv.payments;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class TbankTokenSigner {

    public String sign(Map<String, ?> values, String password) {
        TreeMap<String, String> flatValues = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || value == null || "Token".equals(key) || isNested(value)) {
                return;
            }
            flatValues.put(key, Objects.toString(value, ""));
        });
        flatValues.put("Password", password == null ? "" : password);

        StringBuilder payload = new StringBuilder();
        flatValues.values().forEach(payload::append);
        return sha256(payload.toString());
    }

    public boolean matches(Map<String, ?> values, String password, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(values, password).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isNested(Object value) {
        return value instanceof Map<?, ?> || value instanceof Collection<?>;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
