#!/usr/bin/env bash
# Extract per-test durations from a verbose vitest log, sorted desc. Shows headroom vs the 5000ms budget.
grep -oE "^\s*[✓×] .*[0-9]+ms$" "$1" | sed -E 's/^\s+//' | awk '{
  m=$0; sub(/ms$/,"",m); n=split(m,a," "); d=a[n]+0;
  printf "%6dms  %s\n", d, substr($0, 1, length($0)-length(a[n])-2)
}' | sort -rn
