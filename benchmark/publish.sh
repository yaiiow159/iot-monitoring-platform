#!/bin/bash
# 用 EMQX HTTP API 發一筆遙測：pub.sh DEV-000001 TH-100 temperature 65 [humidity 40]
SP="$(dirname "$0")"; TOKEN_FILE="$SP/.emqx-token"
DEV=$1; MODEL=$2; shift 2
TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)
if [ -z "$TOKEN" ] || ! curl -sf -o /dev/null -H "Authorization: Bearer $TOKEN" localhost:18083/api/v5/status; then
  TOKEN=$(curl -s -X POST localhost:18083/api/v5/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"iotmon2026"}' | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
  echo "$TOKEN" > "$TOKEN_FILE"
fi
METRICS=""
while [ $# -ge 2 ]; do
  METRICS="$METRICS,\\\"$1\\\":$2"; shift 2
done
METRICS=${METRICS#,}
ts=$(date +%s%3N)
curl -s -o /dev/null -w "publish $DEV {$METRICS} → %{http_code}\n" -X POST localhost:18083/api/v5/publish \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"topic\":\"iot/telemetry/$MODEL/$DEV\",\"qos\":1,\"payload\":\"{\\\"deviceId\\\":\\\"$DEV\\\",\\\"ts\\\":$ts,\\\"metrics\\\":{$METRICS}}\"}"
