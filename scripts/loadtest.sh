#!/usr/bin/env bash
# Fires concurrent triage requests at a running server and prints the status-code mix.
#
#   ./scripts/loadtest.sh [requests=200] [concurrency=20] [url=http://localhost:8080]
#
# Start the server with simulated model latency so admission control has something to do:
#   TRIAGE_SIMULATED_LATENCY_MS=300 TRIAGE_MAX_CONCURRENT=4 java -jar target/incident-triage-agent.jar serve
#
# For latency percentiles use a real load tool, e.g. `hey` (brew install hey):
#   hey -n 200 -c 20 -m POST -T application/json \
#     -d '{"incident":"checkout-service is slow"}' http://localhost:8080/v1/triage
set -euo pipefail

REQUESTS="${1:-200}"
CONCURRENCY="${2:-20}"
URL="${3:-http://localhost:8080}"

INCIDENTS=(
  "checkout-service p99 latency above 2s"
  "payments-api 5xx rate above 10 percent"
  "auth-service login failures spiking"
  "orders-service requests timing out"
)

echo "Sending ${REQUESTS} requests (concurrency ${CONCURRENCY}) to ${URL}/v1/triage"
echo "count status"
for i in $(seq "${REQUESTS}"); do
  echo "${INCIDENTS[$((i % ${#INCIDENTS[@]}))]}"
done | xargs -P "${CONCURRENCY}" -I{} \
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "${URL}/v1/triage" \
    -H "Content-Type: application/json" -d '{"incident": "{}"}' \
  | sort | uniq -c

echo
echo "Server metrics:"
curl -s "${URL}/metrics" | grep -E '^triage_(requests_total|latency_ms_count|latency_ms_sum)'
