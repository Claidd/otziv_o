package com.hunt.otziv.issuer;

import java.util.Set;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/** Generation advances in the same PostgreSQL transaction as the security mutation. */
public final class GenerationEvents implements EventListenerProviderFactory {
    public static final String ID = "otziv-security-generation";
    private static final Set<String> USER_EVENTS = Set.of("UPDATE_PASSWORD","RESET_PASSWORD","REMOVE_CREDENTIAL",
            "UPDATE_CREDENTIAL","UPDATE_TOTP","REMOVE_TOTP","DELETE_ACCOUNT");
    private static final Set<String> GLOBAL_RESOURCES = Set.of("roles","roles-by-id","groups","clients","client-scopes","authentication");

    static String affectedSubject(AdminEvent event) {
        if (event.getError() != null || event.getResourcePath() == null) return null;
        String[] path = event.getResourcePath().split("/");
        // Exact-session DELETE is intentionally excluded: late scoped reconciliation
        // must never revoke an unrelated subsequent login for the same user.
        if (path.length >= 2 && "users".equals(path[0])) return path[1];
        return null;
    }

    static boolean realmWide(AdminEvent event) {
        if (event.getError() != null) return false;
        // Realm PUT has no resourcePath. Events configuration also belongs to REALM.
        if (event.getResourceType() == ResourceType.REALM) return true;
        if (event.getResourcePath() == null) return false;
        String path = event.getResourcePath();
        return path.isBlank() || GLOBAL_RESOURCES.contains(path.split("/")[0]);
    }

    public EventListenerProvider create(KeycloakSession session) {
        return new EventListenerProvider() {
            private void atomic(Runnable action) {
                try { action.run(); }
                catch (RuntimeException failure) {
                    // Keycloak logs listener exceptions. Mark the enclosing mutation
                    // rollback-only as well, so a failed fence can never commit silently.
                    session.getTransactionManager().setRollbackOnly();
                    throw failure;
                }
            }
            public void onEvent(Event event) {
                if (event.getError() == null && event.getUserId() != null && USER_EVENTS.contains(event.getType().name()))
                    atomic(() -> new GenerationStore(session).userChanged(event.getRealmId(),event.getUserId()));
            }
            public void onEvent(AdminEvent event, boolean includeRepresentation) {
                String subject = affectedSubject(event);
                if (subject != null) atomic(() -> new GenerationStore(session).userChanged(event.getRealmId(),subject));
                else if (realmWide(event)) atomic(() -> new GenerationStore(session).realmChanged(event.getRealmId()));
            }
            public void close() {}
        };
    }
    public String getId() { return ID; }
    // Journal every mutation even while a realm administrator changes listener
    // selection. A separate realm attribute activates the authority read endpoint.
    @Override public boolean isGlobal() { return true; }
    public void init(Config.Scope config) {}
    public void postInit(KeycloakSessionFactory factory) {}
    public void close() {}
}
