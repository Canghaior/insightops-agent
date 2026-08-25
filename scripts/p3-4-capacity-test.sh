#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-https://insightops.canghaior.com}"
VUS="${VUS:-20}"
HOLD="${HOLD:-3m}"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 is required: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 1
fi
if ! [[ "$VUS" =~ ^[0-9]+$ ]] || (( VUS < 1 || VUS > 100 )); then
  echo "VUS must be an integer from 1 to 100" >&2
  exit 1
fi
case "$BASE_URL" in
  https://insightops.canghaior.com|http://127.0.0.1:*|http://localhost:*) ;;
  *) echo "BASE_URL must target production InsightOps or an explicit local endpoint" >&2; exit 1 ;;
esac

BASE_URL="$BASE_URL" VUS="$VUS" HOLD="$HOLD" \
  k6 run "$ROOT_DIR/scripts/load/public-beta.k6.js"
