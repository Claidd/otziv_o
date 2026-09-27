#!/usr/bin/env bash
set -euo pipefail

mode="${1:---dry-run}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
root="${2:-/docker}"
case "$mode" in
  --dry-run)
    exec python3 "$script_dir/disk_maintenance.py" --root "$root"
    ;;
  --apply)
    exec python3 "$script_dir/disk_maintenance.py" --apply --root "$root"
    ;;
  *)
    echo "Usage: $0 [--dry-run|--apply] [/deployment/path]" >&2
    exit 2
    ;;
esac
