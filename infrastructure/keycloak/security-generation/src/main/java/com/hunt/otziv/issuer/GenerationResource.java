package com.hunt.otziv.issuer;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Existing realm-management service-account privilege, no public user inventory. */
public final class GenerationResource implements RealmResourceProviderFactory {
    public static final String ID = "otziv-security";
    public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            public Object getResource() { return new Endpoint(session); }
            public void close() {}
        };
    }
    public String getId() { return ID; }
    public void init(Config.Scope config) {}
    public void postInit(KeycloakSessionFactory factory) {}
    public void close() {}

    public static final class Endpoint {
        private final KeycloakSession session;
        public Endpoint(KeycloakSession session) { this.session = session; }
        @GET @Path("generation/{subject}") @Produces(MediaType.APPLICATION_JSON)
        public Map<String,Object> generation(@PathParam("subject") String subject) {
            var auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
            if (auth == null) throw new WebApplicationException(401);
            var permissions = auth.getToken().getResourceAccess("realm-management");
            if (auth.getUser().getServiceAccountClientLink() == null || permissions == null
                    || !(permissions.isUserInRole("view-users") || permissions.isUserInRole("manage-users")))
                throw new WebApplicationException(403);
            var realm = session.getContext().getRealm();
            if (!"true".equals(realm.getAttribute("otziv.security.generation.enabled")))
                throw new WebApplicationException("Security generation authority is not activated",503);
            try { GenerationStore.requireSubject(subject); }
            catch (IllegalArgumentException invalid) { throw new WebApplicationException(400); }
            if (session.users().getUserById(realm,subject) == null) throw new WebApplicationException(404);
            return Map.of("protocolVersion",1,"subject",subject,"generation",new GenerationStore(session).current(realm.getId(),subject));
        }
    }
}
