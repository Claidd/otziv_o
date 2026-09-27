#!/usr/bin/env bash
set -Eeuo pipefail

root="${1:-/docker}"
if [[ "$EUID" -ne 0 || ! "$root" =~ ^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$ || "$root" == "/" || -L "$root" ]]; then
  echo "Usage: sudo bash $0 /absolute/deployment/path" >&2
  exit 2
fi
root="$(realpath -e -- "$root")"
source_root="$(cd -- "$(dirname -- "$0")/../../.." && pwd -P)"
test -f "$root/docker-compose.yaml"
test -f "$source_root/infrastructure/scripts/prod/disk_maintenance.py"
stage="$(mktemp -d)"
trap 'rm -f -- "$stage/otziv-disk-maintenance.service" "$stage/otziv-disk-maintenance.timer"; rmdir -- "$stage"' EXIT
sed "s|@@OTZIV_DEPLOY_PATH@@|$root|g" "$source_root/infrastructure/systemd/otziv-disk-maintenance.service.in" > "$stage/otziv-disk-maintenance.service"
cp "$source_root/infrastructure/systemd/otziv-disk-maintenance.timer" "$stage/otziv-disk-maintenance.timer"
systemd-analyze verify "$stage/otziv-disk-maintenance.service" "$stage/otziv-disk-maintenance.timer"
install -o root -g root -m 0755 "$source_root/infrastructure/scripts/prod/disk_maintenance.py" /usr/local/sbin/otziv-disk-maintenance.py
install -o root -g root -m 0644 "$stage/otziv-disk-maintenance.service" /etc/systemd/system/otziv-disk-maintenance.service
install -o root -g root -m 0644 "$stage/otziv-disk-maintenance.timer" /etc/systemd/system/otziv-disk-maintenance.timer
systemctl daemon-reload
systemctl enable --now otziv-disk-maintenance.timer
echo "Disk maintenance installed: checks every 5 minutes, cleanup at 80%, MySQL retention unchanged unless configured."
