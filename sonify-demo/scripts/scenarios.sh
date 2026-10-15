#!/usr/bin/env bash
#
# Runs the sixteen real-world scenarios against the demo applications.
#
#   ./scenarios.sh list
#   ./scenarios.sh run  <id> [users] [seconds]     drive from inside the JVM
#   ./scenarios.sh http <id> [users] [seconds]     drive over HTTP from outside
#   ./scenarios.sh all  [users] [seconds]          every scenario, in order, with pauses
#   ./scenarios.sh compare <id> [users] [seconds]  the same scenario on Tomcat and on Netty
#
# Drive over HTTP whenever the server's own concurrency model is the point: load from inside
# the JVM never touches the Tomcat worker pool or the Netty event loops, so it cannot show
# either of them filling up.
set -u

MVC="${MVC_URL:-http://localhost:8080}"
REACTIVE="${REACTIVE_URL:-http://localhost:8081}"
UPSTREAM="${UPSTREAM_URL:-http://localhost:8090}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DRIVER_CP="$ROOT_DIR/sonify-scenarios/target/classes"

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }
dim()  { printf '\033[2m%s\033[0m\n' "$1"; }

check() {
  local failed=0
  command -v redis-cli >/dev/null 2>&1 && [ "$(redis-cli ping 2>/dev/null)" = "PONG" ] \
    || { echo "  redis      NOT reachable  (brew services start redis)"; failed=1; }
  pg_isready -q 2>/dev/null \
    || { echo "  postgres   NOT reachable  (brew services start postgresql@16)"; failed=1; }
  curl -sf -o /dev/null "$UPSTREAM/health" \
    || { echo "  upstream   NOT reachable  (see 'upstream' below)"; failed=1; }
  curl -sf -o /dev/null "$MVC/actuator/health" \
    || { echo "  demo       NOT reachable  ($MVC)"; failed=1; }
  if [ $failed -eq 1 ]; then
    echo
    echo "  upstream: java -cp $DRIVER_CP net.bitsar.sonify.scenarios.upstream.UpstreamServer 8090"
    echo "  demo    : java -Xmx512m -jar sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar"
    echo "  reactive: java -Xmx512m -jar sonify-demo-reactive/target/sonify-demo-reactive-0.1.0-SNAPSHOT.jar"
    exit 1
  fi
}

list() {
  curl -s "$MVC/scenario" | python3 -c '
import sys, json
rows = json.load(sys.stdin)
category = None
for row in rows:
    if row["category"] != category:
        category = row["category"]
        print(f"\n  {category}")
    tag = "" if row["transport"] == "ANY" else f"  [{row['transport'].lower()} only]"
    print(f"    {row[\"id\"]:<26} {row[\"name\"]}{tag}")
'
  echo
}

describe() {
  curl -s "$MVC/scenario" | python3 -c "
import sys, json
for row in json.load(sys.stdin):
    if row['id'] == '$1':
        print()
        print('  ' + row['name'])
        print('  situation : ' + row['situation'])
        print('  graph     : ' + row['expectedGraph'])
        print('  sound     : ' + row['expectedSound'])
"
}

run_in_jvm() {
  local id="$1" users="${2:-50}" seconds="${3:-30}"
  describe "$id"
  dim "  driving from inside the JVM with $users users for ${seconds}s"
  curl -s "$MVC/scenario/$id/run?users=$users&seconds=$seconds" \
    | python3 -c '
import sys, json
o = json.load(sys.stdin)
print(f"    {o[\"completed\"]} ok, {o[\"failed\"]} failed, {o[\"throughputPerSecond\"]:.1f}/s")
print(f"    p50 {o[\"p50Ms\"]}ms  p95 {o[\"p95Ms\"]}ms  p99 {o[\"p99Ms\"]}ms  max {o[\"maxMs\"]}ms")
print(f"    {o[\"note\"]}")
'
}

run_http() {
  local id="$1" users="${2:-50}" seconds="${3:-30}" base="${4:-$MVC}"
  describe "$id"
  dim "  driving over HTTP at $base with $users users for ${seconds}s"
  java -cp "$DRIVER_CP" net.bitsar.sonify.scenarios.LoadDriver \
       "$base/scenario/$id/once" "$users" "$seconds"
}

compare() {
  local id="$1" users="${2:-50}" seconds="${3:-20}"
  curl -sf -o /dev/null "$REACTIVE/actuator/health" || {
    echo "the reactive application is not running at $REACTIVE"; exit 1; }
  bold "$id on Tomcat (thread per request)"
  java -cp "$DRIVER_CP" net.bitsar.sonify.scenarios.LoadDriver "$MVC/scenario/$id/once" "$users" "$seconds"
  curl -s -o /dev/null "$MVC/scenario/reset"
  bold "$id on Netty (event loop)"
  java -cp "$DRIVER_CP" net.bitsar.sonify.scenarios.LoadDriver "$REACTIVE/scenario/$id/once" "$users" "$seconds"
  curl -s -o /dev/null "$REACTIVE/scenario/reset"
}

all() {
  local users="${1:-50}" seconds="${2:-25}"
  curl -s -o /dev/null "$MVC/scenario/warm"
  for id in $(curl -s "$MVC/scenario" | python3 -c '
import sys, json
for row in json.load(sys.stdin):
    if row["transport"] != "REACTIVE":
        print(row["id"])
'); do
    bold "$id"
    run_in_jvm "$id" "$users" "$seconds"
    curl -s "$MVC/scenario/reset" | sed 's/^/    reset: /'
    dim "  settling for 8s"
    sleep 8
  done
  bold "Reactive-only scenarios"
  dim "  start the reactive app and run: ./scenarios.sh compare conc-thread-per-request"
}

case "${1:-list}" in
  list)     check; list ;;
  run)      check; run_in_jvm "${2:?scenario id required}" "${3:-50}" "${4:-30}" ;;
  http)     check; run_http "${2:?scenario id required}" "${3:-50}" "${4:-30}" ;;
  reactive) check; run_http "${2:?scenario id required}" "${3:-50}" "${4:-30}" "$REACTIVE" ;;
  compare)  check; compare "${2:?scenario id required}" "${3:-50}" "${4:-20}" ;;
  all)      check; all "${2:-50}" "${3:-25}" ;;
  *)        sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
