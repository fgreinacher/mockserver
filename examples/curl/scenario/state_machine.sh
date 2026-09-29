#!/usr/bin/env bash
# Stateful scenario - login state machine.
# Three expectations share scenarioName "LoginFlow". Each scenario is an
# independent named state machine that starts in the "Started" state. An
# expectation matches only when the scenario is in its "scenarioState", and on
# match advances the scenario to "newScenarioState". So GET /profile returns 401
# until POST /login moves the scenario to "LoggedIn", after which it returns 200.
# Self-asserting: exits non-zero if any step misbehaves.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

curl -sf -X PUT "${MS}/mockserver/reset" >/dev/null

# Step 1: login returns a token once, advancing Started -> LoggedIn
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "POST", "path": "/login" },
  "httpResponse": { "statusCode": 200, "body": "{\"token\": \"abc123\"}" },
  "scenarioName": "LoginFlow",
  "scenarioState": "Started",
  "newScenarioState": "LoggedIn",
  "times": { "remainingTimes": 1 }
}' >/dev/null

# Step 2: once LoggedIn, GET /profile returns the user
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/profile" },
  "httpResponse": { "statusCode": 200, "body": "{\"name\": \"Alice\"}" },
  "scenarioName": "LoginFlow",
  "scenarioState": "LoggedIn"
}' >/dev/null

# Step 3: before login (Started), GET /profile is unauthorised
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "method": "GET", "path": "/profile" },
  "httpResponse": { "statusCode": 401, "body": "{\"error\": \"Not authenticated\"}" },
  "scenarioName": "LoginFlow",
  "scenarioState": "Started"
}' >/dev/null

echo "GET /profile (before login):"
before=$(curl -s -o /dev/null -w '%{http_code}' "${MS}/profile")
echo "  -> ${before}"
echo "POST /login:"
token=$(curl -s -X POST "${MS}/login"); echo "  -> ${token}"
echo "GET /profile (after login):"
after=$(curl -s "${MS}/profile"); echo "  -> ${after}"

[ "${before}" = "401" ]            || { echo "FAIL: expected 401 before login"; exit 1; }
grep -q abc123 <<<"${token}"   || { echo "FAIL: expected token from /login"; exit 1; }
grep -q Alice  <<<"${after}"    || { echo "FAIL: expected profile after login"; exit 1; }
echo "PASS: login state machine"
