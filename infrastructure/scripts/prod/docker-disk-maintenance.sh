#!/usr/bin/env bash
set -euo pipefail

mode="${1:---dry-run}"
case "$mode" in
  --dry-run)
    docker system df
    echo
    echo "Dry run only. --apply removes unused images and build cache older than 7 days."
    echo "Volumes and running containers are never removed."
    ;;
  --apply)
    docker image prune --force
    docker builder prune --force --filter "until=168h"
    docker system df
    ;;
  *)
    echo "Usage: $0 [--dry-run|--apply]" >&2
    exit 2
    ;;
esac
