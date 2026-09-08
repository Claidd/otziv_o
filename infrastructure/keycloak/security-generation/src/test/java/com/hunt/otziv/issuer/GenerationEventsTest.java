package com.hunt.otziv.issuer;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransactionManager;

class GenerationEventsTest {
    AdminEvent event(String path, ResourceType type) {
        var event = new AdminEvent(); event.setResourcePath(path);
        event.setRealmId("realm"); event.setResourceType(type); return event;
    }
    @Test void listenerSelectionCannotDisableTheJournal() {
        assertTrue(new GenerationEvents().isGlobal());
        assertTrue(GenerationEvents.realmWide(event(null,ResourceType.REALM)));
        assertTrue(GenerationEvents.realmWide(event("events/config",ResourceType.REALM)));
    }
    @Test void exactSessionDeletionCannotFenceASeparateFreshLogin() {
        var exact = event("sessions/old-session",ResourceType.USER_SESSION);
        assertNull(GenerationEvents.affectedSubject(exact));
        assertFalse(GenerationEvents.realmWide(exact));
        assertEquals("subject",GenerationEvents.affectedSubject(event("users/subject/logout",ResourceType.USER)));
    }
    @Test void failedAdminOperationsDoNotAdvanceTheGeneration() {
        var failed = event("users/subject",ResourceType.USER); failed.setError("denied");
        assertNull(GenerationEvents.affectedSubject(failed));
        assertFalse(GenerationEvents.realmWide(failed));
    }
    @Test void listenerFailurePoisonsTheEnclosingTransactionThatKeycloakWouldOtherwiseCatch() {
        var rollback = new AtomicBoolean();
        var transaction = (KeycloakTransactionManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{KeycloakTransactionManager.class},(proxy,method,args) -> {
                    if (method.getName().equals("setRollbackOnly")) rollback.set(true);
                    return null;
                });
        var session = (KeycloakSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{KeycloakSession.class},(proxy,method,args) ->
                    method.getName().equals("getTransactionManager") ? transaction : null);
        // Missing JPA provider fails before any increment, just as an unavailable journal.
        assertThrows(RuntimeException.class,() -> new GenerationEvents().create(session)
                .onEvent(event("users/subject",ResourceType.USER),false));
        assertTrue(rollback.get());
    }
}
