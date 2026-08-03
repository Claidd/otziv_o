#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

get_env() {
  local env_file="$1"
  local key="$2"
  local default_value="${3:-}"
  local value
  value="$(awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*#/ && index($0, key "=") == 1 {
      sub(/^[^=]*=/, "", $0)
      print $0
    }
  ' "$env_file" | tail -n 1 | sed -e 's/\r$//' -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")"
  if [ -n "$value" ]; then
    printf '%s' "$value"
  else
    printf '%s' "$default_value"
  fi
}

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <production-env-file> <running-app-container-id>" >&2
  exit 2
fi

env_file="$1"
app_container="$2"
if [ ! -f "$env_file" ]; then
  echo "MAX webhook registration env file is missing: $env_file" >&2
  exit 1
fi
if ! printf '%s' "$app_container" | grep -Eq '^[0-9a-f]{12,64}$' \
    || [ "$(docker inspect -f '{{.State.Running}}' "$app_container" 2>/dev/null || true)" != "true" ]; then
  echo "MAX webhook registration requires a running app container." >&2
  exit 1
fi

token="$(get_env "$env_file" MAX_BOT_TOKEN)"
secret="$(get_env "$env_file" MAX_BOT_WEBHOOK_SECRET)"
api_base="$(get_env "$env_file" MAX_BOT_API_BASE_URL https://platform-api2.max.ru)"
webhook_url="$(get_env "$env_file" MAX_BOT_WEBHOOK_URL)"
if [ -z "$webhook_url" ]; then
  app_base="$(get_env "$env_file" OTZIV_APP_BASE_URL)"
  webhook_url="${app_base%/}/webhook/max"
fi
update_types="$(get_env "$env_file" MAX_BOT_WEBHOOK_UPDATE_TYPES bot_started,bot_added,message_created)"
api_base="${api_base%/}"

if ! printf '%s' "$token" | grep -Eq '^[A-Za-z0-9._~-]{20,512}$'; then
  echo "MAX_BOT_TOKEN is missing or contains unsupported characters." >&2
  exit 1
fi
if ! printf '%s' "$secret" | grep -Eq '^[A-Za-z0-9_-]{5,256}$'; then
  echo "MAX_BOT_WEBHOOK_SECRET must match [A-Za-z0-9_-]{5,256}." >&2
  exit 1
fi
if ! printf '%s' "$api_base" | grep -Eq '^https://[A-Za-z0-9.-]+(:443)?(/[A-Za-z0-9._~/?&=%+-]*)?$'; then
  echo "MAX_BOT_API_BASE_URL must be a credential-free HTTPS URL." >&2
  exit 1
fi
if ! printf '%s' "$webhook_url" | grep -Eq '^https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~/?&=%+-]*)?$'; then
  echo "MAX webhook URL must be a credential-free HTTPS URL on port 443." >&2
  exit 1
fi
if ! printf '%s' "$update_types" | grep -Eq '^[a-z0-9_]+(,[a-z0-9_]+)*$'; then
  echo "MAX_BOT_WEBHOOK_UPDATE_TYPES contains unsupported values." >&2
  exit 1
fi

json_types="$(printf '%s' "$update_types" | awk -F, '{
  for (i = 1; i <= NF; i++) {
    printf "%s\"%s\"", (i == 1 ? "" : ","), $i
  }
}')"

registration_succeeded="0"
for attempt in 1 2 3; do
  if printf '%s\n' "$token" "$secret" "$api_base" "$webhook_url" "$json_types" \
      | docker exec -i "$app_container" sh -c '
        set -eu
        IFS= read -r token
        IFS= read -r secret
        IFS= read -r api_base
        IFS= read -r webhook_url
        IFS= read -r json_types
        request_config="$(mktemp)"
        request_body="$(mktemp)"
        cleanup() {
          rm -f -- "$request_config" "$request_body"
        }
        trap cleanup EXIT INT TERM
        chmod 600 "$request_config" "$request_body"
        printf "%s\n" \
          "url = \"$api_base/subscriptions\"" \
          "request = \"POST\"" \
          "header = \"Authorization: $token\"" \
          "header = \"Content-Type: application/json\"" > "$request_config"
        printf "{\"url\":\"%s\",\"update_types\":[%s],\"secret\":\"%s\"}" \
          "$webhook_url" "$json_types" "$secret" > "$request_body"
        response="$(curl --fail-with-body --silent --show-error --max-time 60 \
          --config "$request_config" --data-binary "@$request_body")"
        printf "%s" "$response" | grep -Eq "\"success\"[[:space:]]*:[[:space:]]*true"
      '; then
    registration_succeeded="1"
    break
  fi
  echo "MAX webhook registration attempt $attempt/3 failed." >&2
  if [ "$attempt" -lt 3 ]; then
    sleep 10
  fi
done

token=""
secret=""
if [ "$registration_succeeded" != "1" ]; then
  echo "MAX webhook registration was not confirmed; refusing to complete the deployment." >&2
  exit 1
fi

echo "MAX webhook registration confirmed without exposing token or secret."
