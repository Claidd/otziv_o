#!/bin/bash
set -euo pipefail

WG_IF="wg0"
DOMAIN="api.openai.com"
LOG_PREFIX="[OpenAI VPN Route]"
MAX_IPS=20

echo "$LOG_PREFIX 🔄 Обновление маршрутов $(date)"

# Получаем актуальные IPv4-адреса
CURRENT_IPS=$(dig +short $DOMAIN A | grep -Eo '^[0-9.]+' | sort -u)

if [[ -z "$CURRENT_IPS" ]]; then
  echo "$LOG_PREFIX ❌ Не удалось получить IP-адреса для $DOMAIN"
  exit 1
fi

# Защита от слишком большого списка IP
if (( $(echo "$CURRENT_IPS" | wc -l) > MAX_IPS )); then
  echo "$LOG_PREFIX ⚠️ Слишком много IP-адресов (>$MAX_IPS), пропускаю"
  exit 1
fi

# Обновляем маршруты
for IP in $CURRENT_IPS; do
  echo "$LOG_PREFIX 🔁 Обновляю маршрут для $IP через $WG_IF"
  ip route replace "$IP" dev "$WG_IF" || echo "$LOG_PREFIX ⚠️ Не удалось обновить маршрут $IP"
done

# Удаляем устаревшие маршруты
EXISTING_ROUTES=$(ip route | grep "dev $WG_IF" | grep -Eo '^[0-9.]+')
for IP in $EXISTING_ROUTES; do
  if ! echo "$CURRENT_IPS" | grep -q "$IP"; then
    echo "$LOG_PREFIX ❌ Удаляю устаревший маршрут $IP"
    ip route del "$IP" || echo "$LOG_PREFIX ⚠️ Не удалось удалить маршрут $IP"
  fi
done
