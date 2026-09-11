package com.hunt.otziv.issuer;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.directgrant.ValidatePassword;
import org.keycloak.models.KeycloakSession;

public final class GenerationDirectPassword extends ValidatePassword {
    public static final String ID = "otziv-generation-direct-password";
    @Override public String getId() { return ID; }
    @Override public String getDisplayType() { return "Password with immutable security generation"; }
    @Override public Authenticator create(KeycloakSession session) { return new GenerationDirectPassword(); }
    @Override public void authenticate(AuthenticationFlowContext context) {
        String generation = new GenerationStore(context.getSession()).capture(context.getRealm().getId(),context.getUser().getId());
        context.getAuthenticationSession().setUserSessionNote(GenerationStore.NOTE,generation);
        super.authenticate(context);
    }
}
