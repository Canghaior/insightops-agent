#!/usr/bin/env bash
set -euo pipefail

if (( EUID == 0 )) || [[ ! -t 0 ]]; then
  echo "Run this script interactively as your ordinary VPS SSH user, without sudo." >&2
  exit 1
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"

read -r -s -p 'Paste your DeepSeek API Key here, then press Enter (input stays hidden): ' api_key
printf '\n'
printf '%s' "$api_key" | python3 scripts/configure-tiny-deepseek.py
unset api_key

bash scripts/preflight-tiny.sh
compose=(sudo docker compose --env-file .env.tiny -f infra/compose.tiny.yml)
"${compose[@]}" up -d --no-deps server

ready=false
for (( attempt=1; attempt<=24; attempt++ )); do
  container_id="$("${compose[@]}" ps -q server)"
  if [[ -z "$container_id" ]]; then
    health=missing
  else
    health="$(sudo docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
  fi
  echo "Server health ${attempt}/24: $health"
  if [[ "$health" == healthy ]]; then
    ready=true
    break
  fi
  sleep 10
done

if [[ "$ready" != true ]]; then
  echo "Server did not become healthy; report this result without sending your key." >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 15 \
  https://insightops.canghaior.com/api/v1/system/status
printf '\n'
