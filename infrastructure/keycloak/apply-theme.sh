#!/usr/bin/env sh
set -eu

CONTAINER="${CONTAINER:-otziv-keycloak}"
REALM="${REALM:-otziv}"
THEME="${THEME:-otziv}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$ADMIN_USER" \
  --password "$ADMIN_PASSWORD"

docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh update "realms/$REALM" \
  -s "loginTheme=$THEME" \
  -s "internationalizationEnabled=true" \
  -s "defaultLocale=ru" \
  -s 'supportedLocales=["ru","en"]'

echo "Keycloak login theme '$THEME' applied to realm '$REALM'."
