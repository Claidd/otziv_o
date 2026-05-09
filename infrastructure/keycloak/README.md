# Keycloak

## Login theme

The `otziv` login theme lives in `themes/otziv/login` and is mounted into the Keycloak container by `compose.yaml`.

Fresh realms imported from `realm-config.json` use this theme automatically through `loginTheme: otziv`.

Default session lifespans in realm configs:

- `accessTokenLifespan`: `600` seconds (10 minutes)
- `ssoSessionIdleTimeout`: `28800` seconds (8 hours)
- `ssoSessionMaxLifespan`: `86400` seconds (24 hours)

For an already existing local Keycloak database volume, apply the theme and session settings once:

```powershell
.\infrastructure\keycloak\apply-theme.ps1
```

or on Linux:

```sh
sh infrastructure/keycloak/apply-theme.sh
```

On a running production Docker Compose stack, apply the same values directly:

```sh
docker compose --env-file .env.prod -f compose.prod.yaml exec keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080/keycloak --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"
docker compose --env-file .env.prod -f compose.prod.yaml exec keycloak /opt/keycloak/bin/kcadm.sh update realms/otziv -s accessTokenLifespan=600 -s ssoSessionIdleTimeout=28800 -s ssoSessionMaxLifespan=86400
docker compose --env-file .env.prod -f compose.prod.yaml exec keycloak /opt/keycloak/bin/kcadm.sh get realms/otziv --fields accessTokenLifespan,ssoSessionIdleTimeout,ssoSessionMaxLifespan
```

Local Angular development still talks to Keycloak directly on `http://localhost:8180`. For production behind nginx, set:

```env
KC_HTTP_RELATIVE_PATH=/keycloak
OTZIV_APP_BASE_URL=https://your-domain.example
KEYCLOAK_ISSUER_URI=https://your-domain.example/keycloak/realms/otziv
KEYCLOAK_JWK_SET_URI=http://keycloak:8080/keycloak/realms/otziv/protocol/openid-connect/certs
KEYCLOAK_ADMIN_SERVER_URL=http://keycloak:8080/keycloak
KEYCLOAK_PUBLIC_URL=https://your-domain.example/keycloak
KC_PROXY_TRUSTED_ADDRESSES=172.16.0.0/12,10.0.0.0/8,192.168.0.0/16,127.0.0.0/8
```

and expose Keycloak through nginx at `/keycloak/`. The production deploy scripts also run
`infrastructure/scripts/prod/apply-keycloak-prod-settings.sh` after the stack starts, because
Keycloak skips realm imports when the realm already exists.
