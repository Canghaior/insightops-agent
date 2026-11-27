#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "${1:-}" in
  "") profile=prod ;;
  --tiny) profile=tiny ;;
  *) echo "Usage: $0 [--tiny]" >&2; exit 2 ;;
esac
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.$profile}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.$profile.yml"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

bash "$ROOT_DIR/scripts/preflight-$profile.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
BACKUP_RETENTION_DAYS="$(prod_env_get BACKUP_RETENTION_DAYS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
umask 077
BACKUP_DIR="$(realpath "$BACKUP_DIR")"
exec 9>"$BACKUP_DIR/.snapshot.lock"
flock -n 9 || { echo "Another snapshot is in progress" >&2; exit 1; }
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$BACKUP_DIR/insightops-$timestamp.dump"
temporary="$target.partial"
uploads_target="$BACKUP_DIR/insightops-$timestamp.uploads.tar.gz"
uploads_temporary="$uploads_target.partial"
checksum_target="$BACKUP_DIR/insightops-$timestamp.sha256"
[[ ! -e "$target" && ! -e "$uploads_target" && ! -e "$checksum_target" ]] || {
  echo "A snapshot already exists at this timestamp" >&2; exit 1;
}
running_app_container_ids=()

cleanup() {
  rm -f -- "$temporary" "$uploads_temporary"
  if (( ${#running_app_container_ids[@]} > 0 )); then
    docker start "${running_app_container_ids[@]}" >/dev/null || true
  fi
}
trap cleanup EXIT

for service in server worker; do
  container_id="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --status running -q "$service")"
  if [[ -n "$container_id" ]]; then
    running_app_container_ids+=("$container_id")
  fi
done
if (( ${#running_app_container_ids[@]} > 0 )); then
  echo "Stopping application containers for a consistent database and upload snapshot..."
  docker stop "${running_app_container_ids[@]}"
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump --format=custom --compress=9 --no-owner --no-privileges \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" > "$temporary"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run --rm --no-deps \
  --entrypoint sh uploads-init -c 'mkdir -p /var/lib/insightops/uploads && tar -C /var/lib/insightops/uploads -czf - .' \
  > "$uploads_temporary"

test -s "$temporary"
test -s "$uploads_temporary"
mv -- "$temporary" "$target"
mv -- "$uploads_temporary" "$uploads_target"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$target")" "$(basename "$uploads_target")") > "$checksum_target"
chmod 600 "$target" "$uploads_target" "$checksum_target"
if (( ${#running_app_container_ids[@]} > 0 )); then
  docker start "${running_app_container_ids[@]}"
  running_app_container_ids=()
fi

retention_days="${BACKUP_RETENTION_DAYS:-30}"
if ! [[ "$retention_days" =~ ^[0-9]+$ ]] || (( retention_days < 7 )); then
  echo "BACKUP_RETENTION_DAYS must be an integer of at least 7" >&2
  exit 1
fi
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'insightops-*.dump' -o -name 'insightops-*.uploads.tar.gz' \
     -o -name 'insightops-*.sha256' \) \
  -mtime "+$retention_days" -delete

trap - EXIT
echo "Backup created: $target + $uploads_target"
