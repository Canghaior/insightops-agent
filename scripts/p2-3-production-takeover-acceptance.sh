#!/usr/bin/env bash
set -euo pipefail

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
if ! [[ "$MARKER" =~ ^P23-TAKEOVER-[0-9]+-[0-9]+$ ]]; then
  echo "Marker must match P23-TAKEOVER-<workflow-run>-<attempt>" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-production-restart" || $# -ne 2 ]]; then
  echo "Refusing production acceptance without --confirm-production-restart" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
LEASE_SECONDS="$(prod_env_get AGENT_CHAT_QUEUE_LEASE_SECONDS "$ENV_FILE")"
HEARTBEAT_SECONDS="$(prod_env_get AGENT_CHAT_QUEUE_HEARTBEAT_SECONDS "$ENV_FILE")"
RUN_TIMEOUT_SECONDS="$(prod_env_get AGENT_CHAT_QUEUE_RUN_TIMEOUT_SECONDS "$ENV_FILE")"
AUTH_USERNAME="$(prod_env_get AUTH_BOOTSTRAP_USERNAME "$ENV_FILE")"
AUTH_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"
LEASE_SECONDS="${LEASE_SECONDS:-30}"
HEARTBEAT_SECONDS="${HEARTBEAT_SECONDS:-5}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-$(prod_env_get AGENT_RUN_TIMEOUT_SECONDS "$ENV_FILE")}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-90}"
AUTH_USERNAME="${AUTH_USERNAME:-alpha-owner}"
if ! [[ "$LEASE_SECONDS" =~ ^[0-9]+$ && "$HEARTBEAT_SECONDS" =~ ^[0-9]+$ \
    && "$RUN_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "Lease, heartbeat and run timeout settings must be integer seconds" >&2
  exit 1
fi
if (( LEASE_SECONDS >= RUN_TIMEOUT_SECONDS )); then
  echo "Chat lease must be shorter than the total run timeout; refusing fault injection" >&2
  exit 1
fi
if [[ -z "$AUTH_PASSWORD" || -z "$APP_ADDRESS" ]]; then
  echo "Production acceptance login or APP_ADDRESS is unavailable" >&2
  exit 1
fi
echo "Effective chat queue: lease=${LEASE_SECONDS}s heartbeat=${HEARTBEAT_SECONDS}s timeout=${RUN_TIMEOUT_SECONDS}s"

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
psql_scalar() {
  "${compose[@]}" exec -T postgres psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --tuples-only --no-align --quiet --set ON_ERROR_STOP=1 --command "$1" | tr -d '\r'
}
cookie_jar="$(mktemp)"
sse_output="$(mktemp)"
curl_pid=""
cleanup() {
  if [[ -n "$curl_pid" ]] && kill -0 "$curl_pid" 2>/dev/null; then
    kill "$curl_pid" 2>/dev/null || true
    wait "$curl_pid" 2>/dev/null || true
  fi
  curl --fail --silent --show-error --cookie "$cookie_jar" \
    --request POST "https://${APP_ADDRESS}/api/v1/auth/logout" >/dev/null 2>&1 || true
  rm -f "$cookie_jar" "$sse_output"
}
trap cleanup EXIT

login_body="$(AUTH_USERNAME="$AUTH_USERNAME" AUTH_PASSWORD="$AUTH_PASSWORD" python3 -c \
  'import json,os; print(json.dumps({"username":os.environ["AUTH_USERNAME"],"password":os.environ["AUTH_PASSWORD"]}))')"
printf '%s' "$login_body" | \
  curl --fail --silent --show-error --cookie-jar "$cookie_jar" \
    --header 'Content-Type: application/json' --data-binary @- \
    "https://${APP_ADDRESS}/api/v1/auth/login" >/dev/null
unset AUTH_PASSWORD login_body

chat_message="${MARKER}：分别检索 Spring AI、LangChain4j 和 Dify 的 Agent 工具调用机制，比较执行流程与失败恢复，并为每个框架给出官方来源。"
chat_body="$(CHAT_MESSAGE="$chat_message" python3 -c \
  'import json,os; print(json.dumps({"message":os.environ["CHAT_MESSAGE"],"sessionId":None,"resumeCheckpointId":None}))')"
curl --fail --silent --show-error --no-buffer --max-time "$((RUN_TIMEOUT_SECONDS + 240))" \
  --cookie "$cookie_jar" --header 'Accept: text/event-stream' \
  --header 'Content-Type: application/json' --header "X-Trace-Id: ${MARKER}" \
  --data-binary "$chat_body" "https://${APP_ADDRESS}/api/v1/chat/streams" >"$sse_output" &
curl_pid=$!
unset chat_body

deadline=$((SECONDS + 150))
run_id=""
while (( SECONDS < deadline )); do
  candidates="$(psql_scalar "
    select w.run_id
    from agent_run_work w
    where w.status = 'RUNNING'
      and w.created_at >= now() - interval '10 minutes'
      and w.user_prompt like '%${MARKER}%'
      and w.claimed_by is not null
      and exists (
        select 1 from agent_plan_checkpoint c
        where c.run_id = w.run_id and c.status = 'AVAILABLE'
      )
      and exists (
        select 1 from agent_cost_reservation r
        where r.run_id = w.run_id and r.status = 'RESERVED'
      )
    order by w.created_at desc;")"
  candidate_count="$(printf '%s\n' "$candidates" | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ')"
  if (( candidate_count > 1 )); then
    echo "Multiple eligible Runs matched the unique marker; refusing fault injection" >&2
    exit 1
  fi
  if (( candidate_count == 1 )); then
    run_id="$(printf '%s' "$candidates" | tr -d '[:space:]')"
    break
  fi
  if ! kill -0 "$curl_pid" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
if [[ -z "$run_id" ]]; then
  summary="$(psql_scalar "select id || '|' || status || '|' || coalesce(failure_code, '') from agent_run where question like '%${MARKER}%' order by created_at desc limit 1;")"
  echo "No eligible safe-point Run found for ${MARKER}; latest=${summary:-missing}" >&2
  exit 1
fi
echo "Selected guarded test Run $run_id for marker $MARKER"
bash "$ROOT_DIR/scripts/p2-3-chat-takeover-drill.sh" \
  "$run_id" --confirm-production-restart
wait "$curl_pid" 2>/dev/null || true
curl_pid=""
echo "P2.3-C production acceptance completed for marker $MARKER and Run $run_id"
