package com.hunt.otziv.u_users.keycloak;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "keycloak.admin")
public class KeycloakAdminProperties {
    private String serverUrl;
    private String realm;
    private String clientId;
    private String clientSecret;
}
