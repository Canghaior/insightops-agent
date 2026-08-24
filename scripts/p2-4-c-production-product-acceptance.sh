#!/usr/bin/env bash
set -euo pipefail

umask 077
curl() {
  command curl --header 'X-InsightOps-CSRF: 1' "$@"
}


ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
MARKER="${1:-}"
CONFIRMATION="${2:-}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ "$ROOT_DIR" != "/opt/insightops-agent" ]]; then
  echo "This production acceptance must run from /opt/insightops-agent" >&2
  exit 1
fi
if ! [[ "$MARKER" =~ ^P24C-PRODUCT-[0-9]+-[0-9]+$ ]]; then
  echo "Marker must match P24C-PRODUCT-<workflow-run>-<attempt>" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-product-acceptance" || $# -ne 2 ]]; then
  echo "Refusing P2.4-C production acceptance without explicit confirmation" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
AUTH_USERNAME="$(prod_env_get AUTH_BOOTSTRAP_USERNAME "$ENV_FILE")"
SERVER_AUTH_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
AUTH_USERNAME="${AUTH_USERNAME:-alpha-owner}"
STDIN_AUTH_PASSWORD=""
if [[ ! -t 0 ]]; then
  IFS= read -r STDIN_AUTH_PASSWORD || true
fi
AUTH_PASSWORD="${STDIN_AUTH_PASSWORD:-$SERVER_AUTH_PASSWORD}"
unset STDIN_AUTH_PASSWORD SERVER_AUTH_PASSWORD
if [[ -z "$AUTH_PASSWORD" || -z "$APP_ADDRESS" ]]; then
  echo "Production authentication is unavailable" >&2
  exit 1
fi

api="https://${APP_ADDRESS}/api/v1"
tmp_dir="$(mktemp -d)"
cookie_jar="$tmp_dir/cookies"
preset_id=""
share_id=""
cleanup() {
  if [[ -n "$share_id" ]]; then
    curl --silent --show-error --cookie "$cookie_jar" --request DELETE \
      "$api/admin/agent-workflow-products/shares/$share_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$preset_id" ]]; then
    curl --silent --show-error --cookie "$cookie_jar" --request DELETE \
      "$api/agent-workflow-presets/$preset_id" >/dev/null 2>&1 || true
  fi
  curl --silent --show-error --cookie "$cookie_jar" --request POST \
    "$api/auth/logout" >/dev/null 2>&1 || true
  case "$tmp_dir" in
    /tmp/tmp.*) rm -rf -- "$tmp_dir" ;;
    *) echo "Refusing to remove unexpected temporary directory: $tmp_dir" >&2 ;;
  esac
}
trap cleanup EXIT

login_body="$(AUTH_USERNAME="$AUTH_USERNAME" AUTH_PASSWORD="$AUTH_PASSWORD" python3 -c \
  'import json,os; print(json.dumps({"username":os.environ["AUTH_USERNAME"],"password":os.environ["AUTH_PASSWORD"]}))')"
printf '%s' "$login_body" | curl --fail --silent --show-error --cookie-jar "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @- "$api/auth/login" >/dev/null
unset AUTH_PASSWORD login_body

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/admin/agent-workflows" >"$tmp_dir/overview.json"

python3 - "$tmp_dir/overview.json" "$tmp_dir/meta.json" "$tmp_dir/preset-request.json" "$MARKER" <<'PY'
import json
import sys

overview_path, meta_path, preset_path, marker = sys.argv[1:]
overview = json.load(open(overview_path, encoding="utf-8"))["data"]
template = next(item for item in overview["templates"] if item.get("activeVersionId"))
version = next(item for item in template["versions"] if item["id"] == template["activeVersionId"])
graph = json.loads(version["graphSpecJson"])

values = {}
for name, definition in graph.get("inputs", {}).items():
    if definition.get("default") is not None:
        values[name] = definition["default"]
    elif definition.get("type") == "boolean":
        values[name] = False
    elif definition.get("type") == "integer":
        values[name] = max(1, int(definition.get("minimum", 1)))
    elif definition.get("type") == "number":
        values[name] = float(definition.get("minimum", 1))
    else:
        values[name] = marker

meta = {
    "templateId": template["id"],
    "versionId": version["id"],
    "templateName": template["name"],
}
json.dump(meta, open(meta_path, "w", encoding="utf-8"))
json.dump({
    "templateId": template["id"],
    "versionId": version["id"],
    "name": marker,
    "values": values,
}, open(preset_path, "w", encoding="utf-8"))
PY

