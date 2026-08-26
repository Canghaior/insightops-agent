#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
confirmation="${1:-}"

if [[ "$confirmation" != "--from-base64-lines" ]]; then
  echo "Public Beta production values must be supplied through standard input" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi

decode_line() {
  local encoded=""
  IFS= read -r encoded || {
    echo "Public Beta production input is incomplete" >&2
    exit 1
  }
  [[ -n "$encoded" && "$encoded" =~ ^[A-Za-z0-9+/=]+$ ]] || {
    echo "Public Beta production input is not valid base64" >&2
    exit 1
  }
  printf '%s' "$encoded" | base64 --decode
}

tencent_secret_id="$(decode_line)"
tencent_secret_key="$(decode_line)"
turnstile_site_key="$(decode_line)"
turnstile_secret_key="$(decode_line)"
operator_name="$(decode_line)"
contact_email="$(decode_line)"
if IFS= read -r unexpected_line; then
  echo "Public Beta production input must contain exactly six lines" >&2
  exit 1
fi

[[ "$tencent_secret_id" =~ ^[A-Za-z0-9_-]{16,128}$ ]] || {
  echo "Tencent SES SecretId format is invalid" >&2
  exit 1
}
[[ "$tencent_secret_key" =~ ^[A-Za-z0-9+/=_-]{16,128}$ ]] || {
  echo "Tencent SES SecretKey format is invalid" >&2
  exit 1
}
[[ "$turnstile_site_key" =~ ^[A-Za-z0-9._-]{8,128}$ ]] || {
  echo "Turnstile site key format is invalid" >&2
  exit 1
}
[[ "$turnstile_secret_key" =~ ^[A-Za-z0-9._-]{8,128}$ ]] || {
  echo "Turnstile secret key format is invalid" >&2
  exit 1
}
(( ${#operator_name} >= 2 && ${#operator_name} <= 80 )) \
  && [[ "$operator_name" != *$'\n'* && "$operator_name" != *$'\r'* ]] \
  && [[ "$operator_name" != *"#"* && "$operator_name" != *"="* ]] || {
  echo "Public operator display name format is invalid" >&2
  exit 1
}
[[ "$contact_email" =~ ^[A-Za-z0-9.!#$%\&*+/=?^_{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,63}$ ]] || {
  echo "Public contact email format is invalid" >&2
  exit 1
}

declare -A replacements=(
  [PUBLIC_BETA_ENABLED]="false"
  [PUBLIC_BETA_MAXIMUM_REGISTRATIONS]="100"
  [PUBLIC_BETA_MINIMUM_AGE]="14"
  [PUBLIC_BETA_PENDING_VERIFICATION_HOURS]="24"
  [PUBLIC_OPERATOR_NAME]="$operator_name"
  [PUBLIC_CONTACT_EMAIL]="$contact_email"
  [PUBLIC_TERMS_VERSION]="2026-08-26"
  [PUBLIC_PRIVACY_VERSION]="2026-08-26"
  [PUBLIC_ACCEPTABLE_USE_VERSION]="2026-08-26"
  [IDENTITY_MAIL_ENABLED]="false"
  [IDENTITY_MAIL_FROM]="no-reply@mail.canghaior.com"
  [TENCENT_SES_ENABLED]="false"
  [TENCENT_SES_SECRET_ID]="$tencent_secret_id"
  [TENCENT_SES_SECRET_KEY]="$tencent_secret_key"
  [TENCENT_SES_REGION]="ap-guangzhou"
  [TENCENT_SES_FROM_ADDRESS]="no-reply@mail.canghaior.com"
  [TENCENT_SES_FROM_NAME]="InsightOps Agent"
  [TENCENT_SES_REPLY_TO]=""
  [TENCENT_SES_EMAIL_VERIFICATION_TEMPLATE_ID]="58078"
  [TENCENT_SES_PASSWORD_RESET_TEMPLATE_ID]="58079"
  [TENCENT_SES_WORKSPACE_INVITATION_TEMPLATE_ID]="58080"
  [TURNSTILE_ENABLED]="false"
  [TURNSTILE_SITE_KEY]="$turnstile_site_key"
  [TURNSTILE_SECRET_KEY]="$turnstile_secret_key"
  [TURNSTILE_EXPECTED_HOSTNAME]="insightops.canghaior.com"
  [TURNSTILE_EXPECTED_ACTION]="register"
  [PERSONAL_EXPORT_EXPIRES_HOURS]="24"
)
ordered_names=(
  PUBLIC_BETA_ENABLED PUBLIC_BETA_MAXIMUM_REGISTRATIONS
  PUBLIC_BETA_MINIMUM_AGE PUBLIC_BETA_PENDING_VERIFICATION_HOURS
  PUBLIC_OPERATOR_NAME PUBLIC_CONTACT_EMAIL PUBLIC_TERMS_VERSION
  PUBLIC_PRIVACY_VERSION PUBLIC_ACCEPTABLE_USE_VERSION
  IDENTITY_MAIL_ENABLED IDENTITY_MAIL_FROM TENCENT_SES_ENABLED
  TENCENT_SES_SECRET_ID TENCENT_SES_SECRET_KEY TENCENT_SES_REGION
  TENCENT_SES_FROM_ADDRESS TENCENT_SES_FROM_NAME TENCENT_SES_REPLY_TO
  TENCENT_SES_EMAIL_VERIFICATION_TEMPLATE_ID
  TENCENT_SES_PASSWORD_RESET_TEMPLATE_ID
  TENCENT_SES_WORKSPACE_INVITATION_TEMPLATE_ID
  TURNSTILE_ENABLED TURNSTILE_SITE_KEY TURNSTILE_SECRET_KEY
  TURNSTILE_EXPECTED_HOSTNAME TURNSTILE_EXPECTED_ACTION
  PERSONAL_EXPORT_EXPIRES_HOURS
)
declare -A written=()

temporary="$(mktemp "${ENV_FILE}.public-beta.XXXXXX")"
cleanup() {
  if [[ "$temporary" == "${ENV_FILE}.public-beta."* && -f "$temporary" ]]; then
    rm -f -- "$temporary"
  fi
}
trap cleanup EXIT

while IFS= read -r line || [[ -n "$line" ]]; do
  name="${line%%=*}"
  if [[ "$line" == *=* && -v "replacements[$name]" ]]; then
    if [[ ! -v "written[$name]" ]]; then
      printf '%s=%s\n' "$name" "${replacements[$name]}" >> "$temporary"
      written[$name]=true
    fi
  else
    printf '%s\n' "$line" >> "$temporary"
  fi
done < "$ENV_FILE"

for name in "${ordered_names[@]}"; do
  if [[ ! -v "written[$name]" ]]; then
    printf '%s=%s\n' "$name" "${replacements[$name]}" >> "$temporary"
  fi
done

chmod 600 "$temporary"
bash "$ROOT_DIR/scripts/preflight-prod.sh" "$temporary" >/dev/null
bash "$ROOT_DIR/scripts/preflight-public-beta.sh" "$temporary" >/dev/null
mv -- "$temporary" "$ENV_FILE"
trap - EXIT

tencent_secret_id=""
tencent_secret_key=""
turnstile_site_key=""
turnstile_secret_key=""

echo "Public Beta credentials are configured with all public activation switches still disabled"
