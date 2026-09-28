#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
  echo "Run this script with sudo on the VPS." >&2
  exit 1
fi

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root_dir"
build_dir="$root_dir/.tiny-server-build"
image="insightops-agent-server:tiny-local"

[[ -s "$build_dir/app.jar" && -f "$build_dir/Dockerfile" ]] || {
  echo "Missing local server JAR or Dockerfile in $build_dir" >&2
  exit 1
}

docker build --pull=false -t "$image" "$build_dir"

if grep -q '^TINY_SERVER_IMAGE=' .env.tiny; then
  grep -qx "TINY_SERVER_IMAGE=$image" .env.tiny || {
    echo "TINY_SERVER_IMAGE already has a different value; edit it before continuing." >&2
    exit 1
  }
else
  printf '\nTINY_SERVER_IMAGE=%s\n' "$image" >> .env.tiny
fi

bash scripts/preflight-tiny.sh
compose=(docker compose --env-file .env.tiny -f infra/compose.tiny.yml)
"${compose[@]}" up -d postgres uploads-init server web

ready=false
for (( attempt=1; attempt<=24; attempt++ )); do
  container_id="$("${compose[@]}" ps -q server)"
  if [[ -z "$container_id" ]]; then
    health=missing
  else
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
  fi
  echo "Server health ${attempt}/24: $health"
  if [[ "$health" == healthy ]]; then
    ready=true
    break
  fi
  sleep 10
done

"${compose[@]}" ps --all
if [[ "$ready" != true ]]; then
  "${compose[@]}" logs --tail=80 server >&2
  exit 1
fi

echo "Server is healthy. Caddy is still off until DNS is ready."
