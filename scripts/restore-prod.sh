#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
BACKUP_DIR="$ROOT_DIR/backups"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"
backup_file="${1:-}"
confirmation="${2:-}"

if [[ -z "$backup_file" || "$confirmation" != "--confirm-destructive-restore" ]]; then
  echo "Usage: $0 backups/insightops-<timestamp>.dump --confirm-destructive-restore" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
resolved_backup="$(realpath "$backup_file")"
resolved_directory="$(realpath "$BACKUP_DIR")"
if [[ "$resolved_backup" != "$resolved_directory/"* || ! -f "$resolved_backup" ]]; then
  echo "Backup must be an existing file inside $BACKUP_DIR" >&2
  exit 1
fi

if [[ -f "$resolved_backup.sha256" ]]; then
  (cd "$BACKUP_DIR" && sha256sum --check "$(basename "$resolved_backup.sha256")")
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"

running_app_services=()
for service in server worker; do
  if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
      ps --status running -q "$service" | grep -q .; then
    running_app_services+=("$service")
  fi
done

if (( ${#running_app_services[@]} > 0 )); then
  echo "Stopping application containers before restore..."
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" stop "${running_app_services[@]}"
fi

restore_failed=0
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_restore --clean --if-exists --no-owner --no-privileges \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" < "$resolved_backup" || restore_failed=1

if (( ${#running_app_services[@]} > 0 )); then
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" start "${running_app_services[@]}"
fi
if (( restore_failed != 0 )); then
  echo "Restore failed; previously running application containers were restarted. Inspect PostgreSQL logs." >&2
  exit 1
fi

echo "Restore completed from $resolved_backup"
