#!/usr/bin/env sh
set -eu

ENV_FILE="${1:-.env}"

compose() {
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose -f docker-compose.yaml --env-file "$ENV_FILE" "$@"
  elif docker compose version >/dev/null 2>&1; then
    docker compose -f docker-compose.yaml --env-file "$ENV_FILE" "$@"
  else
    echo "Docker Compose is not installed. Install docker-compose or the Docker Compose plugin." >&2
    exit 1
  fi
}

env_value() {
  key="$1"
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi

  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[^=]*=/, "")
      value = $0
    }
    END {
      if (value != "") {
        print value
      }
    }
  ' "$ENV_FILE" | tr -d '\r'
}

kc() {
  compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

APP_BASE_URL="${OTZIV_APP_BASE_URL:-$(env_value OTZIV_APP_BASE_URL)}"
KEYCLOAK_PUBLIC_URL="${KEYCLOAK_PUBLIC_URL:-$(env_value KEYCLOAK_PUBLIC_URL)}"
REALM="${KEYCLOAK_ADMIN_REALM:-$(env_value KEYCLOAK_ADMIN_REALM)}"
ADMIN_USER="${KEYCLOAK_ADMIN:-$(env_value KEYCLOAK_ADMIN)}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-$(env_value KEYCLOAK_ADMIN_PASSWORD)}"
BACKEND_CLIENT_ID="${KEYCLOAK_ADMIN_CLIENT_ID:-$(env_value KEYCLOAK_ADMIN_CLIENT_ID)}"
BACKEND_CLIENT_SECRET="${KEYCLOAK_ADMIN_CLIENT_SECRET:-$(env_value KEYCLOAK_ADMIN_CLIENT_SECRET)}"

APP_BASE_URL="${APP_BASE_URL:-https://o-ogo.ru}"
KEYCLOAK_PUBLIC_URL="${KEYCLOAK_PUBLIC_URL:-https://o-ogo.ru/keycloak}"
REALM="${REALM:-otziv}"
BACKEND_CLIENT_ID="${BACKEND_CLIENT_ID:-otziv-backend}"

if [ -z "$ADMIN_USER" ] || [ -z "$ADMIN_PASSWORD" ]; then
  echo "KEYCLOAK_ADMIN and KEYCLOAK_ADMIN_PASSWORD must be set in $ENV_FILE." >&2
  exit 1
fi

echo "Applying Keycloak production settings for $KEYCLOAK_PUBLIC_URL."

configured="0"
for attempt in $(seq 1 30); do
  if kc config credentials \
    --server http://localhost:8080/keycloak \
    --realm master \
    --user "$ADMIN_USER" \
    --password "$ADMIN_PASSWORD" >/dev/null 2>&1; then
    configured="1"
    break
  fi

  sleep 2
done

if [ "$configured" != "1" ]; then
  echo "Could not authenticate to Keycloak admin CLI after waiting." >&2
  exit 1
fi

kc update "realms/$REALM" \
  -s sslRequired=external \
  -s loginTheme=otziv \
  -s accessTokenLifespan=600 \
  -s ssoSessionIdleTimeout=28800 \
  -s ssoSessionMaxLifespan=86400

frontend_client_uuid="$(
  kc get clients -r "$REALM" -q clientId=otziv-frontend --fields id --format csv --noquotes \
    | tr -d '\r' \
    | tail -n 1
)"

if [ -n "$frontend_client_uuid" ] && [ "$frontend_client_uuid" != "id" ]; then
  kc update "clients/$frontend_client_uuid" -r "$REALM" \
    -s "redirectUris=[\"$APP_BASE_URL/*\"]" \
    -s "webOrigins=[\"$APP_BASE_URL\"]"

  kc update "clients/$frontend_client_uuid" -r "$REALM" \
    -s "attributes.\"pkce.code.challenge.method\"=S256" \
    -s "attributes.\"post.logout.redirect.uris\"=$APP_BASE_URL/*" \
    || echo "Warning: could not update optional frontend client attributes." >&2
else
  echo "Warning: frontend Keycloak client otziv-frontend was not found." >&2
fi

backend_client_uuid="$(
  kc get clients -r "$REALM" -q clientId="$BACKEND_CLIENT_ID" --fields id --format csv --noquotes \
    | tr -d '\r' \
    | tail -n 1
)"

if [ -n "$backend_client_uuid" ] && [ "$backend_client_uuid" != "id" ]; then
  backend_update_args="
    -s enabled=true
    -s publicClient=false
    -s serviceAccountsEnabled=true
    -s standardFlowEnabled=false
    -s directAccessGrantsEnabled=false
    -s clientAuthenticatorType=client-secret
  "

  if [ -n "$BACKEND_CLIENT_SECRET" ]; then
    kc update "clients/$backend_client_uuid" -r "$REALM" \
      -s enabled=true \
      -s publicClient=false \
      -s serviceAccountsEnabled=true \
      -s standardFlowEnabled=false \
      -s directAccessGrantsEnabled=false \
      -s clientAuthenticatorType=client-secret \
      -s "secret=$BACKEND_CLIENT_SECRET"
  else
    echo "Warning: KEYCLOAK_ADMIN_CLIENT_SECRET is empty; backend client secret was not changed." >&2
    kc update "clients/$backend_client_uuid" -r "$REALM" \
      $backend_update_args
  fi

  kc add-roles -r "$REALM" --uusername "service-account-$BACKEND_CLIENT_ID" --rolename ADMIN \
    || echo "Warning: could not add ADMIN role to backend service account." >&2

  backend_audience_mapper_id="$(
    kc get "clients/$backend_client_uuid/protocol-mappers/models" -r "$REALM" \
      --fields id,name --format csv --noquotes \
      | tr -d '\r' \
      | awk -F, '$2 == "otziv-backend-audience" { print $1; exit }'
  )"

  if [ -n "$backend_audience_mapper_id" ]; then
    kc update "clients/$backend_client_uuid/protocol-mappers/models/$backend_audience_mapper_id" -r "$REALM" \
      -s name=otziv-backend-audience \
      -s protocol=openid-connect \
      -s protocolMapper=oidc-audience-mapper \
      -s "config.\"included.client.audience\"=$BACKEND_CLIENT_ID" \
      -s "config.\"id.token.claim\"=false" \
      -s "config.\"access.token.claim\"=true"
  else
    kc create "clients/$backend_client_uuid/protocol-mappers/models" -r "$REALM" \
      -s name=otziv-backend-audience \
      -s protocol=openid-connect \
      -s protocolMapper=oidc-audience-mapper \
      -s "config.\"included.client.audience\"=$BACKEND_CLIENT_ID" \
      -s "config.\"id.token.claim\"=false" \
      -s "config.\"access.token.claim\"=true"
  fi
else
  echo "Warning: backend Keycloak client $BACKEND_CLIENT_ID was not found." >&2
fi

echo "Keycloak production settings applied."
