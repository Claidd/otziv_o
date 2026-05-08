#!/usr/bin/env sh
set -eu

CONTAINER="${CONTAINER:-otziv-keycloak}"
REALM="${REALM:-otziv}"
THEME="${THEME:-otziv}"
ACCESS_TOKEN_LIFESPAN="${ACCESS_TOKEN_LIFESPAN:-600}"
SSO_SESSION_IDLE_TIMEOUT="${SSO_SESSION_IDLE_TIMEOUT:-28800}"
SSO_SESSION_MAX_LIFESPAN="${SSO_SESSION_MAX_LIFESPAN:-86400}"
SERVER="${KEYCLOAK_KCADM_SERVER:-http://localhost:8080/keycloak}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
  --server "$SERVER" \
  --realm master \
  --user "$ADMIN_USER" \
  --password "$ADMIN_PASSWORD"

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh update "realms/$REALM" \
  -s "loginTheme=$THEME" \
  -s "internationalizationEnabled=true" \
  -s "defaultLocale=ru" \
  -s 'supportedLocales=["ru","en"]' \
  -s "accessTokenLifespan=$ACCESS_TOKEN_LIFESPAN" \
  -s "ssoSessionIdleTimeout=$SSO_SESSION_IDLE_TIMEOUT" \
  -s "ssoSessionMaxLifespan=$SSO_SESSION_MAX_LIFESPAN"

echo "Keycloak realm settings applied to realm '$REALM'."
