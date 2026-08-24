#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="$ROOT_DIR/infra/compose.prod.yml"
# shellcheck source=prod-env.sh
source "$ROOT_DIR/scripts/prod-env.sh"

observation_hours="${1:-72}"
minimum_success_percent="${2:-95}"
if ! [[ "$observation_hours" =~ ^[0-9]+$ ]] || (( observation_hours < 24 || observation_hours > 720 )); then
  echo "Observation hours must be an integer from 24 to 720" >&2
  exit 1
fi
if ! [[ "$minimum_success_percent" =~ ^[0-9]+$ ]] \
    || (( minimum_success_percent < 80 || minimum_success_percent > 100 )); then
  echo "Minimum success percent must be an integer from 80 to 100" >&2
  exit 1
fi

bash "$ROOT_DIR/scripts/preflight-prod.sh" "$ENV_FILE" >/dev/null
POSTGRES_USER="$(prod_env_get POSTGRES_USER "$ENV_FILE")"
POSTGRES_DB="$(prod_env_get POSTGRES_DB "$ENV_FILE")"
GITHUB_TOKEN="$(prod_env_get GITHUB_TOKEN "$ENV_FILE")"
POSTGRES_USER="${POSTGRES_USER:-insightops}"
POSTGRES_DB="${POSTGRES_DB:-insightops}"

if [[ -z "$GITHUB_TOKEN" ]]; then
  echo "GITHUB_TOKEN is required to close the 10-project stability gate" >&2
  exit 1
fi

query=$(cat <<'SQL'
with expected(sort_order, repository_owner, repository_name) as (
  values
    (1, 'spring-projects', 'spring-ai'),
    (2, 'langchain4j', 'langchain4j'),
    (3, 'langgenius', 'dify'),
    (4, 'alibaba', 'spring-ai-alibaba'),
    (5, 'quarkiverse', 'quarkus-langchain4j'),
    (6, 'modelcontextprotocol', 'java-sdk'),
    (7, 'openai', 'openai-java'),
    (8, 'anthropics', 'anthropic-sdk-java'),
    (9, 'googleapis', 'java-genai'),
    (10, 'ollama', 'ollama')
), project_rows as (
  select expected.sort_order, expected.repository_owner, expected.repository_name,
         project.id, project.enabled, project.sync_interval_hours,
         project.last_sync_status, project.last_sync_at,
         project.consecutive_failures, project.sync_locked_until
  from expected
  left join tracked_project project
    on project.repository_owner=expected.repository_owner
   and project.repository_name=expected.repository_name
   and project.workspace_id='00000000-0000-0000-0000-000000000001'
), job_stats as (
  select project_rows.id,
         count(job.id) as terminal_runs,
         count(job.id) filter (where job.status='SUCCEEDED') as successful_runs,
         count(job.id) filter (where job.status<>'SUCCEEDED') as unsuccessful_runs
  from project_rows
  left join job_task job
    on job.project_id=project_rows.id
   and job.job_type='GITHUB_RELEASE_SYNC'
   and job.updated_at >= now() - (:observation_hours || ' hours')::interval
  group by project_rows.id
)
select project_rows.sort_order,
       project_rows.repository_owner || '/' || project_rows.repository_name,
       case when project_rows.id is null then 'MISSING' else 'PRESENT' end,
       coalesce(project_rows.enabled, false),
       coalesce(project_rows.sync_interval_hours, 0),
       coalesce(project_rows.last_sync_status, 'MISSING'),
       coalesce(round(extract(epoch from (now() - project_rows.last_sync_at)) / 3600.0, 2), 999999),
       coalesce(project_rows.consecutive_failures, 999999),
       coalesce(job_stats.terminal_runs, 0),
       coalesce(job_stats.successful_runs, 0),
       coalesce(job_stats.unsuccessful_runs, 0),
       case when coalesce(job_stats.terminal_runs, 0)=0 then 0
            else round(100.0 * job_stats.successful_runs / job_stats.terminal_runs, 2) end,
       case when project_rows.last_sync_status='RUNNING'
            then coalesce(project_rows.sync_locked_until > now(), false)
            else true end
from project_rows
left join job_stats on job_stats.id=project_rows.id
order by project_rows.sort_order;
SQL
)
query="${query//:observation_hours/$observation_hours}"

rows="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T postgres \
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --no-align --tuples-only --field-separator=$'\t' --set ON_ERROR_STOP=1 \
  --command "$query")"

printf '%-3s %-43s %-7s %-8s %-7s %-11s %-8s %-5s %-5s %-5s %-5s %-7s\n' \
  '#' 'PROJECT' 'PRESENT' 'ENABLED' 'PERIOD' 'STATUS' 'AGE_H' 'FAIL' 'RUNS' 'OK' 'BAD' 'RATE'
failures=0
project_count=0
while IFS=$'\t' read -r sort_order project present enabled interval status age_hours \
    consecutive_failures terminal_runs successful_runs unsuccessful_runs success_percent lease_valid; do
  [[ -n "$sort_order" ]] || continue
  project_count=$((project_count + 1))
  printf '%-3s %-43s %-7s %-8s %-7s %-11s %-8s %-5s %-5s %-5s %-5s %-7s\n' \
    "$sort_order" "$project" "$present" "$enabled" "$interval" "$status" "$age_hours" \
    "$consecutive_failures" "$terminal_runs" "$successful_runs" "$unsuccessful_runs" "$success_percent"

  if [[ "$present" != "PRESENT" || "$enabled" != "t" || "$interval" == "0" ]]; then
    echo "Stability gate failed for $project: project is missing or disabled" >&2
    failures=$((failures + 1))
    continue
  fi
  if [[ "$status" != "SUCCEEDED" && "$status" != "RUNNING" ]]; then
    echo "Stability gate failed for $project: current status is $status" >&2
    failures=$((failures + 1))
  fi
  if [[ "$lease_valid" != "t" ]]; then
    echo "Stability gate failed for $project: active collection lease is invalid" >&2
    failures=$((failures + 1))
  fi
  if (( consecutive_failures != 0 )); then
    echo "Stability gate failed for $project: consecutive failures=$consecutive_failures" >&2
    failures=$((failures + 1))
  fi

  maximum_age=$((interval + 6))
  age_whole="${age_hours%%.*}"
  if (( age_whole > maximum_age )); then
    echo "Stability gate failed for $project: last sync age ${age_hours}h exceeds ${maximum_age}h" >&2
    failures=$((failures + 1))
  fi

  expected_runs=$((observation_hours * 80 / 100 / interval))
  (( expected_runs < 2 )) && expected_runs=2
  if (( terminal_runs < expected_runs )); then
    echo "Stability gate failed for $project: $terminal_runs runs, expected at least $expected_runs" >&2
    failures=$((failures + 1))
  fi
  success_whole="${success_percent%%.*}"
  if (( success_whole < minimum_success_percent )); then
    echo "Stability gate failed for $project: success rate ${success_percent}%" >&2
    failures=$((failures + 1))
  fi
done <<< "$rows"

if (( project_count != 10 || failures != 0 )); then
  echo "10-project stability gate failed: projects=$project_count checks_failed=$failures" >&2
  exit 1
fi

echo "10-project stability gate passed for ${observation_hours}h at >=${minimum_success_percent}% success"
