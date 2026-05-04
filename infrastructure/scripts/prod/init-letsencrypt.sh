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
  echo "Copy .env.prod.example to .env.prod and fill it first."
  exit 1
fi

DOMAIN="$(read_env_value DOMAIN)"
DOMAIN="${DOMAIN:-o-ogo.ru}"
CERTBOT_EMAIL="$(read_env_value CERTBOT_EMAIL)"
CERTBOT_EXTRA_DOMAINS="$(read_env_value CERTBOT_EXTRA_DOMAINS)"
CERTBOT_DRY_RUN="$(read_env_value CERTBOT_DRY_RUN)"
CERTBOT_STAGING="$(read_env_value CERTBOT_STAGING)"

if [[ -z "$CERTBOT_EMAIL" ]]; then
  echo "CERTBOT_EMAIL is required in $ENV_FILE"
  exit 1
fi

cd "$ROOT_DIR"

mkdir -p \
  data/app-backup \
  data/app-logs \
  data/bots \
  data/certbot/conf \
  data/mysql_backup \
  data/nginx/certs \
  data/nginx/logs \
  data/nginx/www

if [[ ! -s data/nginx/certs/fullchain.pem || ! -s data/nginx/certs/privkey.pem ]]; then
  echo "Creating a temporary self-signed certificate for nginx bootstrap."
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout data/nginx/certs/privkey.pem \
    -out data/nginx/certs/fullchain.pem \
    -subj "/CN=$DOMAIN"
fi

domain_args=(-d "$DOMAIN")
if [[ -n "${CERTBOT_EXTRA_DOMAINS:-}" ]]; then
  IFS=',' read -ra extra_domains <<< "$CERTBOT_EXTRA_DOMAINS"
  for extra_domain in "${extra_domains[@]}"; do
    extra_domain="$(echo "$extra_domain" | xargs)"
    if [[ -n "$extra_domain" ]]; then
      domain_args+=(-d "$extra_domain")
    fi
  done
fi

certbot_args=()
case "${CERTBOT_DRY_RUN,,}" in
  true|1|yes|y)
    certbot_args+=(--dry-run)
    ;;
esac

if [[ ${#certbot_args[@]} -eq 0 ]]; then
  case "${CERTBOT_STAGING,,}" in
    true|1|yes|y)
      certbot_args+=(--staging)
      ;;
  esac
fi

echo "Pulling production images."
"${COMPOSE[@]}" pull

echo "Starting production stack with temporary TLS if needed."
"${COMPOSE[@]}" up -d

echo "Requesting Let's Encrypt certificate for: ${domain_args[*]}"
"${COMPOSE[@]}" run --rm certbot certonly \
  "${certbot_args[@]}" \
  --webroot \
  --webroot-path /var/www/certbot \
  --email "$CERTBOT_EMAIL" \
  --agree-tos \
  --no-eff-email \
  --non-interactive \
  --expand \
  "${domain_args[@]}"

if [[ " ${certbot_args[*]} " == *" --dry-run "* ]]; then
  echo "Certbot dry run completed successfully. No certificate was installed."
  exit 0
fi

echo "Installing issued certificate into nginx certificate mount."
"${COMPOSE[@]}" run --rm --entrypoint sh certbot -c \
  "cp /etc/letsencrypt/live/$DOMAIN/fullchain.pem /etc/nginx/certs/fullchain.pem && cp /etc/letsencrypt/live/$DOMAIN/privkey.pem /etc/nginx/certs/privkey.pem && chmod 644 /etc/nginx/certs/fullchain.pem && chmod 600 /etc/nginx/certs/privkey.pem"

echo "Reloading nginx."
"${COMPOSE[@]}" exec nginx nginx -s reload

echo "Done. Production stack is running with a Let's Encrypt certificate."
