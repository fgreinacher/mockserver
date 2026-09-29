#!/usr/bin/env bash
# Load Scenario registry - the full register -> start -> list -> stop lifecycle.
#
# A "load scenario" is a named, server-side traffic generator. You REGISTER it
# once (its profile of ramp/hold/pause stages and the request steps it drives),
# then START it by name. While running it generates synthetic traffic against the
# data plane and reports live throughput/latency status; STOP halts it (it stays
# registered and can be re-started). Useful for soak tests, autoscaling drills and
# resilience verification entirely inside MockServer.
#
# IMPORTANT: the server must be started with load generation enabled:
#   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-jar-with-dependencies.jar -serverPort 1080
#   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always
#   allowed; STARTING returns HTTP 403 when load generation is disabled.
#
# Self-asserting: exits non-zero if any step misbehaves.
# Honours MOCKSERVER_URL, or MOCKSERVER_HOST/MOCKSERVER_PORT (default localhost:1080).
set -euo pipefail

MS="${MOCKSERVER_URL:-http://${MOCKSERVER_HOST:-localhost}:${MOCKSERVER_PORT:-1080}}"

# A target expectation so the generated traffic gets a 200 to measure.
curl -sf -X PUT "${MS}/mockserver/expectation" -d '{
  "httpRequest": { "path": "/ping" },
  "httpResponse": { "statusCode": 200, "body": "pong" }
}' >/dev/null

# 1. REGISTER - PUT /mockserver/loadScenario with a full LoadScenario definition.
#    Long stage durations are used so the scenario is observably RUNNING below.
echo "==> register 'demo-soak' (RATE ramp -> VU hold -> PAUSE)..."
register_resp=$(curl -sf -X PUT "${MS}/mockserver/loadScenario" -d '{
  "name": "demo-soak",
  "templateType": "VELOCITY",
  "profile": {
    "stages": [
      { "type": "RATE", "startRate": 1, "endRate": 10, "durationMillis": 60000, "curve": "LINEAR", "maxVus": 20 },
      { "type": "VU",   "vus": 5, "durationMillis": 60000 },
      { "type": "PAUSE", "durationMillis": 5000 }
    ]
  },
  "steps": [
    { "name": "ping", "request": { "method": "GET", "path": "/ping" }, "thinkTime": { "timeUnit": "MILLISECONDS", "value": 50 } }
  ]
}') || true
grep -q '"state" : "LOADED"' <<<"${register_resp}" || { echo "FAIL: register did not return LOADED"; exit 1; }

# 2. START - PUT /mockserver/loadScenario/start with {"names":[...]} (or {"name":"x"}).
echo "==> start 'demo-soak'..."
start_resp=$(curl -sf -X PUT "${MS}/mockserver/loadScenario/start" -d '{"names":["demo-soak"]}')
grep -q '"state" : "RUNNING"' <<<"${start_resp}" || { echo "FAIL: start did not return RUNNING (is loadGenerationEnabled=true?)"; echo "${start_resp}"; exit 1; }

# 3. LIST - GET /mockserver/loadScenario returns {"scenarios":[ <status node>, ... ]}.
echo "==> list scenarios (expect demo-soak RUNNING)..."
sleep 1
list_resp=$(curl -sf "${MS}/mockserver/loadScenario")
grep -q '"state" : "RUNNING"' <<<"${list_resp}" || { echo "FAIL: demo-soak not RUNNING in list"; echo "${list_resp}"; exit 1; }

# A single scenario's live status (throughput/latency) is at GET /mockserver/loadScenario/{name}.
echo "==> status of 'demo-soak':"
curl -sf "${MS}/mockserver/loadScenario/demo-soak" \
  | grep -E '"state"|"stageType"|"currentTarget"|"requestsSent"|"p95Millis"' || true

# 4. STOP - PUT /mockserver/loadScenario/stop with {"names":[...]}; {} or {"all":true} stops all.
echo "==> stop 'demo-soak'..."
stop_resp=$(curl -sf -X PUT "${MS}/mockserver/loadScenario/stop" -d '{"names":["demo-soak"]}') || true
grep -q '"state" : "STOPPED"' <<<"${stop_resp}" || { echo "FAIL: stop did not return STOPPED"; exit 1; }

echo "PASS: register -> start -> list -> stop"
