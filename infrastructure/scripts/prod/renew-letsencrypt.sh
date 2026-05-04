#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.prod}"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yaml" --env-file "$ENV_FILE")

read_env_value() {
  local key="$1"
  local value=""
  local line=""

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != "$key="* ]] && continue
    value="${line#*=}"
  done < "$ENV_FILE"

  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  printf '%s' "$value"
}

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE"
  exit 1
fi

DOMAIN="$(read_env_value DOMAIN)"
DOMAIN="${DOMAIN:-o-ogo.ru}"
CERTBOT_DRY_RUN="$(read_env_value CERTBOT_DRY_RUN)"

cd "$ROOT_DIR"

renew_args=()
case "${CERTBOT_DRY_RUN,,}" in
  true|1|yes|y)
    renew_args+=(--dry-run)
    ;;
esac

"${COMPOSE[@]}" run --rm certbot renew "${renew_args[@]}" --webroot --webroot-path /var/www/certbot

if [[ " ${renew_args[*]} " == *" --dry-run "* ]]; then
  echo "Certbot renew dry run completed successfully. No certificate was installed."
  exit 0
fi

"${COMPOSE[@]}" run --rm --entrypoint sh certbot -c \
  "if [ -f /etc/letsencrypt/live/$DOMAIN/fullchain.pem ]; then cp /etc/letsencrypt/live/$DOMAIN/fullchain.pem /etc/nginx/certs/fullchain.pem; cp /etc/letsencrypt/live/$DOMAIN/privkey.pem /etc/nginx/certs/privkey.pem; chmod 644 /etc/nginx/certs/fullchain.pem; chmod 600 /etc/nginx/certs/privkey.pem; fi"

"${COMPOSE[@]}" exec nginx nginx -s reload
