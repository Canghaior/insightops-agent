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
  echo "Usage: $0 backups/insightops-<timestamp>.dump --confirm-destructive-restore (requires matching uploads archive)" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
resolved_backup="$(realpath "$backup_file")"
resolved_directory="$(realpath "$BACKUP_DIR")"
if [[ "$resolved_backup" != "$resolved_directory/"* || ! -f "$resolved_backup" ]]; then
  echo "Backup must be an existing file inside $BACKUP_DIR" >&2
  exit 1
fi

backup_prefix="${resolved_backup%.dump}"
uploads_backup="$backup_prefix.uploads.tar.gz"
checksum_file="$backup_prefix.sha256"
if [[ ! -f "$uploads_backup" || ! -f "$checksum_file" ]]; then
  echo "Matching upload archive and checksum manifest are required" >&2
  exit 1
fi
(cd "$BACKUP_DIR" && sha256sum --check "$(basename "$checksum_file")")

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
if (( restore_failed == 0 )); then
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run --rm --no-deps \
    --entrypoint sh server -c \
    'mkdir -p /var/lib/insightops/uploads && find /var/lib/insightops/uploads -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + && tar -C /var/lib/insightops/uploads -xzf -' \
    < "$uploads_backup" || restore_failed=1
fi

if (( ${#running_app_services[@]} > 0 )); then
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" start "${running_app_services[@]}"
fi
if (( restore_failed != 0 )); then
  echo "Restore failed; inspect PostgreSQL and upload-volume state before accepting service." >&2
  exit 1
fi

echo "Database and upload restore completed from $resolved_backup"