template_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["templateId"])' "$tmp_dir/meta.json")"
version_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionId"])' "$tmp_dir/meta.json")"

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/admin/agent-workflow-products/templates/$template_id/versions/$version_id/export" \
  >"$tmp_dir/export.json"

curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @"$tmp_dir/preset-request.json" \
  "$api/agent-workflow-presets" >"$tmp_dir/preset.json"
preset_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "$tmp_dir/preset.json")"

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/agent-workflow-presets?templateId=$template_id&versionId=$version_id" \
  >"$tmp_dir/presets-before-delete.json"
curl --fail --silent --show-error --cookie "$cookie_jar" --request DELETE \
  "$api/agent-workflow-presets/$preset_id" >"$tmp_dir/preset-delete.json"

printf '%s' '{"expiresInDays":1}' | curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @- \
  "$api/admin/agent-workflow-products/templates/$template_id/versions/$version_id/shares" \
  >"$tmp_dir/share.json"
share_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["share"]["id"])' "$tmp_dir/share.json")"

python3 - "$tmp_dir/share.json" "$tmp_dir/preview-request.json" "$tmp_dir/import-request.json" "$MARKER" <<'PY'
import json
import sys

share_path, preview_path, import_path, marker = sys.argv[1:]
token = json.load(open(share_path, encoding="utf-8"))["data"]["token"]
json.dump({"token": token}, open(preview_path, "w", encoding="utf-8"))
json.dump({"token": token, "name": marker + "-IMPORT"},
          open(import_path, "w", encoding="utf-8"))
PY

curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @"$tmp_dir/preview-request.json" \
  "$api/admin/agent-workflow-products/shared/preview" >"$tmp_dir/preview.json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @"$tmp_dir/import-request.json" \
  "$api/admin/agent-workflow-products/shared/imports" >"$tmp_dir/import.json"

curl --fail --silent --show-error --cookie "$cookie_jar" --request DELETE \
  "$api/admin/agent-workflow-products/shares/$share_id" >"$tmp_dir/share-revoke.json"
share_id=""

revoked_status="$(curl --silent --show-error --output "$tmp_dir/revoked-preview.json" \
  --write-out '%{http_code}' --cookie "$cookie_jar" --header 'Content-Type: application/json' \
  --data-binary @"$tmp_dir/preview-request.json" \
  "$api/admin/agent-workflow-products/shared/preview")"
if [[ "$revoked_status" != "400" ]]; then
  echo "Revoked share preview returned HTTP $revoked_status instead of 400" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/admin/agent-workflow-products/templates/$template_id/shares" >"$tmp_dir/shares.json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/admin/agent-workflow-products/templates/$template_id/analytics?days=30" \
  >"$tmp_dir/analytics.json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/agent-workflow-presets?templateId=$template_id&versionId=$version_id" \
  >"$tmp_dir/presets-after-delete.json"

python3 - "$tmp_dir" "$preset_id" "$share_id" "$MARKER" <<'PY'
import json
import os
import sys

folder, preset_id, _cleared_share_id, marker = sys.argv[1:]
load = lambda name: json.load(open(os.path.join(folder, name), encoding="utf-8"))["data"]
meta = json.load(open(os.path.join(folder, "meta.json"), encoding="utf-8"))
bundle = load("export.json")
preset = load("preset.json")
before = load("presets-before-delete.json")
after = load("presets-after-delete.json")
share_created = load("share.json")
preview = load("preview.json")
imported = load("import.json")
shares = load("shares.json")
analytics = load("analytics.json")

assert bundle["schemaVersion"] == 1
assert bundle["version"]["graph"]["nodes"]
assert preset["id"] == preset_id
assert any(item["id"] == preset_id for item in before)
assert all(item["id"] != preset_id for item in after)
share_id = share_created["share"]["id"]
raw_token = share_created["token"]
assert len(raw_token) >= 40
assert preview["share"]["id"] == share_id
assert preview["bundle"]["schemaVersion"] == 1
assert imported["name"] == marker + "-IMPORT"
assert imported["status"] == "ACTIVE"
assert imported.get("activeVersionId") is None
assert imported["versions"][0]["status"] == "DRAFT"
audit = next(item for item in shares if item["id"] == share_id)
assert audit["status"] == "REVOKED"
assert audit["importCount"] == 1
assert raw_token not in json.dumps(shares)
assert analytics["windowDays"] == 30
assert "runCount" in analytics["summary"]
print(
    f"P2.4-C production acceptance passed: source={meta['templateName']} "
    f"template={meta['templateId']} version={meta['versionId']} "
    f"preset={preset_id} share={share_id} imported={imported['id']}"
)
PY

preset_id=""
echo "P2.4-C product production acceptance completed"
