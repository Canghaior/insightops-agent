#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  echo "Copy .env.prod.example to .env.prod and replace every CHANGE_ME value." >&2
  exit 1
fi

required=(POSTGRES_PASSWORD DB_PASSWORD GRAFANA_ADMIN_PASSWORD APP_ADDRESS)
for name in "${required[@]}"; do
  value="$(prod_env_get "$name" "$ENV_FILE")"
  if [[ -z "$value" || "$value" == *CHANGE_ME* ]]; then
    echo "$name is missing or still contains CHANGE_ME" >&2
    exit 1
  fi
done

POSTGRES_PASSWORD="$(prod_env_get POSTGRES_PASSWORD "$ENV_FILE")"
DB_PASSWORD="$(prod_env_get DB_PASSWORD "$ENV_FILE")"
GRAFANA_ADMIN_PASSWORD="$(prod_env_get GRAFANA_ADMIN_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
ALLOW_INSECURE_LOCAL="$(prod_env_get ALLOW_INSECURE_LOCAL "$ENV_FILE")"
AUTH_SECURE_COOKIE="$(prod_env_get AUTH_SECURE_COOKIE "$ENV_FILE")"
DEEPSEEK_ENABLED="$(prod_env_get DEEPSEEK_ENABLED "$ENV_FILE")"
DEEPSEEK_API_KEY="$(prod_env_get DEEPSEEK_API_KEY "$ENV_FILE")"
AUTH_BOOTSTRAP_ENABLED="$(prod_env_get AUTH_BOOTSTRAP_ENABLED "$ENV_FILE")"
AUTH_BOOTSTRAP_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"

if [[ "$POSTGRES_PASSWORD" != "$DB_PASSWORD" ]]; then
  echo "POSTGRES_PASSWORD and DB_PASSWORD must match for the bundled PostgreSQL service." >&2
  exit 1
fi

if (( ${#POSTGRES_PASSWORD} < 16 || ${#GRAFANA_ADMIN_PASSWORD} < 16 )); then
  echo "Database and Grafana passwords must contain at least 16 characters." >&2
  exit 1
fi

if [[ "${AUTH_BOOTSTRAP_ENABLED:-false}" == "true" ]]; then
  if (( ${#AUTH_BOOTSTRAP_PASSWORD} < 10 || ${#AUTH_BOOTSTRAP_PASSWORD} > 72 )) \
      || [[ ! "$AUTH_BOOTSTRAP_PASSWORD" =~ [A-Z] ]] \
      || [[ ! "$AUTH_BOOTSTRAP_PASSWORD" =~ [a-z] ]] \
      || [[ ! "$AUTH_BOOTSTRAP_PASSWORD" =~ [0-9] ]]; then
    echo "AUTH_BOOTSTRAP_PASSWORD must be 10-72 characters and contain upper-case, lower-case and digit characters." >&2
    exit 1
  fi
fi

if [[ "${ALLOW_INSECURE_LOCAL:-false}" != "true" ]]; then
  if [[ "$APP_ADDRESS" == http://* || "$APP_ADDRESS" == https://* \
      || "$APP_ADDRESS" == *localhost* || "$APP_ADDRESS" != *.* \
      || ! "$APP_ADDRESS" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$ ]]; then
    echo "Public deployment requires a real domain in APP_ADDRESS, without http://." >&2
    exit 1
  fi
  if [[ "${AUTH_SECURE_COOKIE:-true}" != "true" ]]; then
    echo "AUTH_SECURE_COOKIE must be true for public deployment." >&2
    exit 1
  fi
  if [[ "${DEEPSEEK_ENABLED:-false}" == "true" && -z "${DEEPSEEK_API_KEY:-}" ]]; then
    echo "DEEPSEEK_API_KEY is required when DEEPSEEK_ENABLED=true." >&2
    exit 1
  fi
fi

command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 1; }
docker compose version >/dev/null
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet

echo "Production preflight passed for $APP_ADDRESS"
