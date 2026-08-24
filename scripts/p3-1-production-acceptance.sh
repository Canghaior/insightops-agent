#!/usr/bin/env bash
set -euo pipefail

umask 077

curl() {
  command curl --header 'X-InsightOps-CSRF: 1' "$@"
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
MARKER="${1:-}"
CONFIRMATION="${2:-}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

if [[ "$ROOT_DIR" != "/opt/insightops-agent" ]]; then
  echo "This production acceptance must run from /opt/insightops-agent" >&2
  exit 1
fi
if ! [[ "$MARKER" =~ ^P31-IDENTITY-[0-9]+-[0-9]+$ ]]; then
  echo "Marker must match P31-IDENTITY-<workflow-run>-<attempt>" >&2
  exit 1
fi
if [[ "$CONFIRMATION" != "--confirm-identity-team-acceptance" || $# -ne 2 ]]; then
  echo "Refusing P3.1 production acceptance without explicit confirmation" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
AUTH_USERNAME="$(prod_env_get AUTH_BOOTSTRAP_USERNAME "$ENV_FILE")"
SERVER_AUTH_PASSWORD="$(prod_env_get AUTH_BOOTSTRAP_PASSWORD "$ENV_FILE")"
APP_ADDRESS="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
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

base="https://${APP_ADDRESS}"
api="${base}/api/v1"
tmp_dir="$(mktemp -d)"
cookie_jar="$tmp_dir/cookies"
original_workspace_id=""
workspace_id=""
invitation_id=""
cleanup() {
  if [[ -n "$workspace_id" && -n "$invitation_id" ]]; then
    curl --silent --show-error --cookie "$cookie_jar" --request DELETE \
      "$api/workspaces/$workspace_id/invitations/$invitation_id" >/dev/null 2>&1 || true
  fi
  if [[ -n "$original_workspace_id" ]]; then
    curl --silent --show-error --cookie "$cookie_jar" --request POST \
      "$api/workspaces/$original_workspace_id/switch" >/dev/null 2>&1 || true
  fi
  if [[ -n "$workspace_id" ]]; then
    curl --silent --show-error --cookie "$cookie_jar" --request POST \
      "$api/workspaces/$workspace_id/archive" >/dev/null 2>&1 || true
  fi
  curl --silent --show-error --cookie "$cookie_jar" --request POST \
    "$api/auth/logout" >/dev/null 2>&1 || true
  case "$tmp_dir" in
    /tmp/tmp.*) rm -rf -- "$tmp_dir" ;;
    *) echo "Refusing to remove unexpected temporary directory: $tmp_dir" >&2 ;;
  esac
}
trap cleanup EXIT

headers="$(command curl --silent --show-error --head "$base/")"
if ! printf '%s' "$headers" | tr -d '\r' | grep -qi '^strict-transport-security:'; then
  echo "Production response is missing Strict-Transport-Security" >&2
  exit 1
fi

anonymous_status="$(command curl --silent --show-error --output "$tmp_dir/anonymous.json" \
  --write-out '%{http_code}' "$api/workspaces")"
if [[ "$anonymous_status" != "401" ]]; then
  echo "Anonymous workspace API returned HTTP $anonymous_status instead of 401" >&2
  exit 1
fi

login_body="$(AUTH_USERNAME="$AUTH_USERNAME" AUTH_PASSWORD="$AUTH_PASSWORD" python3 -c \
  'import json,os; print(json.dumps({"username":os.environ["AUTH_USERNAME"],"password":os.environ["AUTH_PASSWORD"]}))')"
printf '%s' "$login_body" | curl --fail --silent --show-error --cookie-jar "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @- "$api/auth/login" \
  >"$tmp_dir/login.json"
unset AUTH_PASSWORD login_body

csrf_status="$(command curl --silent --show-error --output "$tmp_dir/csrf.json" \
  --write-out '%{http_code}' --cookie "$cookie_jar" --request POST "$api/auth/logout")"
if [[ "$csrf_status" != "403" ]]; then
  echo "Unsafe authenticated request without CSRF marker returned HTTP $csrf_status instead of 403" >&2
  exit 1
fi

curl --fail --silent --show-error --cookie "$cookie_jar" "$api/auth/me" >"$tmp_dir/me-before.json"
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/identity/security" >"$tmp_dir/security.json"
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/identity/sessions" >"$tmp_dir/sessions.json"
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/workspaces" >"$tmp_dir/workspaces-before.json"
original_workspace_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["workspaceId"])' "$tmp_dir/me-before.json")"

slug="$(printf '%s' "$MARKER" | tr '[:upper:]' '[:lower:]')"
workspace_body="$(P31_NAME="$MARKER" P31_SLUG="$slug" python3 -c \
  'import json,os; print(json.dumps({"name":os.environ["P31_NAME"],"slug":os.environ["P31_SLUG"],"description":"P3.1 guarded production acceptance"}))')"
