#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.prod}"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

compose_files=(
  -f "$ROOT_DIR/infra/compose.prod.yml"
  -f "$ROOT_DIR/infra/compose.public-beta.yml"
  -f "$ROOT_DIR/infra/compose.tencent-ses.yml"
  -f "$ROOT_DIR/infra/compose.personal-export.yml"
  -f "$ROOT_DIR/infra/compose.public-beta-monitoring.yml"
)

docker compose --env-file "$ENV_FILE" "${compose_files[@]}" config --quiet

if [[ "$(prod_env_get PUBLIC_BETA_ENABLED "$ENV_FILE")" != "true" ]]; then
  echo "Public Beta preflight passed with registration infrastructure disabled"
  exit 0
fi

required=(
  PUBLIC_OPERATOR_NAME PUBLIC_CONTACT_EMAIL
  TURNSTILE_SITE_KEY TURNSTILE_SECRET_KEY TURNSTILE_EXPECTED_HOSTNAME
  TENCENT_SES_SECRET_ID TENCENT_SES_SECRET_KEY TENCENT_SES_FROM_ADDRESS
  TENCENT_SES_EMAIL_VERIFICATION_TEMPLATE_ID
  TENCENT_SES_PASSWORD_RESET_TEMPLATE_ID
  TENCENT_SES_WORKSPACE_INVITATION_TEMPLATE_ID
)
for name in "${required[@]}"; do
  value="$(prod_env_get "$name" "$ENV_FILE")"
  if [[ -z "$value" || "$value" == *CHANGE_ME* ]]; then
    echo "$name is required before PUBLIC_BETA_ENABLED=true" >&2
    exit 1
  fi
done

for name in IDENTITY_MAIL_ENABLED TENCENT_SES_ENABLED TURNSTILE_ENABLED; do
  if [[ "$(prod_env_get "$name" "$ENV_FILE")" != "true" ]]; then
    echo "$name must be true before PUBLIC_BETA_ENABLED=true" >&2
    exit 1
  fi
done

minimum_age="$(prod_env_get PUBLIC_BETA_MINIMUM_AGE "$ENV_FILE")"
maximum_registrations="$(prod_env_get PUBLIC_BETA_MAXIMUM_REGISTRATIONS "$ENV_FILE")"
[[ -z "$minimum_age" ]] && minimum_age=14
[[ -z "$maximum_registrations" ]] && maximum_registrations=100
if ! [[ "$minimum_age" =~ ^[0-9]+$ ]] || (( minimum_age < 14 )); then
  echo "PUBLIC_BETA_MINIMUM_AGE must be an integer of at least 14" >&2
  exit 1
fi
if ! [[ "$maximum_registrations" =~ ^[0-9]+$ ]] || (( maximum_registrations < 1 || maximum_registrations > 100 )); then
  echo "PUBLIC_BETA_MAXIMUM_REGISTRATIONS must be between 1 and 100 for the initial Beta" >&2
  exit 1
fi

app_address="$(prod_env_get APP_ADDRESS "$ENV_FILE")"
turnstile_hostname="$(prod_env_get TURNSTILE_EXPECTED_HOSTNAME "$ENV_FILE")"
if [[ "$turnstile_hostname" != "$app_address" ]]; then
  echo "TURNSTILE_EXPECTED_HOSTNAME must exactly match APP_ADDRESS" >&2
  exit 1
fi
if [[ "$(prod_env_get PUBLIC_CONTACT_EMAIL "$ENV_FILE")" != *@* ]]; then
  echo "PUBLIC_CONTACT_EMAIL must be a valid public contact address" >&2
  exit 1
fi

echo "Public Beta production preflight passed; the database registration switch remains the final gate"
