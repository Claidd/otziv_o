# Keycloak

## Login theme

The `otziv` login theme lives in `themes/otziv/login` and is mounted into the Keycloak container by `compose.yaml`.

Fresh realms imported from `realm-config.json` use this theme automatically through `loginTheme: otziv`.

For an already existing local Keycloak database volume, apply the theme once:

```powershell
.\infrastructure\keycloak\apply-theme.ps1
```

or on Linux:

```sh
sh infrastructure/keycloak/apply-theme.sh
```

Local Angular development still talks to Keycloak directly on `http://localhost:8180`. For production behind nginx, set:

```env
KC_HTTP_RELATIVE_PATH=/keycloak
OTZIV_APP_BASE_URL=https://your-domain.example
KEYCLOAK_ISSUER_URI=https://your-domain.example/keycloak/realms/otziv
KEYCLOAK_JWK_SET_URI=http://keycloak:8080/keycloak/realms/otziv/protocol/openid-connect/certs
KEYCLOAK_ADMIN_SERVER_URL=http://keycloak:8080/keycloak
```

and expose Keycloak through nginx at `/keycloak/`.
