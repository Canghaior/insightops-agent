#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$ROOT_DIR/backups"
OFFSITE_DIR="$BACKUP_DIR/offsite"
PASSPHRASE_FILE="$ROOT_DIR/.secrets/offsite-backup-passphrase"
marker="${1:-manual}"

if ! [[ "$marker" =~ ^[A-Za-z0-9._-]{1,96}$ ]]; then
  echo "Backup marker must use only letters, digits, dot, underscore or dash" >&2
  exit 1
fi
if [[ ! -s "$PASSPHRASE_FILE" ]]; then
  echo "Offsite backup passphrase file is missing" >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

bash "$ROOT_DIR/scripts/backup-prod.sh"
source_dump="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'insightops-*.dump' \
  -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)"
if [[ -z "$source_dump" || ! -s "$source_dump" ]]; then
  echo "No production database backup was created" >&2
  exit 1
fi
source_prefix="${source_dump%.dump}"
source_uploads="$source_prefix.uploads.tar.gz"
source_manifest="$source_prefix.sha256"
[[ -s "$source_uploads" && -s "$source_manifest" ]] || {
  echo "The newest production backup is incomplete" >&2
  exit 1
}
(cd "$BACKUP_DIR" && sha256sum --check "$(basename "$source_manifest")") >/dev/null

install -m 700 -d "$OFFSITE_DIR"
staging="$(mktemp -d "$BACKUP_DIR/.offsite-stage.XXXXXX")"
cleanup() {
  if [[ "$staging" == "$BACKUP_DIR/".offsite-stage.* && -d "$staging" ]]; then
    rm -rf -- "$staging"
  fi
}
trap cleanup EXIT

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="insightops-offsite-$timestamp-$marker"
package="$OFFSITE_DIR/$base.tar.gz.enc"
package_partial="$package.partial"
outer_checksum="$OFFSITE_DIR/$base.sha256"
metadata="$OFFSITE_DIR/$base.metadata"
dump_name="$(basename "$source_dump")"
uploads_name="$(basename "$source_uploads")"
portable_manifest="${dump_name%.dump}.sha256"

cp -- "$source_dump" "$staging/$dump_name"
cp -- "$source_uploads" "$staging/$uploads_name"
(cd "$staging" && sha256sum "$dump_name" "$uploads_name" > "$portable_manifest")

tar -C "$staging" -czf - "$dump_name" "$uploads_name" "$portable_manifest" \
  | openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -md sha256 \
      -pass "file:$PASSPHRASE_FILE" -out "$package_partial"
test -s "$package_partial"
mv -- "$package_partial" "$package"
(cd "$OFFSITE_DIR" && sha256sum "$(basename "$package")" > "$(basename "$outer_checksum")")
cat > "$metadata" <<EOF
format=insightops-offsite-v1
created_at=$timestamp
marker=$marker
cipher=aes-256-cbc-pbkdf2-sha256-iter200000
source_dump=$dump_name
source_uploads=$uploads_name
EOF
chmod 600 "$package" "$outer_checksum" "$metadata"

find "$OFFSITE_DIR" -maxdepth 1 -type f \
  \( -name 'insightops-offsite-*.tar.gz.enc' -o -name 'insightops-offsite-*.sha256' \
     -o -name 'insightops-offsite-*.metadata' \) -mtime +30 -delete

trap - EXIT
cleanup
echo "OFFSITE_PACKAGE=$package"
