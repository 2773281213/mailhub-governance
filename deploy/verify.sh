#!/usr/bin/env bash
set -euo pipefail

base_url=${1:-http://127.0.0.1:8018}
: "${MAILHUB_ADMIN_PASSWORD:?MAILHUB_ADMIN_PASSWORD is required}"
cookie=$(mktemp)
trap 'rm -f "$cookie"' EXIT

curl -fsS "${base_url}/api/health"
printf '\n'
payload=$(MAILHUB_ADMIN_PASSWORD="$MAILHUB_ADMIN_PASSWORD" python3 -c \
  'import json,os; print(json.dumps({"password": os.environ["MAILHUB_ADMIN_PASSWORD"]}))')
curl -fsS -c "$cookie" -H 'Content-Type: application/json' \
  --data "$payload" "${base_url}/api/login"
printf '\n'
curl -fsS -b "$cookie" "${base_url}/api/oauth/providers" | python3 -c '
import json, sys
provider = json.load(sys.stdin)["providers"]["163"]
keys = ("host", "port", "auth_modes", "guided_auth", "oauth")
print(json.dumps({key: provider[key] for key in keys}, ensure_ascii=False))
'
