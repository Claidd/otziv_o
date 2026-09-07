package com.hunt.otziv.issuer;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public final class GenerationEntities implements JpaEntityProviderFactory {
    public static final String ID = "otziv-security-generation";
    public JpaEntityProvider create(KeycloakSession session) {
        return new JpaEntityProvider() {
            public List<Class<?>> getEntities() { return List.of(SecurityGenerationEntity.class); }
            public String getChangelogLocation() { return "META-INF/otziv-security-generation.xml"; }
            public String getFactoryId() { return ID; }
            public void close() {}
        };
    }
    public String getId() { return ID; }
    public void init(Config.Scope config) {}
    public void postInit(KeycloakSessionFactory factory) {}
    public void close() {}
}
