#!/usr/bin/env bash
# Interleaved HEAD-vs-baseline differential. Load on this box swings wildly, so running
# one side then the other would confound version with load. Alternate instead.
OUT=/Users/jerry/zimu-work/integration/.scratch/vitest-flake/delaynull
mkdir -p "$OUT"
for i in 1 2 3; do
  for side in HEAD BASE; do
    case $side in
      HEAD) DIR=/Users/jerry/zimu-work/integration/frontend ;;
      BASE) DIR=/Users/jerry/zimu-work/int-baseline/frontend ;;
    esac
    l=$(uptime | sed -E 's/.*load averages?: ([0-9.]+).*/\1/')
    (cd "$DIR" && npx vitest run --testTimeout=60000 --reporter=verbose) > "$OUT/$side-$i.log" 2>&1
    tot=$(grep -oE "^\s*[✓×] .* [0-9]+ms$" "$OUT/$side-$i.log" | grep -oE "[0-9]+ms$" | tr -d 'ms' | paste -sd+ - | bc)
    res=$(grep -oE "Tests +[0-9]+ failed \| [0-9]+ passed|Tests +[0-9]+ passed" "$OUT/$side-$i.log" | tail -1)
    printf "iter=%s %-4s load1=%-6s sum_test_ms=%-7s %s\n" "$i" "$side" "$l" "${tot:-?}" "$res"
  done
done
