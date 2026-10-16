#!/usr/bin/env bash
#
# Drives the demo application through the story the UI is built to show:
#
#   calm -> allocation spike -> contention storm -> leak -> recovery
#
# Open http://localhost:8080/sonify/ first, press "Play sound", then press
# "Record clip" and run this. Plain bash and curl, so there is nothing to install.
#
#   ./story.sh [base-url]
set -u

BASE="${1:-http://localhost:8080}"

bold()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
note()  { printf '  \033[2m%s\033[0m\n' "$1"; }
call()  { curl -s -o /dev/null "$BASE$1" || printf '  (request failed: %s)\n' "$1"; }

countdown() {
  local seconds="$1"
  while [ "$seconds" -gt 0 ]; do
    printf '\r  %2ds ' "$seconds"
    sleep 1
    seconds=$((seconds - 1))
  done
  printf '\r      \r'
}

if ! curl -sf -o /dev/null "$BASE/actuator/health"; then
  echo "Cannot reach $BASE - start the demo first:" >&2
  SONIFY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  . "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_paths.sh"
  echo "  java -Xmx512m -jar $(sonify_jar sonify-demo 2>/dev/null || echo sonify-demo/target/sonify-demo-0.1.0-SNAPSHOT.jar)" >&2
  exit 1
fi

call "/load/calm"

bold "1. Calm"
note "A steady drone, an occasional collection, a pentatonic note now and then."
countdown 12

bold "2. Allocation spike"
note "Garbage at speed. The hi-hat quickens and young collections start to drum."
for _ in 1 2 3 4 5 6; do
  call "/load/alloc?mb=400"
  sleep 2
done

bold "3. Contention storm"
note "Twenty threads queue for one monitor. Detuned squares pile into a cluster, and the melody drops out."
call "/load/lock?threads=20&ms=60"
countdown 16

bold "4. Leak"
note "Memory is retained, so the drone climbs and collections reclaim less and less."
for _ in 1 2 3 4 5 6; do
  call "/load/leak?mb=60"
  sleep 2
done
note "Listen for the low swell: a collection that freed almost nothing."
countdown 8

bold "5. Recovery"
note "Everything is released. The drone falls, the hi-hat slows, the melody returns."
call "/load/calm"
countdown 14

bold "Done"
note "Press 'Record clip' again to save the WebM."
