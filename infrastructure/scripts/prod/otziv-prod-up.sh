#!/usr/bin/env bash
set -Eeuo pipefail

umask 027

deploy_path="$(pwd -P)"
if [[ "$deploy_path" != /* || "$deploy_path" == "/" ]]; then
  echo "Production self-heal requires a specific absolute WorkingDirectory." >&2
  exit 1
fi

deploy_lock="$deploy_path/.deploy.lock.d"
if [[ -e "$deploy_lock" || -L "$deploy_lock" ]]; then
  echo "Production deployment lock is present; self-heal reconciliation skipped."
  exit 0
fi

compose_file="$deploy_path/docker-compose.yaml"
env_selector="$deploy_path/.self-heal-env-file"
env_file_name=".env"
if [[ -L "$env_selector" ]]; then
  echo "Production self-heal env selector must not be a symlink." >&2
  exit 1
fi
if [[ -f "$env_selector" ]]; then
  IFS= read -r env_file_name < "$env_selector" || true
fi
if [[ ! "$env_file_name" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Production self-heal env selector contains an unsafe file name." >&2
  exit 1
fi
env_file="$deploy_path/$env_file_name"
if [[ ! -r "$compose_file" || ! -r "$env_file" ]]; then
  echo "Production compose or env file is unavailable in $deploy_path." >&2
  exit 1
fi

if [[ -x /usr/local/bin/docker-compose ]]; then
  compose=(/usr/local/bin/docker-compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
elif docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
else
  echo "Docker Compose is unavailable for production self-heal." >&2
  exit 1
fi

# Release 5.50 always deploys and health-checks this profile. Running without an
# explicit service list also reconciles default services such as Alloy that the
# previous VPS helper accidentally omitted.
"${compose[@]}" \
  -f "$compose_file" \
  --env-file "$env_file" \
  --profile external-review \
  up -d
