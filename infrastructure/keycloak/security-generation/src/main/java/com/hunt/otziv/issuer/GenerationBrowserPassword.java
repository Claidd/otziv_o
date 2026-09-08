package com.hunt.otziv.issuer;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

/** Capture before credential verification; never replace the note during refresh. */
public final class GenerationBrowserPassword extends UsernamePasswordFormFactory {
    public static final String ID = "otziv-generation-browser-password";
    @Override public String getId() { return ID; }
    @Override public String getDisplayType() { return "Username Password with immutable security generation"; }
    @Override public Authenticator create(KeycloakSession session) {
        return new UsernamePasswordForm() {
            @Override public boolean validatePassword(AuthenticationFlowContext context, UserModel user,
                    MultivaluedMap<String,String> input, boolean clearUser) {
                String generation = new GenerationStore(context.getSession()).capture(context.getRealm().getId(),user.getId());
                boolean valid = super.validatePassword(context,user,input,clearUser);
                if (valid) context.getAuthenticationSession().setUserSessionNote(GenerationStore.NOTE,generation);
                return valid;
            }
        };
    }
}
