#!/bin/sh
# Grafana entrypoint for Telegram alerts (audit INF-3).
#
# Alert rules are static provisioning (provisioning/alerting/otziv-alert-rules.json). The
# contact point is built here, at every container start, from the application's own bot
# settings: TELEGRAM_BOT_TOKEN and the comma-separated TELEGRAM_ADMIN_CHAT_IDS (one
# Telegram integration per chat; Grafana takes a single chat id per integration). The token
# stays out of the file: Grafana expands the env reference when it provisions. The script
# never prints the token or the chat ids.
#
# The mounted provisioning directory is read-only, so it is copied with the generated file
# to a private directory, which GF_PATHS_PROVISIONING then points to; /run.sh is the
# image's own entrypoint. "--render SRC DST" only writes the directory (tests).
set -eu
# Chat ids are split on whitespace below; never expand them as file globs.
set -f

log() { echo "otziv-alerting: $*" >&2; }

render() {
  src=$1
  dst=$2
  rm -rf "$dst"
  mkdir -p "$dst"
  chmod 700 "$dst"
  cp -R "$src/." "$dst/"
  mkdir -p "$dst/alerting"
  out="$dst/alerting/otziv-telegram.yaml"

  token=${TELEGRAM_BOT_TOKEN:-}
  if ! printf '%s' "$token" | grep -Eq '^[0-9]{1,20}:[A-Za-z0-9_-]{20,128}$'; then
    log "Telegram delivery is off: TELEGRAM_BOT_TOKEN is missing or malformed. Rules stay visible in Grafana."
    return 0
  fi

  chats=""
  skipped=0
  for chat in $(printf '%s' "${TELEGRAM_ADMIN_CHAT_IDS:-}" | tr ',;' '  '); do
    if ! printf '%s' "$chat" | grep -Eq '^-?[0-9]{1,20}$'; then
      skipped=$((skipped + 1))
      continue
    fi
    case " $chats " in
      *" $chat "*) ;;
      *) chats="$chats $chat" ;;
    esac
  done
  [ "$skipped" -eq 0 ] || log "ignored $skipped malformed entries of TELEGRAM_ADMIN_CHAT_IDS"
  if [ -z "$chats" ]; then
    log "Telegram delivery is off: TELEGRAM_ADMIN_CHAT_IDS has no chat id. Rules stay visible in Grafana."
    return 0
  fi

  umask 077
  {
    echo 'apiVersion: 1'
    echo 'contactPoints:'
    echo '  - orgId: 1'
    echo '    name: otziv-telegram'
    echo '    receivers:'
    index=0
    for chat in $chats; do
      index=$((index + 1))
      echo "      - uid: otziv-telegram-$index"
      echo '        type: telegram'
      echo '        disableResolveMessage: false'
      echo '        settings:'
      # Literal reference: Grafana substitutes the variable from its own environment.
      echo '          bottoken: $TELEGRAM_BOT_TOKEN'
      echo "          chatid: \"$chat\""
      echo '          parse_mode: HTML'
      echo '          disable_web_page_preview: true'
      echo "          message: '{{ template \"otziv.telegram.message\" . }}'"
    done
    echo 'policies:'
    echo '  - orgId: 1'
    echo '    receiver: otziv-telegram'
    echo '    group_by: [grafana_folder, alertname]'
    echo '    group_wait: 30s'
    echo '    group_interval: 5m'
    echo '    repeat_interval: 4h'
  } > "$out"
  log "Telegram delivery is on: $index admin chat(s)."
}

# Same proxy as the application's bot (TELEGRAM_PROXY_*): Grafana's HTTP clients honour
# HTTPS_PROXY. Datasources stay direct: they are plain HTTP and listed in NO_PROXY anyway.
proxy() {
  [ "${TELEGRAM_PROXY_ENABLED:-false}" = "true" ] || return 0
  host=${TELEGRAM_PROXY_HOST:-}
  port=${TELEGRAM_PROXY_PORT:-8888}
  if ! printf '%s' "$host" | grep -Eq '^[A-Za-z0-9.-]{1,253}$' || ! printf '%s' "$port" | grep -Eq '^[0-9]{1,5}$'; then
    log "TELEGRAM_PROXY_ENABLED=true without a valid TELEGRAM_PROXY_HOST/PORT: alerts go direct"
    return 0
  fi
  case "$(printf '%s' "${TELEGRAM_PROXY_TYPE:-HTTP}" | tr '[:lower:]' '[:upper:]')" in
    HTTP) scheme=http ;;
    SOCKS5) scheme=socks5 ;;
    *) log "TELEGRAM_PROXY_TYPE is not HTTP or SOCKS5: alerts go direct"; return 0 ;;
  esac
  export HTTPS_PROXY="$scheme://$host:$port"
  # Only Telegram needs the proxy; Grafana update and plugin checks stay direct as before.
  export NO_PROXY="localhost,127.0.0.1,prometheus,loki,tempo,grafana,grafana.com,.grafana.com,grafana.net,.grafana.net"
  log "Telegram alerts use the TELEGRAM_PROXY_* proxy ($scheme)."
}

if [ "${1:-}" = "--render" ]; then
  render "$2" "$3"
  exit 0
fi

target=/tmp/otziv-grafana-provisioning
render "${GF_PATHS_PROVISIONING:-/etc/grafana/provisioning}" "$target"
export GF_PATHS_PROVISIONING="$target"
proxy
exec /run.sh "$@"
