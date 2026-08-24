#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

bash "$ROOT_DIR/scripts/ensure-prod-reliability-secrets.sh"

profiles="$(prod_env_get COMPOSE_PROFILES "$ENV_FILE")"
if [[ ",$profiles," != *,observability,* ]]; then
  if [[ -n "$profiles" ]]; then
    profiles="$profiles,observability"
  else
    profiles="observability"
  fi
  temporary="$(mktemp "${ENV_FILE}.profiles.XXXXXX")"
  awk -v replacement="$profiles" '
    BEGIN { written = 0 }
    /^[[:space:]]*COMPOSE_PROFILES=/ {
      if (!written) print "COMPOSE_PROFILES=" replacement
      written = 1
      next
    }
    { print }
    END { if (!written) print "COMPOSE_PROFILES=" replacement }
  ' "$ENV_FILE" > "$temporary"
  chmod 600 "$temporary"
  mv -- "$temporary" "$ENV_FILE"
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d --force-recreate alertmanager prometheus
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d grafana

for endpoint in http://127.0.0.1:9093/-/ready http://127.0.0.1:9090/-/ready; do
  ready=false
  for _ in $(seq 1 30); do
    if curl --fail --silent --show-error "$endpoint" >/dev/null; then
      ready=true
      break
    fi
    sleep 2
  done
  [[ "$ready" == "true" ]] || { echo "Observability endpoint is not ready" >&2; exit 1; }
done

alertmanager_connected=false
for _ in $(seq 1 20); do
  if curl --fail --silent --show-error \
      http://127.0.0.1:9090/api/v1/alertmanagers \
      | grep -q 'alertmanager:9093'; then
    alertmanager_connected=true
    break
  fi
  sleep 2
done
[[ "$alertmanager_connected" == "true" ]] || {
  echo "Prometheus has not connected to Alertmanager" >&2
  exit 1
}

echo "Alertmanager, Prometheus and Grafana are ready"
