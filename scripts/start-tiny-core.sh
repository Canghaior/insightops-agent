#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
  echo "Run this script with sudo on the VPS." >&2
  exit 1
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

bash scripts/preflight-tiny.sh
compose=(docker compose --parallel 2 --env-file .env.tiny -f infra/compose.tiny.yml)

echo "Pulling the four core services; this can take several minutes."
if grep -qx 'TINY_SERVER_IMAGE=insightops-agent-server:tiny-local' .env.tiny; then
  "${compose[@]}" pull postgres uploads-init web
else
  "${compose[@]}" pull postgres uploads-init server web
fi

echo "Starting PostgreSQL, server, and web. Caddy stays off until DNS is ready."
"${compose[@]}" up -d postgres uploads-init server web

container_health() {
  local container_id
  container_id="$("${compose[@]}" ps -q "$1")"
  if [[ -z "$container_id" ]]; then
    printf 'missing'
    return
  fi
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id"
}

ready=false
for (( attempt=1; attempt<=36; attempt++ )); do
  postgres_health="$(container_health postgres)"
  server_health="$(container_health server)"
  web_health="$(container_health web)"
  echo "Health ${attempt}/36: postgres=$postgres_health server=$server_health web=$web_health"
  if [[ "$postgres_health" == healthy && "$server_health" == healthy && "$web_health" == healthy ]]; then
    ready=true
    break
  fi
  if [[ "$postgres_health" == exited || "$server_health" == exited || "$web_health" == exited ]]; then
    break
  fi
  sleep 10
done

"${compose[@]}" ps --all
free -h
df -h /

if [[ "$ready" != true ]]; then
  echo "Core services did not all become healthy. Recent logs follow:" >&2
  "${compose[@]}" logs --tail=60 postgres uploads-init server web >&2
  exit 1
fi

echo "Core services are healthy. Next: check firewall and DNS before starting Caddy."
