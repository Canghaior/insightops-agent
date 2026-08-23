#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
MARKER="${1:-}"
CONFIRMATION="${2:-}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ "$ROOT_DIR" != "/opt/insightops-agent" ]]; then
  echo "This production acceptance must run from /opt/insightops-agent" >&2
  exit 1
fi
if ! [[ "$MARKER" =~ ^P24B-RUN-[0-9]+-[0-9]+$ ]]; then
  echo "Marker must match P24B-RUN-<workflow-run>-<attempt>" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-read-only-template-run" || $# -ne 2 ]]; then
  echo "Refusing production acceptance without --confirm-read-only-template-run" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
RUN_TIMEOUT_SECONDS="$(prod_env_get AGENT_CHAT_QUEUE_RUN_TIMEOUT_SECONDS "$ENV_FILE")"
AUTH_USERNAME="$(prod_env_get AUTH_BOOTSTRAP_USERNAME "$ENV_FILE")"
AUTH_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-$(prod_env_get AGENT_RUN_TIMEOUT_SECONDS "$ENV_FILE")}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-90}"
AUTH_USERNAME="${AUTH_USERNAME:-alpha-owner}"

if [[ -z "$AUTH_PASSWORD" || -z "$APP_ADDRESS" ]]; then
  echo "Production authentication or application address is unavailable" >&2
  exit 1
fi
if ! [[ "$RUN_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "Run timeout must be integer seconds" >&2
  exit 1
fi

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
psql_scalar() {
  "${compose[@]}" exec -T postgres psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --tuples-only --no-align --quiet --set ON_ERROR_STOP=1 --command "$1" | tr -d '\r'
}

cookie_jar="$(mktemp)"
templates_json="$(mktemp)"
tools_json="$(mktemp)"
selection_json="$(mktemp)"
launch_json="$(mktemp)"
run_json="$(mktemp)"
workflow_json="$(mktemp)"
checkpoint_json="$(mktemp)"
cleanup() {
  curl --fail --silent --show-error --cookie "$cookie_jar" \
    --request POST "https://${APP_ADDRESS}/api/v1/auth/logout" >/dev/null 2>&1 || true
  rm -f "$cookie_jar" "$templates_json" "$tools_json" "$selection_json" \
    "$launch_json" "$run_json" "$workflow_json" "$checkpoint_json"
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
  "https://${APP_ADDRESS}/api/v1/agent-workflows" >"$templates_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/agent/tools" >"$tools_json"

python3 - "$templates_json" "$tools_json" "$selection_json" "$MARKER" <<'PY'
import json
import sys

templates_path, tools_path, selection_path, marker = sys.argv[1:]
with open(templates_path, encoding="utf-8") as source:
    templates = json.load(source).get("data") or []
with open(tools_path, encoding="utf-8") as source:
    tools = json.load(source).get("data") or []

risk = {item["name"]: item["riskLevel"] for item in tools}
candidates = []
for template in templates:
    graph = json.loads(template["graphSpecJson"])
    nodes = graph.get("nodes") or []
    if not nodes:
        continue
    tool_names = [node.get("toolName") for node in nodes]
    if all(risk.get(name) == "READ_ONLY" for name in tool_names):
        candidates.append((len(nodes), template.get("name", ""), template, graph))
if not candidates:
    raise SystemExit("No active template contains only READ_ONLY tools")

_, _, template, graph = sorted(candidates, key=lambda item: (item[0], item[1]))[0]
inputs = {}
for name, definition in (template.get("inputs") or {}).items():
    default = definition.get("defaultValue")
    if default is not None:
        inputs[name] = default
        continue
    if not definition.get("required"):
        continue
    input_type = definition.get("type")
    if input_type == "string":
        value = f"{marker} Spring AI Agent workflow production acceptance"
        maximum = definition.get("maxLength")
        inputs[name] = value if maximum is None else value[:maximum]
    elif input_type == "integer":
        minimum = definition.get("minimum")
        inputs[name] = 0 if minimum is None else minimum
    elif input_type == "boolean":
        inputs[name] = False
    elif input_type == "string_array":
        inputs[name] = [marker]
    elif input_type == "json":
        inputs[name] = {}
    elif input_type == "json_array":
        inputs[name] = []
    else:
        raise SystemExit(f"Unsupported required input type: {input_type}")

selection = {
    "templateId": template["id"],
    "templateName": template["name"],
    "versionId": template["activeVersionId"],
    "version": template["version"],
    "graph": graph,
    "inputs": inputs,
    "toolRisk": {name: risk[name] for name in {node["toolName"] for node in graph["nodes"]}},
}
with open(selection_path, "w", encoding="utf-8") as target:
    json.dump(selection, target, ensure_ascii=False)
print(f"Selected READ_ONLY template {selection['templateName']} v{selection['version']} with {len(graph['nodes'])} nodes")
PY

template_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["templateId"])' "$selection_json")"
version_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionId"])' "$selection_json")"
request_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
launch_body="$(python3 - "$selection_json" "$version_id" "$request_id" <<'PY'
import json
import sys
selection = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps({
    "expectedVersionId": sys.argv[2],
    "sessionId": None,
    "requestId": sys.argv[3],
    "inputs": selection["inputs"],
}, ensure_ascii=False))
PY
)"
printf '%s' "$launch_body" | \
  curl --fail --silent --show-error --cookie "$cookie_jar" \
    --header 'Content-Type: application/json' --header "X-Trace-Id: ${MARKER}" \
    --data-binary @- "https://${APP_ADDRESS}/api/v1/agent-workflows/${template_id}/runs" \
    >"$launch_json"
