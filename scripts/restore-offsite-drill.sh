#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMPORT_ROOT="$ROOT_DIR/recovery-imports"
PASSPHRASE_FILE="$ROOT_DIR/.secrets/offsite-backup-passphrase"
package_path="${1:-}"
confirmation="${2:-}"
marker="${3:-}"

if [[ "$confirmation" != "--confirm-isolated-recovery" ]]; then
  echo "Usage: $0 recovery-imports/<marker>/<package>.enc --confirm-isolated-recovery <marker>" >&2
  exit 1
fi
if ! [[ "$marker" =~ ^[A-Za-z0-9._-]{1,96}$ ]]; then
  echo "Recovery marker is invalid" >&2
  exit 1
fi
[[ -s "$PASSPHRASE_FILE" ]] || { echo "Offsite backup passphrase file is missing" >&2; exit 1; }

mkdir -p "$IMPORT_ROOT"
resolved_package="$(realpath "$package_path")"
resolved_root="$(realpath "$IMPORT_ROOT")"
if [[ "$resolved_package" != "$resolved_root/"* || ! -f "$resolved_package" ]]; then
  echo "Recovery package must be an existing file inside $IMPORT_ROOT" >&2
  exit 1
fi
package_name="$(basename "$resolved_package")"
base="${package_name%.tar.gz.enc}"
outer_checksum="$(dirname "$resolved_package")/$base.sha256"
metadata="$(dirname "$resolved_package")/$base.metadata"
[[ -s "$outer_checksum" && -s "$metadata" ]] || {
  echo "Imported package checksum or metadata is missing" >&2
  exit 1
}
(cd "$(dirname "$resolved_package")" && sha256sum --check "$(basename "$outer_checksum")") >/dev/null
grep -qx 'format=insightops-offsite-v1' "$metadata" || {
  echo "Unsupported offsite package format" >&2
  exit 1
}

work_dir="$(mktemp -d "/tmp/insightops-recovery-${marker}.XXXXXX")"
container_name="insightops-recovery-${marker,,}"
container_name="${container_name//./-}"
container_name="${container_name//_/-}"
container_name="${container_name:0:96}"
database_password="$(openssl rand -hex 24)"

cleanup() {
  docker rm -f "$container_name" >/dev/null 2>&1 || true
  if [[ "$work_dir" == /tmp/insightops-recovery-* && -d "$work_dir" ]]; then
    rm -rf -- "$work_dir"
  fi
}
trap cleanup EXIT

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 \
  -pass "file:$PASSPHRASE_FILE" -in "$resolved_package" -out "$work_dir/package.tar.gz"
archive_listing="$(tar -tzf "$work_dir/package.tar.gz")"
if grep -Eq '(^/|(^|/)\.\.(/|$))' <<< "$archive_listing"; then
  echo "Unsafe path found in the decrypted offsite archive" >&2
  exit 1
fi
tar -xzf "$work_dir/package.tar.gz" -C "$work_dir"
dump_file="$(find "$work_dir" -maxdepth 1 -type f -name 'insightops-*.dump' -print -quit)"
uploads_file="$(find "$work_dir" -maxdepth 1 -type f -name 'insightops-*.uploads.tar.gz' -print -quit)"
manifest_file="$(find "$work_dir" -maxdepth 1 -type f -name 'insightops-*.sha256' -print -quit)"
[[ -s "$dump_file" && -s "$uploads_file" && -s "$manifest_file" ]] || {
  echo "Decrypted package does not contain a complete backup triplet" >&2
  exit 1
}
(cd "$work_dir" && sha256sum --check "$(basename "$manifest_file")") >/dev/null

uploads_listing="$(tar -tzf "$uploads_file")"
if grep -Eq '(^/|(^|/)\.\.(/|$))' <<< "$uploads_listing"; then
  echo "Unsafe path found in the upload archive" >&2
  exit 1
fi
mkdir -p "$work_dir/uploads"
tar -xzf "$uploads_file" -C "$work_dir/uploads"

docker run -d --name "$container_name" \
  -e POSTGRES_DB=insightops_restore \
  -e POSTGRES_USER=insightops_restore \
  -e POSTGRES_PASSWORD="$database_password" \
  pgvector/pgvector:0.8.5-pg18 >/dev/null
database_ready=false
for _ in $(seq 1 60); do
  if docker exec "$container_name" pg_isready \
      -U insightops_restore -d insightops_restore >/dev/null 2>&1; then
    database_ready=true
    break
  fi
  sleep 2
done
[[ "$database_ready" == "true" ]] || { echo "Isolated recovery database did not start" >&2; exit 1; }

docker exec -i "$container_name" pg_restore \
  --no-owner --no-privileges --exit-on-error \
  --username insightops_restore --dbname insightops_restore < "$dump_file"

sql_value() {
  local sql="$1"
  docker exec -e PGPASSWORD="$database_password" "$container_name" \
    psql --username insightops_restore --dbname insightops_restore \
    --no-align --tuples-only --set ON_ERROR_STOP=1 --command "$sql"
}

flyway_version="$(sql_value "select version from flyway_schema_history where success order by installed_rank desc limit 1")"
project_count="$(sql_value "select count(*) from tracked_project where enabled=true and id between '00000000-0000-0000-0000-000000000101' and '00000000-0000-0000-0000-000000000110'")"
workspace_count="$(sql_value "select count(*) from workspace")"
user_count="$(sql_value "select count(*) from app_user")"
upload_count="$(sql_value "select count(*) from knowledge_upload")"

[[ "$flyway_version" =~ ^[0-9]+$ && "$flyway_version" -ge 38 ]] || {
  echo "Recovered Flyway version is invalid: $flyway_version" >&2
  exit 1
}
[[ "$project_count" == "10" && "$workspace_count" -ge 1 && "$user_count" -ge 1 ]] || {
  echo "Recovered database invariants failed" >&2
  exit 1
}

while IFS=$'\t' read -r storage_key expected_sha; do
  [[ -n "$storage_key" ]] || continue
  if ! [[ "$storage_key" =~ ^[0-9a-f-]{36}\.bin$ ]]; then
    echo "Recovered upload storage key is invalid" >&2
    exit 1
  fi
  restored_file="$work_dir/uploads/$storage_key"
  [[ -f "$restored_file" ]] || { echo "Recovered upload file is missing" >&2; exit 1; }
  actual_sha="$(sha256sum "$restored_file" | cut -d' ' -f1)"
  [[ "$actual_sha" == "$expected_sha" ]] || { echo "Recovered upload digest mismatch" >&2; exit 1; }
done < <(docker exec -e PGPASSWORD="$database_password" "$container_name" \
  psql --username insightops_restore --dbname insightops_restore \
  --no-align --tuples-only --field-separator=$'\t' --set ON_ERROR_STOP=1 \
  --command 'select storage_key, sha256 from knowledge_upload order by storage_key')

echo "ISOLATED_RECOVERY=PASS flyway=$flyway_version projects=$project_count workspaces=$workspace_count users=$user_count uploads=$upload_count"
