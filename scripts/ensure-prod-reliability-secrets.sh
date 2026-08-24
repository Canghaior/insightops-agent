#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
SECRET_DIR="${RELIABILITY_SECRET_DIR:-$ROOT_DIR/.secrets}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

set_env_value() {
  local name="$1"
  local value="$2"
  local temporary
  [[ "$name" =~ ^[A-Z0-9_]+$ ]] || { echo "Invalid environment name" >&2; exit 1; }
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || {
    echo "Environment values must be single-line" >&2
    exit 1
  }
  temporary="$(mktemp "${ENV_FILE}.reliability.XXXXXX")"
  awk -v target="$name" -v replacement="$value" '
    BEGIN { written = 0 }
    $0 ~ "^[[:space:]]*" target "=" {
      if (!written) print target "=" replacement
      written = 1
      next
    }
    { print }
    END { if (!written) print target "=" replacement }
  ' "$ENV_FILE" > "$temporary"
  chmod 600 "$temporary"
  mv -- "$temporary" "$ENV_FILE"
}

webhook_url="$(prod_env_get ALERT_WEBHOOK_URL "$ENV_FILE")"
if [[ -z "$webhook_url" ]]; then
  topic="insightops-$(openssl rand -hex 24)"
  webhook_url="https://ntfy.sh/${topic}?template=alertmanager&firebase=no"
  set_env_value ALERT_WEBHOOK_URL "$webhook_url"
fi
if [[ ! "$webhook_url" =~ ^https://ntfy\.sh/insightops-[a-f0-9]{48}\?template=alertmanager\&firebase=no$ ]]; then
  echo "ALERT_WEBHOOK_URL must be an InsightOps random private ntfy HTTPS topic" >&2
  exit 1
fi

offsite_passphrase="$(prod_env_get OFFSITE_BACKUP_PASSPHRASE "$ENV_FILE")"
if [[ -z "$offsite_passphrase" ]]; then
  offsite_passphrase="$(openssl rand -hex 32)"
  set_env_value OFFSITE_BACKUP_PASSPHRASE "$offsite_passphrase"
fi
if (( ${#offsite_passphrase} < 32 )); then
  echo "OFFSITE_BACKUP_PASSPHRASE must contain at least 32 characters" >&2
  exit 1
fi

install -m 700 -d "$SECRET_DIR"
umask 077
printf '%s\n' "$webhook_url" > "$SECRET_DIR/alertmanager-webhook-url"
printf '%s\n' "$offsite_passphrase" > "$SECRET_DIR/offsite-backup-passphrase"
chmod 644 "$SECRET_DIR/alertmanager-webhook-url"
chmod 600 "$SECRET_DIR/offsite-backup-passphrase" "$ENV_FILE"

echo "Production reliability secret files are ready"
