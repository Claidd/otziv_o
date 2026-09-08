package com.hunt.otziv.issuer;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;

/** PostgreSQL locks remain owned by the surrounding Keycloak transaction. */
final class GenerationStore {
    static final String NOTE = "otziv.security.generation.v1";
    static final String CLAIM = "otziv_session_generation";
    private static final String GLOBAL = "*";
    private final KeycloakSession session;
    private final EntityManager em;

    GenerationStore(KeycloakSession session) {
        this.session = session;
        this.em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    String capture(String realm, String subject) {
        requireSubject(subject);
        // SHARE permits different users to authenticate concurrently. Global role/group
        // mutations take UPDATE on this row; every path uses realm then user lock order.
        long global = lock(realm, GLOBAL, false);
        long user = lock(realm, subject, true);
        return "v1:" + global + ":" + user;
    }

    String current(String realm, String subject) {
        // A read also owns the generation locks until the authenticated response is
        // serialized. No successful observation is cached by the backend.
        return capture(realm, subject);
    }

    void userChanged(String realm, String subject) {
        requireSubject(subject);
        lock(realm, GLOBAL, false);
        lock(realm, subject, true);
        increment(realm, subject);
    }

    void realmChanged(String realm) {
        lock(realm, GLOBAL, true);
        increment(realm, GLOBAL);
    }

    private long lock(String realm, String subject, boolean exclusive) {
        String key = key(realm, subject);
        em.createNativeQuery("INSERT INTO OTZIV_SEC_GENERATION (ID,REALM_ID,SUBJECT_ID,GENERATION) "
                + "VALUES (?1,?2,?3,0) ON CONFLICT (ID) DO NOTHING")
                .setParameter(1,key).setParameter(2,realm).setParameter(3,subject).executeUpdate();
        Object value = em.createNativeQuery("SELECT GENERATION FROM OTZIV_SEC_GENERATION WHERE ID=?1 FOR "
                + (exclusive ? "UPDATE" : "SHARE")).setParameter(1,key).getSingleResult();
        long generation = ((Number)value).longValue();
        if (generation < 0) throw new IllegalStateException("Invalid issuer generation");
        return generation;
    }

    private void increment(String realm, String subject) {
        int changed = em.createNativeQuery("UPDATE OTZIV_SEC_GENERATION SET GENERATION=GENERATION+1 "
                + "WHERE ID=?1 AND GENERATION < 9223372036854775807")
                .setParameter(1,key(realm,subject)).executeUpdate();
        if (changed != 1) {
            session.getTransactionManager().setRollbackOnly();
            throw new IllegalStateException("Issuer generation could not advance");
        }
    }

    static void requireSubject(String subject) {
        if (subject == null || !subject.matches("[A-Za-z0-9._:-]{1,255}"))
            throw new IllegalArgumentException("Invalid issuer subject");
    }

    private static String key(String realm, String subject) {
        if (realm == null || realm.isBlank() || realm.length() > 255) throw new IllegalArgumentException("Invalid issuer realm");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((realm + "\u0000" + subject).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
