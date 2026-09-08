package com.hunt.otziv.whatsapp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Domain-separated operation identities never contain phone numbers or message text. */
public final class WhatsAppOperationKey {
    private WhatsAppOperationKey() {}
    public static String of(String domain, Object... identity) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(domain.getBytes(StandardCharsets.UTF_8));
            for (Object part : identity) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return "wa-" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
