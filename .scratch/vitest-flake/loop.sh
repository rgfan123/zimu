#!/usr/bin/env bash
# Phase 1 feedback loop: run the frontend component suite N times, record per-test verdicts.
# Usage: loop.sh <runs> <label> [extra vitest args...]
set -uo pipefail
RUNS="${1:?runs}"; LABEL="${2:?label}"; shift 2
FE=/Users/jerry/zimu-work/integration/frontend
OUT=/Users/jerry/zimu-work/integration/.scratch/vitest-flake/$LABEL
mkdir -p "$OUT"
echo "== label=$LABEL runs=$RUNS args=$* =="
echo "== load at start: $(uptime | sed 's/.*load/load/') =="
for i in $(seq 1 "$RUNS"); do
  start=$(date +%s)
  (cd "$FE" && npx vitest run --reporter=verbose "$@") > "$OUT/run-$i.log" 2>&1
  code=$?
  end=$(date +%s)
  fails=$(grep -cE "^\s*(×|✗|FAIL)" "$OUT/run-$i.log" 2>/dev/null || echo 0)
  passed=$(grep -oE "Tests +[0-9]+ failed \| [0-9]+ passed|Tests +[0-9]+ passed" "$OUT/run-$i.log" | tail -1)
  printf "run %-3s exit=%-3s wall=%-4ss  %s\n" "$i" "$code" "$((end-start))" "${passed:-NO-SUMMARY}"
  grep -E "^\s*×" "$OUT/run-$i.log" | sed 's/^/      RED: /'
done
echo "== load at end: $(uptime | sed 's/.*load/load/') =="