printf '%s' "$workspace_body" | curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @- "$api/workspaces" \
  >"$tmp_dir/workspace.json"
workspace_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "$tmp_dir/workspace.json")"

curl --fail --silent --show-error --cookie "$cookie_jar" --request POST \
  "$api/workspaces/$workspace_id/switch" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/auth/me" >"$tmp_dir/me-switched.json"
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/workspaces/$workspace_id/members" \
  >"$tmp_dir/members.json"

invite_email="${slug}@example.invalid"
invite_body="$(P31_EMAIL="$invite_email" python3 -c \
  'import json,os; print(json.dumps({"email":os.environ["P31_EMAIL"],"role":"MEMBER"}))')"
printf '%s' "$invite_body" | curl --fail --silent --show-error --cookie "$cookie_jar" \
  --header 'Content-Type: application/json' --data-binary @- \
  "$api/workspaces/$workspace_id/invitations" >"$tmp_dir/invitation.json"
invitation_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["invitation"]["id"])' "$tmp_dir/invitation.json")"
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/workspaces/$workspace_id/invitations" >"$tmp_dir/invitations-before.json"

curl --fail --silent --show-error --cookie "$cookie_jar" --request DELETE \
  "$api/workspaces/$workspace_id/invitations/$invitation_id" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_jar" \
  "$api/workspaces/$workspace_id/invitations" >"$tmp_dir/invitations-after.json"
invitation_id=""

forgot_body="$(P31_EMAIL="$invite_email" python3 -c \
  'import json,os; print(json.dumps({"email":os.environ["P31_EMAIL"]}))')"
forgot_status="$(printf '%s' "$forgot_body" | curl --silent --show-error \
  --output "$tmp_dir/forgot.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data-binary @- \
  "$api/public/identity/password/forgot")"
if [[ "$forgot_status" != "202" ]]; then
  echo "Generic password recovery returned HTTP $forgot_status instead of 202" >&2
  exit 1
fi

python3 - "$tmp_dir" "$original_workspace_id" "$workspace_id" "$invitation_id" "$MARKER" <<'PY'
import json
import os
import sys

folder, original_id, workspace_id, _cleared_invitation_id, marker = sys.argv[1:]
load = lambda name: json.load(open(os.path.join(folder, name), encoding="utf-8"))["data"]
login = load("login.json")
security = load("security.json")
sessions = load("sessions.json")
before = load("workspaces-before.json")
created = load("workspace.json")
switched = load("me-switched.json")
members = load("members.json")
invitation_created = load("invitation.json")
invitations_before = load("invitations-before.json")
invitations_after = load("invitations-after.json")

assert login["workspaceId"] == original_id
assert any(item["id"] == original_id and item["status"] == "ACTIVE" for item in before)
assert created["id"] == workspace_id and created["role"] == "OWNER"
assert created["name"] == marker and created["status"] == "ACTIVE"
assert switched["workspaceId"] == workspace_id and switched["role"] == "OWNER"
assert any(item["userId"] == login["userId"] and item["role"] == "OWNER" for item in members)
assert isinstance(security["mfaEnabled"], bool)
assert security["activeSessionCount"] >= 1
assert any(item["current"] and item["workspaceId"] == original_id for item in sessions)

created_invite = invitation_created["invitation"]
invite_id = created_invite["id"]
assert created_invite["workspaceId"] == workspace_id
assert created_invite["role"] == "MEMBER" and created_invite["status"] == "PENDING"
queued = invitation_created["deliveryQueued"]
manual_link = invitation_created.get("manualInvitationLink")
assert queued is True or (queued is False and manual_link and "#token=" in manual_link)
assert any(item["id"] == invite_id and item["status"] == "PENDING" for item in invitations_before)
assert any(item["id"] == invite_id and item["status"] == "REVOKED" for item in invitations_after)
if manual_link:
    raw_token = manual_link.split("#token=", 1)[1]
    assert raw_token not in json.dumps(invitations_before)
    assert raw_token not in json.dumps(invitations_after)
print(
    f"P3.1 identity/team production acceptance passed: workspace={workspace_id} "
    f"invitation={invite_id} mailQueued={queued} sessions={len(sessions)}"
)
PY

curl --fail --silent --show-error --cookie "$cookie_jar" --request POST \
  "$api/workspaces/$original_workspace_id/switch" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_jar" --request POST \
  "$api/workspaces/$workspace_id/archive" >/dev/null
curl --fail --silent --show-error --cookie "$cookie_jar" "$api/workspaces" \
  >"$tmp_dir/workspaces-after.json"
python3 - "$tmp_dir/workspaces-after.json" "$workspace_id" <<'PY'
import json
import sys
items = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
assert any(item["id"] == sys.argv[2] and item["status"] == "DISABLED" for item in items)
PY
workspace_id=""
original_workspace_id=""
echo "P3.1 production identity and team workspace acceptance completed"
