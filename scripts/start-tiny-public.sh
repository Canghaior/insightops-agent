#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
  echo "Run this script with sudo on the VPS." >&2
  exit 1
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"
bash scripts/preflight-tiny.sh

# Docker publishes only Caddy's HTTP(S) ports in the tiny Compose file.
ufw allow 80/tcp
ufw allow 443/tcp

compose=(docker compose --env-file .env.tiny -f infra/compose.tiny.yml)
"${compose[@]}" pull caddy
"${compose[@]}" up -d caddy

ready=false
for (( attempt=1; attempt<=12; attempt++ )); do
  container_id="$("${compose[@]}" ps -q caddy)"
  if [[ -z "$container_id" ]]; then
    health=missing
  else
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
  fi
  echo "Caddy health ${attempt}/12: $health"
  if [[ "$health" == healthy ]]; then
    ready=true
    break
  fi
  sleep 5
done

"${compose[@]}" ps
ufw status

if [[ "$ready" != true ]]; then
  "${compose[@]}" logs --tail=80 caddy >&2
  exit 1
fi

echo "Caddy is healthy. Verify public ports, then update the DNS A record."
