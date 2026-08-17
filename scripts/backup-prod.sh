#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
BACKUP_DIR="$ROOT_DIR/backups"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
BACKUP_RETENTION_DAYS="$(prod_env_get BACKUP_RETENTION_DAYS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$BACKUP_DIR/insightops-$timestamp.dump"
temporary="$target.partial"

cleanup() { rm -f -- "$temporary"; }
trap cleanup EXIT

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump --format=custom --compress=9 --no-owner --no-privileges \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" > "$temporary"

test -s "$temporary"
mv -- "$temporary" "$target"
sha256sum "$target" > "$target.sha256"
chmod 600 "$target" "$target.sha256"

retention_days="${BACKUP_RETENTION_DAYS:-30}"
if ! [[ "$retention_days" =~ ^[0-9]+$ ]] || (( retention_days < 7 )); then
  echo "BACKUP_RETENTION_DAYS must be an integer of at least 7" >&2
  exit 1
fi
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'insightops-*.dump' -o -name 'insightops-*.dump.sha256' \) \
  -mtime "+$retention_days" -delete

trap - EXIT
echo "Backup created: $target"
