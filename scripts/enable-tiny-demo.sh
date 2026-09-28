#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo 'Run with sudo: sudo bash scripts/enable-tiny-demo.sh' >&2
  exit 1
fi

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
compose=(docker compose --env-file .env.tiny -f infra/compose.tiny.yml)

"${compose[@]}" config --quiet
"${compose[@]}" run --rm --no-deps --entrypoint caddy caddy \
  validate --config /etc/caddy/Caddyfile

echo 'Pulling bounded worker image.'
"${compose[@]}" pull worker
"${compose[@]}" up -d --no-deps worker

wait_healthy() {
  local name=$1 attempts=$2 delay=$3 state
  for ((i=1; i<=attempts; i++)); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || true)
    echo "$name health $i/$attempts: ${state:-missing}"
    [[ $state == healthy ]] && return 0
    [[ $state == exited || $state == dead ]] && break
    sleep "$delay"
  done
  return 1
}

if ! wait_healthy insightops-prod-worker-1 24 10; then
  echo 'Worker did not become healthy; stopping it to protect the site.' >&2
  "${compose[@]}" stop worker
  "${compose[@]}" logs --tail=60 worker
  exit 1
fi

available_kib=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
if (( available_kib < 262144 )); then
  echo 'Less than 256 MiB available after starting Worker; stopping it.' >&2
  "${compose[@]}" stop worker
  exit 1
fi

echo 'Enabling keyword retrieval in Server.'
"${compose[@]}" up -d --no-deps server
if ! wait_healthy insightops-prod-server-1 24 10; then
  echo 'Server failed after enabling keyword retrieval; restoring the previous setting.' >&2
  "${compose[@]}" stop worker
  RAG_ENABLED=false "${compose[@]}" up -d --no-deps server
  wait_healthy insightops-prod-server-1 24 10 || true
  exit 1
fi

echo 'Publishing the project information page.'
"${compose[@]}" up -d --no-deps caddy
if ! wait_healthy insightops-prod-caddy-1 12 5; then
  echo 'Caddy did not become healthy. Inspect its logs and previous configuration.' >&2
  "${compose[@]}" logs --tail=60 caddy
  exit 1
fi

free -h
df -h /
"${compose[@]}" ps
echo 'Demo services are healthy. Official documentation sources still need a first manual sync.'
