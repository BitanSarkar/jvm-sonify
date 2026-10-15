#!/usr/bin/env bash
#
# Measures what JVM Sonify costs while someone is watching.
#
# The experiment is deliberately paired. One JVM is started with sonify.enabled=true, and the
# only thing that changes between trials is whether a client is subscribed, because Sonify
# measures nothing until one is. Trials alternate so JIT state, heap occupancy and whatever
# else the machine is doing affect both conditions equally, and the workload runs inside the
# JVM on one request rather than from a shell loop, which would measure the cost of spawning
# curl instead of anything about the application.
#
#   ./overhead.sh [path-to-jar] [trials-per-condition]
set -u

JAR="${1:-sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar}"
TRIALS="${2:-5}"
PORT=8099
BASE="http://localhost:$PORT"
WORKLOAD="mb=4000&threads=8&cycles=1200"

if [ ! -f "$JAR" ]; then
  echo "Jar not found: $JAR" >&2
  echo "Build it first: mvn -q -DskipTests package" >&2
  exit 1
fi

cleanup() {
  [ -n "${STREAM_PID:-}" ] && kill "$STREAM_PID" 2>/dev/null
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
}
trap cleanup EXIT

echo "JVM Sonify overhead"
echo "machine : $(uname -sm), $(sysctl -n hw.ncpu 2>/dev/null || nproc) cpus"
echo "runtime : $(java -version 2>&1 | head -1)"
echo "workload: $WORKLOAD, $TRIALS alternating trials per condition"
echo

java -Xmx1g -XX:+UseG1GC -jar "$JAR" \
     --server.port=$PORT \
     --sonify.enabled=true \
     --sonify.grace-period=1s \
     --logging.level.root=WARN > /tmp/sonify-overhead.log 2>&1 &
APP_PID=$!

for _ in $(seq 1 60); do
  curl -sf -o /dev/null "$BASE/actuator/health" && break
  sleep 1
done
if ! curl -sf -o /dev/null "$BASE/actuator/health"; then
  echo "application did not start; see /tmp/sonify-overhead.log" >&2
  exit 1
fi

echo "warming up (JIT, heap sizing)"
curl -s -o /dev/null "$BASE/benchmark?$WORKLOAD"
curl -s -o /dev/null "$BASE/benchmark?$WORKLOAD"

field() { sed -n "s/.*\"$2\":\([0-9]*\).*/\1/p" <<< "$1"; }

: > /tmp/sonify-overhead-off
: > /tmp/sonify-overhead-on

measure_without() {
  local result
  result=$(curl -s "$BASE/benchmark?$WORKLOAD")
  printf '%s %s\n' "$(field "$result" wallMs)" "$(field "$result" cpuMs)" >> /tmp/sonify-overhead-off
  printf '    without a client : wall %5sms  cpu %6sms\n' \
         "$(field "$result" wallMs)" "$(field "$result" cpuMs)"
}

measure_with() {
  local result
  curl -sN "$BASE/sonify/stream" > /tmp/sonify-overhead-stream.out 2>&1 &
  STREAM_PID=$!
  sleep 2
  result=$(curl -s "$BASE/benchmark?$WORKLOAD")
  printf '%s %s\n' "$(field "$result" wallMs)" "$(field "$result" cpuMs)" >> /tmp/sonify-overhead-on
  printf '    with a client    : wall %5sms  cpu %6sms\n' \
         "$(field "$result" wallMs)" "$(field "$result" cpuMs)"
  kill $STREAM_PID 2>/dev/null
  STREAM_PID=""
  sleep 3   # longer than the 1s grace period, so the pipeline really stops again
}

for trial in $(seq 1 "$TRIALS"); do
  printf '  trial %d\n' "$trial"
  # ABBA: the condition that goes first alternates, so neither position is favoured.
  if [ $((trial % 2)) -eq 1 ]; then
    measure_without
    measure_with
  else
    measure_with
    measure_without
  fi
done

echo
echo "events observed during the measured runs:"
curl -s "$BASE/actuator/sonify"
echo
echo

awk '
  function median(values, n,   i, j, tmp) {
    for (i = 2; i <= n; i++) { tmp = values[i]; for (j = i-1; j >= 1 && values[j] > tmp; j--) values[j+1] = values[j]; values[j+1] = tmp; }
    return (n % 2) ? values[(n+1)/2] : (values[n/2] + values[n/2+1]) / 2;
  }
  FILENAME ~ /off$/ { offWall[++o] = $1; offCpu[o] = $2; next }
  { onWall[++n] = $1; onCpu[n] = $2 }
  END {
    ow = median(offWall, o); oc = median(offCpu, o);
    nw = median(onWall, n);  nc = median(onCpu, n);
    cpuDelta = 100*(nc-oc)/oc;
    wallDelta = 100*(nw-ow)/ow;
    spread = 100*(offCpu[o] - offCpu[1])/oc;
    printf "  median wall time : %6.0fms -> %6.0fms   %+.1f%%\n", ow, nw, wallDelta;
    printf "  median cpu time  : %6.0fms -> %6.0fms   %+.1f%%\n", oc, nc, cpuDelta;
    printf "  run-to-run spread within the unwatched condition alone: %.1f%%\n", spread;
    printf "\n";
    if (cpuDelta < 0 || (cpuDelta < spread))
      printf "  Overhead is smaller than this harness can resolve: the measured difference\n  (%+.1f%%) is inside the %.1f%% spread of the baseline condition itself.\n  Report it as below that bound, not as a point estimate.\n", cpuDelta, spread;
    else
      printf "  overhead while being watched: %+.1f%% cpu (baseline spread %.1f%%)\n", cpuDelta, spread;
    printf "  With nobody connected it is zero: the recording stream is not running.\n";
  }
' /tmp/sonify-overhead-off /tmp/sonify-overhead-on
