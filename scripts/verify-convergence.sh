#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
LEDGER="${CONVERGENCE_LEDGER:-$REPO_ROOT/.scratch/workspace-convergence-20260830/sources.json}"
EXPECTED_BRANCH="${CONVERGENCE_BRANCH:-codex/workspace-convergence-20260830}"
MODE="full"
WATCHDOG_PID=""
WATCHDOG_DIR=""
WATCHDOG_FLAG=""

if [[ ${1:-} == "--preflight-only" ]]; then
  MODE="preflight"
elif [[ $# -gt 0 ]]; then
  echo "用法: bash scripts/verify-convergence.sh [--preflight-only]" >&2
  exit 64
fi

for command in git python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "缺少必需命令: $command" >&2
    exit 69
  fi
done

if [[ ! -f "$LEDGER" ]]; then
  echo "来源账本不存在: $LEDGER" >&2
  exit 66
fi

assert_clean() {
  local status
  status="$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)"
  if [[ -n "$status" ]]; then
    echo "收敛 worktree 不是干净状态，拒绝把当前结果标为已验收:" >&2
    echo "$status" >&2
    return 1
  fi
}

capture_target_identity() {
  TARGET_HEAD="$(git -C "$REPO_ROOT" rev-parse --verify HEAD)"
  TARGET_TREE="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{tree}')"
  TARGET_BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD || true)"
  if [[ -z "$TARGET_BRANCH" ]]; then
    echo "收敛 worktree 处于 detached HEAD，拒绝验收" >&2
    return 1
  fi
  if [[ "$TARGET_BRANCH" != "$EXPECTED_BRANCH" ]]; then
    echo "收敛分支错误: expected=$EXPECTED_BRANCH actual=$TARGET_BRANCH" >&2
    return 1
  fi
}

assert_target_identity_unchanged() {
  local current_head current_tree current_branch
  current_head="$(git -C "$REPO_ROOT" rev-parse --verify HEAD)"
  current_tree="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{tree}')"
  current_branch="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD || true)"
  if [[ "$current_head" != "$TARGET_HEAD" \
      || "$current_tree" != "$TARGET_TREE" \
      || "$current_branch" != "$TARGET_BRANCH" ]]; then
    echo "验收期间目标 Git 身份发生变化，测试结果不再对应当前 HEAD" >&2
    echo "before branch=$TARGET_BRANCH head=$TARGET_HEAD tree=$TARGET_TREE" >&2
    echo "after  branch=${current_branch:-DETACHED} head=$current_head tree=$current_tree" >&2
    return 1
  fi
}

cleanup_target_watchdog() {
  if [[ -n "$WATCHDOG_PID" ]]; then
    for job_pid in $(jobs -pr); do
      if [[ "$job_pid" == "$WATCHDOG_PID" ]]; then
        kill "$WATCHDOG_PID" 2>/dev/null || true
        break
      fi
    done
    # wait 只回收当前 shell 的 child；即使缓存 PID 已被系统复用，也不会作用于无关进程。
    wait "$WATCHDOG_PID" 2>/dev/null || true
  fi
  WATCHDOG_PID=""
  if [[ -n "$WATCHDOG_FLAG" && -f "$WATCHDOG_FLAG" ]]; then
    rm -f "$WATCHDOG_FLAG"
  fi
  if [[ -n "$WATCHDOG_DIR" && -d "$WATCHDOG_DIR" ]]; then
    rmdir "$WATCHDOG_DIR" 2>/dev/null || true
  fi
}

start_target_watchdog() {
  local temp_root
  temp_root="${TMPDIR:-/tmp}"
  WATCHDOG_DIR="$(mktemp -d "$temp_root/zimu-convergence-watch.XXXXXX")"
  WATCHDOG_FLAG="$WATCHDOG_DIR/changed"
  (
    while true; do
      local_head="$(git -C "$REPO_ROOT" -c core.fsmonitor=false rev-parse --verify HEAD 2>/dev/null || true)"
      local_branch="$(git -C "$REPO_ROOT" -c core.fsmonitor=false symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
      local_status="$(git -C "$REPO_ROOT" -c core.fsmonitor=false status --porcelain=v1 --untracked-files=all 2>/dev/null || printf '__OBSERVATION_FAILED__')"
      if [[ "$local_head" != "$TARGET_HEAD" \
          || "$local_branch" != "$TARGET_BRANCH" \
          || -n "$local_status" ]]; then
        printf 'branch=%s head=%s status=%s\n' \
          "${local_branch:-DETACHED}" "${local_head:-UNKNOWN}" "${local_status:-clean}" \
          > "$WATCHDOG_FLAG"
        exit 0
      fi
      sleep 0.2
    done
  ) &
  WATCHDOG_PID=$!
  trap cleanup_target_watchdog EXIT INT TERM
}

assert_watchdog_clean() {
  if [[ -n "$WATCHDOG_FLAG" && -f "$WATCHDOG_FLAG" ]]; then
    echo "验收期间检测到目标工作树变化，测试证据作废:" >&2
    sed -n '1p' "$WATCHDOG_FLAG" >&2
    return 1
  fi
}

verify_sources() {
  bash "$REPO_ROOT/scripts/check-baseline.sh" \
    --ledger "$LEDGER" \
    --target "$REPO_ROOT" \
    --json
}

verify_migration_numbers() {
  python3 - "$REPO_ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
migration_dir = root / "backend/src/main/resources/db/migration"
by_version: dict[int, list[str]] = {}
for path in migration_dir.glob("V*__*.sql"):
    match = re.fullmatch(r"V(\d+)__.+\.sql", path.name)
    if match is None:
        raise SystemExit(f"非法迁移文件名: {path.name}")
    by_version.setdefault(int(match.group(1)), []).append(path.name)

duplicates = {version: names for version, names in by_version.items() if len(names) > 1}
if duplicates:
    raise SystemExit(f"迁移版本重复: {duplicates}")
if not by_version:
    raise SystemExit("未发现迁移文件")
print(f"migration_versions=unique highest=V{max(by_version)} count={len(by_version)}")
PY
}

cd "$REPO_ROOT"
assert_clean
capture_target_identity
verify_sources
verify_migration_numbers

if [[ "$MODE" == "preflight" ]]; then
  echo "CONVERGENCE_PREFLIGHT_READY"
  exit 0
fi

for command in mvn node npm; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "缺少完整验收命令: $command" >&2
    exit 69
  fi
done
if [[ ! -f "$REPO_ROOT/frontend/package-lock.json" ]]; then
  echo "frontend/package-lock.json 不存在，无法执行可重复 npm ci" >&2
  exit 69
fi

start_target_watchdog

echo "[gate] workspace/pre-work/verifier CLI tests"
python3 -m unittest scripts/test_workspace_convergence.py scripts/test_verify_convergence.py

if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 24)}"
fi
unset JD_LOP_CLIENT_MODE || true
(
  cd backend
  echo "[gate] backend focused convergence tests"
  mvn -q \
    -Dtest=McpModulesEnvExampleTest,McpToolRegistryModuleFilterTest,McpProtocolAcceptanceTest,McpHttpTransportAcceptanceTest,McpBundleReadToolsUnitTest,McpBundleReadToolsTest,SkuSearchApiTest,SourceBundleKeyUnificationApiTest,OrderLineBundleResolutionApiTest,CaishixianShipmentArtifactFactoryTest,StructuredImportApiTest,MixedProviderStaticBundlePipelineApiTest,V89SourceSkuRefFreezeSqlContractTest,ProductionMigrationHistoryCompatTest,SchemaSnapshotMigrationEquivalenceTest \
    test

  # 聚焦报告不能替代完整套件；clean 清除任何旧/并行 Surefire 产物后重新生成可归属证据。
  echo "[gate] backend full clean test"
  mvn -q clean test
)

(
  cd frontend
  echo "[gate] frontend lockfile dependency install"
  npm ci
  echo "[gate] frontend typecheck"
  npm run typecheck
  echo "[gate] frontend unit tests"
  npm run test:unit
  echo "[gate] frontend component tests"
  npm run test:component
  echo "[gate] frontend production build"
  npm run build
)

# 长测试期间来源分支仍可能移动；完成时必须再次观察，且构建产物不得污染 Git 状态。
verify_sources
assert_target_identity_unchanged
assert_clean
assert_watchdog_clean
cleanup_target_watchdog
trap - EXIT INT TERM
echo "CONVERGENCE_VERIFIED"
