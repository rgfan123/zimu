#!/usr/bin/env bash
# Synthetic CPU load: N busy workers for S seconds. Used to reproduce the "backend mvn test
# is hogging the box" condition on demand, so the flake is testable instead of anecdotal.
N="${1:-8}"; S="${2:-180}"
for i in $(seq 1 "$N"); do
  ( end=$((SECONDS+S)); while [ $SECONDS -lt $end ]; do :; done ) &
done
echo "loadgen: $N workers for ${S}s (pids $(jobs -p | tr '\n' ' '))"
wait
