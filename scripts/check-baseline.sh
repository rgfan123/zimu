#!/usr/bin/env bash
# 基线自检：确认当前工作区在「项目在根目录」这条历史线上。
#
# 为什么需要它
# ------------
# 这个仓里有两条**无共同祖先**的历史（`git merge-base main master` 返回空）：
#
#   镜像线：只有 `main` 一个分支。项目被 vendor 进 jry/ 子目录，顶层只有 README.md / fan / jry
#   开发线：`master` → `jry/integration-20260828` 及其余全部分支，项目在根目录
#
# **「落后」和「站错线」是两回事，别混。** master 是 integration 的直系祖先，只是落后
# 一百多个提交——从它派生的 worktree rebase 一下就能用，不必重做。真正白干的只有
# 落在镜像线（顶层 jry/）上的那种：它跟开发线没有共同祖先，改了也进不了主线。
#
# 所以本脚本的判据是**项目布局**：顶层有 backend/ frontend/ 就算在开发线上（可能旧，
# 那是另一个问题）；顶层只有 jry/ 才是落错线。
#
# 一个 .git 上挂着 50+ 个 worktree，新建的 worktree 继承创建它时所在的分支。
# 只要那一刻在 A 线，新 worktree 就落在 A 线——2026-08-29 有 agent 就是这么开局的，
# 全靠它自己发现并 reset 才没白干；也有别的 agent 因此报「文件不存在」。
#
# 症状长得像「工作区乱」，其实是一个确定的、可检测的状态。开工前跑一次即可。
#
# 用法：bash scripts/check-baseline.sh   （在错的线上退出码非 0）
#
# 防重复开工门禁（只读，不 fetch、不写 refs/index/工作树）：
#   bash scripts/check-baseline.sh --pre-work --target WORKTREE --baseline COMMIT \
#     --work-item STABLE_ID --intent normalized-intent \
#     [--candidate REF_OR_PATCH_OR_WORKTREE] [--registry FILE] --json
# 只有 START_ALLOWED 返回 0；其余稳定裁决返回 1；参数或记录非法返回 2。

set -euo pipefail

if [ "${1:-}" = "--ledger" ] || [ "${1:-}" = "--pre-work" ]; then
    script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
    exec python3 "$script_dir/workspace_convergence.py" "$@"
fi

if [ "$#" -ne 0 ]; then
    echo "用法：bash scripts/check-baseline.sh [--ledger FILE --target WORKTREE [--json]]" >&2
    echo "   或：bash scripts/check-baseline.sh --pre-work --target WORKTREE --baseline COMMIT --work-item ID --intent NORMALIZED_INTENT [--candidate REF_OR_PATH] [--registry FILE] [--json]" >&2
    exit 2
fi

root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$root" ]; then
    echo "❌ 当前目录不在 git 仓库里" >&2
    exit 2
fi

# 判据取「项目布局」而不是分支名：分支名会随票变，布局不会。
if [ -d "$root/backend" ] && [ -d "$root/frontend" ]; then
    echo "✅ 基线正确：项目在根目录（$(git -C "$root" log --oneline -1)）"
    exit 0
fi

echo "❌ 基线错误：这个工作区不在开发历史线上。" >&2
echo "" >&2
echo "   当前位置：$root" >&2
echo "   当前 HEAD：$(git -C "$root" log --oneline -1 2>/dev/null || echo '未知')" >&2
echo "   顶层内容：$(ls "$root" | head -5 | tr '\n' ' ')" >&2
echo "" >&2
if [ -d "$root/jry/backend" ]; then
    echo "   看起来落在了「项目嵌在 jry/ 子目录」的镜像线上。" >&2
fi
echo "   这条线与开发线**没有共同祖先**，在这里改代码不会进入主线。" >&2
echo "" >&2
echo "   怎么办：切到开发线，或换到已在开发线上的工作区。" >&2
echo "     git checkout --detach jry/integration-20260828" >&2
echo "   （分支被别的 worktree 占用时必须用 --detach；直接 checkout 会被 git 拒绝）" >&2
exit 1
