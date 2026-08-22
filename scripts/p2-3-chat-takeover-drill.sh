#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
RUN_ID="${1:-}"
CONFIRMATION="${2:-}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ "$ROOT_DIR" != "/opt/insightops-agent" ]]; then
  echo "This production drill must run from /opt/insightops-agent" >&2
  exit 1
fi
if ! [[ "$RUN_ID" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
  echo "Usage: $0 <running-agent-run-uuid> --confirm-production-restart" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-production-restart" || $# -ne 2 ]]; then
  echo "Refusing fault injection without --confirm-production-restart" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
LEASE_SECONDS="$(prod_env_get AGENT_CHAT_QUEUE_LEASE_SECONDS "$ENV_FILE")"
RUN_TIMEOUT_SECONDS="$(prod_env_get AGENT_RUN_TIMEOUT_SECONDS "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"
LEASE_SECONDS="${LEASE_SECONDS:-120}"
RUN_TIMEOUT_SECONDS="${RUN_TIMEOUT_SECONDS:-90}"
if ! [[ "$LEASE_SECONDS" =~ ^[0-9]+$ && "$RUN_TIMEOUT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "Lease and run timeout settings must be integer seconds" >&2
  exit 1
fi

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
psql_scalar() {
  "${compose[@]}" exec -T postgres psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --tuples-only --no-align --quiet --set ON_ERROR_STOP=1 --command "$1" | tr -d '\r'
}
wait_for_server() {
  local container_id status
  for _ in $(seq 1 60); do
    container_id="$("${compose[@]}" ps -q server)"
    if [[ -n "$container_id" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
      [[ "$status" == "healthy" ]] && return 0
      [[ "$status" == "unhealthy" || "$status" == "exited" ]] && return 1
    fi
    sleep 5
  done
  return 1
}
work_row() {
  psql_scalar "select status || '|' || attempt_count || '|' || coalesce(claimed_by, '') from agent_run_work where run_id = '$RUN_ID'::uuid;"
}
event_count() {
  psql_scalar "select count(*) from agent_run_event where run_id = '$RUN_ID'::uuid and event_type = 'run_recovered';"
}
terminal_ledger_count() {
  psql_scalar "select count(*) from agent_cost_ledger where run_id = '$RUN_ID'::uuid and entry_type in ('SETTLE', 'RELEASE');"
}

IFS='|' read -r status_before attempt_before worker_before <<< "$(work_row)"
if [[ "$status_before" != "RUNNING" || -z "$attempt_before" || -z "$worker_before" ]]; then
  echo "Run $RUN_ID must be RUNNING and currently leased; found status=${status_before:-missing}" >&2
  exit 1
fi
checkpoint_id="$(psql_scalar "select id from agent_plan_checkpoint where run_id = '$RUN_ID'::uuid and status = 'AVAILABLE' order by sequence desc limit 1;")"
if [[ -z "$checkpoint_id" ]]; then
  echo "Run $RUN_ID has no AVAILABLE safe point; refusing a production kill" >&2
  exit 1
fi
recovery_events_before="$(event_count)"
terminal_ledger_before="$(terminal_ledger_count)"
reserve_ledger_count="$(psql_scalar "select count(*) from agent_cost_ledger where run_id = '$RUN_ID'::uuid and entry_type = 'RESERVE';")"
reservation_status_before="$(psql_scalar "select status from agent_cost_reservation where run_id = '$RUN_ID'::uuid;")"
if [[ "$terminal_ledger_before" != "0" || "$reserve_ledger_count" != "1" || "$reservation_status_before" != "RESERVED" ]]; then
  echo "Cost ledger is not in the expected single RESERVED state; refusing fault injection" >&2
  exit 1
fi

server_container="$("${compose[@]}" ps --status running -q server)"
if [[ -z "$server_container" ]]; then
  echo "Production server container is not running" >&2
  exit 1
fi
echo "Injecting one Server process failure for Run $RUN_ID (attempt $attempt_before, checkpoint $checkpoint_id)"
server_needs_start=true
restore_server() {
  if [[ "$server_needs_start" == "true" ]]; then
    "${compose[@]}" up -d server >/dev/null || true
  fi
}
trap restore_server EXIT
docker kill --signal=KILL "$server_container" >/dev/null
"${compose[@]}" up -d server >/dev/null
server_needs_start=false
if ! wait_for_server; then
  echo "Server did not return healthy after fault injection" >&2
  exit 1
fi

takeover_deadline=$((SECONDS + LEASE_SECONDS + 90))
attempt_after="$attempt_before"
worker_after="$worker_before"
while (( SECONDS < takeover_deadline )); do
  IFS='|' read -r current_status attempt_after worker_after <<< "$(work_row)"
  recovery_events_after="$(event_count)"
  if (( attempt_after > attempt_before )) && (( recovery_events_after > recovery_events_before )); then
    break
  fi
  if [[ "$current_status" != "RUNNING" ]]; then
    echo "Run became $current_status before lease takeover was observed" >&2
    exit 1
  fi
  sleep 2
done
if (( attempt_after <= attempt_before )) || (( recovery_events_after <= recovery_events_before )); then
  echo "Timed out waiting for lease takeover and run_recovered" >&2
  exit 1
fi
if [[ "$worker_after" == "$worker_before" ]]; then
  echo "Worker identity did not change after Server process replacement" >&2
  exit 1
fi
if [[ "$(terminal_ledger_count)" != "0" ]]; then
  echo "Old worker wrote a terminal cost entry after losing its lease" >&2
  exit 1
fi

terminal_deadline=$((SECONDS + RUN_TIMEOUT_SECONDS + 120))
terminal_status="RUNNING"
while (( SECONDS < terminal_deadline )); do
  IFS='|' read -r terminal_status _ _ <<< "$(work_row)"
  [[ "$terminal_status" =~ ^(SUCCEEDED|FAILED|CANCELLED)$ ]] && break
  sleep 2
done
if ! [[ "$terminal_status" =~ ^(SUCCEEDED|FAILED|CANCELLED)$ ]]; then
  echo "Recovered Run did not reach an accountable terminal state: $terminal_status" >&2
  exit 1
fi
reservation_status_after="$(psql_scalar "select status from agent_cost_reservation where run_id = '$RUN_ID'::uuid;")"
terminal_ledger_after="$(terminal_ledger_count)"
if [[ "$terminal_ledger_after" != "1" || ! "$reservation_status_after" =~ ^(SETTLED|RELEASED)$ ]]; then
  echo "Cost ledger reconciliation failed: reservation=$reservation_status_after terminal_entries=$terminal_ledger_after" >&2
  exit 1
fi

trap - EXIT
echo "P2.3-C takeover drill passed: attempt $attempt_before->$attempt_after, worker changed, run_recovered appended, terminal=$terminal_status, cost=$reservation_status_after"
