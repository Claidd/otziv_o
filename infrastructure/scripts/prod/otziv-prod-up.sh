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

compose_project_name="otziv-prod"
if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif [[ -x /usr/local/bin/docker-compose ]]; then
  compose=(/usr/local/bin/docker-compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "Docker Compose is unavailable for production self-heal." >&2
  exit 1
fi

# External review checking is an explicit opt-in. Read the hard deployment
# switch without sourcing the secret-bearing env file.
external_review_enabled="$({
  grep -E '^EXTERNAL_REVIEW_CHECK_ENABLED=' "$env_file" || true
} | tail -n 1 | cut -d= -f2- | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
case "$external_review_enabled" in
  ""|false)
    external_review_enabled=false
    ;;
  true)
    ;;
  *)
    echo "EXTERNAL_REVIEW_CHECK_ENABLED must be exactly true or false." >&2
    exit 1
    ;;
esac

compose_args=(
  --project-name "$compose_project_name"
  --project-directory "$deploy_path"
  -f "$compose_file"
  --env-file "$env_file"
)
if [[ "$external_review_enabled" == "true" ]]; then
  "${compose[@]}" "${compose_args[@]}" --profile external-review up -d
else
  # Ensure a worker from an earlier opt-in rollout stays stopped, including
  # after a host reboot, while reconciling every default production service.
  "${compose[@]}" "${compose_args[@]}" --profile external-review stop external-review-worker
  "${compose[@]}" "${compose_args[@]}" up -d
fi
