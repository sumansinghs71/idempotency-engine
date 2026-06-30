#!/usr/bin/env bash
# =============================================================================
# IdemEngine smoke test — exercises the contract end-to-end via curl.
# Run while the app is bootRun'ing.
#
# Usage:  ./scripts/smoke-test.sh [user_id]
#         defaults to user_id=1
# =============================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
USER_ID="${1:-1}"

KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
KEY2="$(uuidgen | tr '[:upper:]' '[:lower:]')"
BODY_A='{"amount":2000,"currency":"usd","customer_id":"cus_alice"}'
BODY_B='{"amount":9999,"currency":"usd","customer_id":"cus_alice"}'

bold() { printf "\n\033[1m== %s ==\033[0m\n" "$1"; }
status() { printf "  -> %s\n" "$1"; }

req() {
  # req <name> <expected-status> <key> <body>
  local name="$1" want="$2" key="$3" body="$4"
  local out; out="$(mktemp)"
  local code
  code="$(curl -s -o "$out" -w '%{http_code}' \
      -X POST "$BASE/charges" \
      -H "Content-Type: application/json" \
      -H "X-User-Id: $USER_ID" \
      -H "Idempotency-Key: $key" \
      --data "$body")"
  printf "[%s] %s  -> %s\n" "$code" "$name" "$(cat "$out")"
  rm -f "$out"
  if [[ "$code" != "$want" ]]; then
    echo "  FAIL: expected $want, got $code"
    exit 1
  fi
}

bold "FR-1 missing Idempotency-Key -> 400"
code="$(curl -s -o /dev/null -w '%{http_code}' \
    -X POST "$BASE/charges" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: $USER_ID" \
    --data "$BODY_A")"
status "got $code (want 400)"
[[ "$code" == "400" ]] || { echo "FAIL"; exit 1; }

bold "Happy path -> 201"
req "first attempt" 201 "$KEY" "$BODY_A"

bold "FR-2 same key + same body -> 201 cached (no second charge)"
req "second attempt" 201 "$KEY" "$BODY_A"

bold "FR-3 same key + different body -> 422"
req "body mismatch" 422 "$KEY" "$BODY_B"

bold "FR-7 same key string, fresh request via second key -> 201"
req "different key, same body" 201 "$KEY2" "$BODY_A"

bold "Done. All assertions passed."