unset launch_body

run_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["runId"])' "$launch_json")"
if ! [[ "$run_id" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "Launch did not return a valid Run ID" >&2
  exit 1
fi
echo "Started production workflow Run $run_id for marker $MARKER"

deadline=$((SECONDS + RUN_TIMEOUT_SECONDS + 240))
last_status=""
while (( SECONDS < deadline )); do
  curl --fail --silent --show-error --cookie "$cookie_jar" \
    "https://${APP_ADDRESS}/api/v1/runs/${run_id}" >"$run_json"
  status="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["status"])' "$run_json")"
  if [[ "$status" != "$last_status" ]]; then
    echo "Run $run_id status=$status"
    last_status="$status"
  fi
  case "$status" in
    SUCCEEDED) break ;;
    FAILED|CANCELLED|PAUSED)
      failure="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"].get("failureCode") or "")' "$run_json")"
      echo "Workflow Run ended as $status failure=${failure:-none}" >&2
      exit 1
      ;;
  esac
  sleep 2
done
if [[ "$last_status" != "SUCCEEDED" ]]; then
  echo "Workflow Run did not succeed before timeout" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/agent-workflows/runs/${run_id}" >"$workflow_json"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "https://${APP_ADDRESS}/api/v1/runs/${run_id}/checkpoint" >"$checkpoint_json"

python3 - "$selection_json" "$workflow_json" "$checkpoint_json" "$run_id" <<'PY'
import json
import re
import sys

selection_path, workflow_path, checkpoint_path, run_id = sys.argv[1:]
selection = json.load(open(selection_path, encoding="utf-8"))
workflow = json.load(open(workflow_path, encoding="utf-8"))["data"]
checkpoint = json.load(open(checkpoint_path, encoding="utf-8"))["data"]

assert workflow["runId"] == run_id
assert workflow["templateId"] == selection["templateId"]
assert workflow["templateVersionId"] == selection["versionId"]
assert workflow["templateName"] == selection["templateName"]
assert workflow["templateVersion"] == selection["version"]
assert workflow["graphSpec"] == selection["graph"]
assert workflow["inputs"] == selection["inputs"]
assert re.fullmatch(r"[0-9a-f]{64}", workflow["toolContractFingerprint"])

nodes = workflow["nodes"]
assert len(nodes) == len(selection["graph"]["nodes"])
assert any(node["status"] == "SUCCEEDED" for node in nodes)
for node in nodes:
    assert node["riskLevel"] == "READ_ONLY"
    assert node["status"] in {"SUCCEEDED", "SKIPPED"}
    assert node["inputTokens"] >= 0 and node["outputTokens"] >= 0
    assert node["estimatedCostCny"] >= 0
    if node["status"] == "SUCCEEDED":
        assert node["attemptCount"] >= 1
        assert node["resolvedInput"] is not None
        assert node["output"] is not None
        assert node["toolCallId"] and node["planNodeId"]
        assert node["attempts"]
        assert node["attempts"][-1]["status"] == "SUCCEEDED"

assert checkpoint["reason"] == "WORKFLOW_WAVE_COMPLETED"
assert checkpoint["status"] == "AVAILABLE"
print(f"Validated immutable snapshot, {len(nodes)} durable nodes and checkpoint {checkpoint['id']}")
PY

cost_deadline=$((SECONDS + 30))
cost_summary=""
while (( SECONDS < cost_deadline )); do
  cost_summary="$(psql_scalar "
    select r.status || '|' ||
      (select count(*) from agent_cost_ledger l where l.run_id = r.run_id and l.entry_type = 'RESERVE') || '|' ||
      (select count(*) from agent_cost_ledger l where l.run_id = r.run_id and l.entry_type = 'SETTLE')
    from agent_cost_reservation r where r.run_id = '${run_id}'::uuid;")"
  cost_summary="$(printf '%s' "$cost_summary" | tr -d '[:space:]')"
  [[ "$cost_summary" == "SETTLED|1|1" ]] && break
  sleep 1
done
if [[ "$cost_summary" != "SETTLED|1|1" ]]; then
  echo "Unexpected cost settlement summary: ${cost_summary:-missing}" >&2
  exit 1
fi

db_summary="$(psql_scalar "
  select count(*) || '|' ||
    count(*) filter (where status = 'SUCCEEDED') || '|' ||
    coalesce(sum(attempt_count), 0)
  from agent_workflow_run_node where run_id = '${run_id}'::uuid;")"
db_summary="$(printf '%s' "$db_summary" | tr -d '[:space:]')"
echo "Database node summary total|succeeded|attempts=${db_summary}"
echo "Cost settlement status|reserve_entries|settle_entries=${cost_summary}"
echo "P2.4-B production READ_ONLY workflow acceptance completed for marker $MARKER and Run $run_id"
