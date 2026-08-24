#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
RUN_ID="${1:-}"
MARKER="${2:-}"
CONFIRMATION="${3:-}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ "$ROOT_DIR" != "/opt/insightops-agent" ]]; then
  echo "This production verification must run from /opt/insightops-agent" >&2
  exit 1
fi
if ! [[ "$RUN_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Existing Run ID must be a UUID" >&2
  exit 1
fi
if ! [[ "$MARKER" =~ ^P24B-RUN-[0-9]+-[0-9]+$ ]]; then
  echo "Marker must match P24B-RUN-<workflow-run>-<attempt>" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-existing-read-only-run" || $# -ne 3 ]]; then
  echo "Refusing existing Run verification without explicit confirmation" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
AUTH_USERNAME="$(prod_env_get AUTH_BOOTSTRAP_USERNAME "$ENV_FILE")"
SERVER_AUTH_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"
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

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
psql_scalar() {
  "${compose[@]}" exec -T postgres psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --tuples-only --no-align --quiet --set ON_ERROR_STOP=1 --command "$1" | tr -d '\r'
}

cookie_jar="$(mktemp)"
run_json="$(mktemp)"
workflow_json="$(mktemp)"
checkpoint_json="$(mktemp)"
admin_json="$(mktemp)"
tools_json="$(mktemp)"
verification_json="$(mktemp)"
cleanup() {
  curl --fail --silent --show-error --cookie "$cookie_jar" \
    --request POST "https://${APP_ADDRESS}/api/v1/auth/logout" >/dev/null 2>&1 || true
  rm -f "$cookie_jar" "$run_json" "$workflow_json" "$checkpoint_json" \
    "$admin_json" "$tools_json" "$verification_json"
}
trap cleanup EXIT

login_body="$(AUTH_USERNAME="$AUTH_USERNAME" AUTH_PASSWORD="$AUTH_PASSWORD" python3 -c \
  'import json,os; print(json.dumps({"username":os.environ["AUTH_USERNAME"],"password":os.environ["AUTH_PASSWORD"]}))')"
printf '%s' "$login_body" | \
  curl --fail --silent --show-error --cookie-jar "$cookie_jar" \
    --header 'Content-Type: application/json' --data-binary @- \
    "https://${APP_ADDRESS}/api/v1/auth/login" >/dev/null
unset AUTH_PASSWORD login_body

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/runs/${RUN_ID}" >"$run_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/agent-workflows/runs/${RUN_ID}" >"$workflow_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/runs/${RUN_ID}/checkpoint" >"$checkpoint_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/admin/agent-workflows" >"$admin_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/agent/tools" >"$tools_json"

python3 - "$run_json" "$workflow_json" "$checkpoint_json" "$admin_json" \
  "$tools_json" "$verification_json" "$RUN_ID" "$MARKER" <<'PY'
import difflib
import hashlib
import json
import re
import sys

run_path, workflow_path, checkpoint_path, admin_path, tools_path, result_path, run_id, marker = sys.argv[1:]
run = json.load(open(run_path, encoding="utf-8"))["data"]
workflow = json.load(open(workflow_path, encoding="utf-8"))["data"]
checkpoint = json.load(open(checkpoint_path, encoding="utf-8"))["data"]
overview = json.load(open(admin_path, encoding="utf-8"))["data"]
tools = json.load(open(tools_path, encoding="utf-8"))["data"]

assert run["status"] == "SUCCEEDED"
assert workflow["runId"] == run_id
template = next(item for item in overview["templates"] if item["id"] == workflow["templateId"])
version = next(item for item in template["versions"] if item["id"] == workflow["templateVersionId"])
immutable_graph = json.loads(version["graphSpecJson"])
snapshot_graph = workflow["graphSpec"]

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

immutable_text = canonical(immutable_graph)
snapshot_text = canonical(snapshot_graph)
snapshot_equal = immutable_text == snapshot_text
print(
    "Graph snapshot sha256 immutable|run="
    + hashlib.sha256(immutable_text.encode()).hexdigest()
    + "|"
    + hashlib.sha256(snapshot_text.encode()).hexdigest()
)
if not snapshot_equal:
    print("Graph snapshot structural diff follows:")
    immutable_pretty = json.dumps(immutable_graph, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
    snapshot_pretty = json.dumps(snapshot_graph, ensure_ascii=False, sort_keys=True, indent=2).splitlines()
    for line in list(difflib.unified_diff(
            immutable_pretty, snapshot_pretty, fromfile="immutable-version", tofile="run-snapshot"))[:160]:
        print(line)

assert workflow["templateName"] == template["name"]
assert workflow["templateVersion"] == version["version"]
assert marker in json.dumps(workflow["inputs"], ensure_ascii=False)
assert re.fullmatch(r"[0-9a-f]{64}", workflow["toolContractFingerprint"])
risk = {item["name"]: item["riskLevel"] for item in tools}
nodes = workflow["nodes"]
assert len(nodes) == len(snapshot_graph["nodes"])
for node in nodes:
    assert risk[node["toolName"]] == "READ_ONLY"
    assert node["riskLevel"] == "READ_ONLY"
    assert node["status"] in {"SUCCEEDED", "SKIPPED"}
    if node["status"] == "SUCCEEDED":
        assert node["attemptCount"] >= 1
        assert node["resolvedInput"] is not None
        assert node["output"] is not None
        assert node["toolCallId"] and node["planNodeId"]
        assert node["attempts"] and node["attempts"][-1]["status"] == "SUCCEEDED"
assert checkpoint["reason"] == "WORKFLOW_WAVE_COMPLETED"
assert checkpoint["status"] == "AVAILABLE"

result = {
    "snapshotGraphEqual": snapshot_equal,
    "templateName": template["name"],
    "templateVersion": version["version"],
    "nodeCount": len(nodes),
    "checkpointId": checkpoint["id"],
}
with open(result_path, "w", encoding="utf-8") as target:
    json.dump(result, target, ensure_ascii=False)
print(
    f"Verified existing Run {run_id}: template={template['name']} v{version['version']} "
    f"nodes={len(nodes)} checkpoint={checkpoint['id']}"
)
PY

cost_summary="$(psql_scalar "
  select r.status || '|' ||
    (select count(*) from agent_cost_ledger l where l.run_id = r.run_id and l.entry_type = 'RESERVE') || '|' ||
    (select count(*) from agent_cost_ledger l where l.run_id = r.run_id and l.entry_type = 'SETTLE')
  from agent_cost_reservation r where r.run_id = '${RUN_ID}'::uuid;")"
cost_summary="$(printf '%s' "$cost_summary" | tr -d '[:space:]')"
if [[ "$cost_summary" != "SETTLED|1|1" ]]; then
  echo "Unexpected cost settlement summary: ${cost_summary:-missing}" >&2
  exit 1
fi

db_summary="$(psql_scalar "
  select count(*) || '|' ||
    count(*) filter (where status = 'SUCCEEDED') || '|' ||
    coalesce(sum(attempt_count), 0)
  from agent_workflow_run_node where run_id = '${RUN_ID}'::uuid;")"
db_summary="$(printf '%s' "$db_summary" | tr -d '[:space:]')"
snapshot_equal="$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["snapshotGraphEqual"]).lower())' "$verification_json")"
echo "Database node summary total|succeeded|attempts=${db_summary}"
echo "Cost settlement status|reserve_entries|settle_entries=${cost_summary}"
if [[ "$snapshot_equal" != "true" ]]; then
  echo "Run graph snapshot differs from its immutable template version" >&2
  exit 1
fi
echo "P2.4-B existing production READ_ONLY Run verification completed for $RUN_ID"
