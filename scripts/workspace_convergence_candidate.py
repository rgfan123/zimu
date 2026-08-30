"""Current-state-only candidate integration proofs."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from workspace_convergence_common import (
    DEFAULT_MAX_PATCH_BYTES,
    ObservationError,
    RecordError,
    env_limit,
    git,
    inspect_path,
    safe_read,
)
from workspace_convergence_snapshot import observe_worktree


def _patch_limit() -> int:
    return env_limit(
        "WORKSPACE_CONVERGENCE_MAX_PATCH_BYTES",
        DEFAULT_MAX_PATCH_BYTES,
        "invalid_patch_limit",
    )


def _read_patch(path: Path) -> tuple[bytes, str, str]:
    try:
        result = safe_read(path, _patch_limit(), "candidate_patch_too_large")
    except ObservationError as error:
        reason = error.reason if "too_large" in error.reason else "candidate_path_not_regular"
        raise RecordError("INVALID_INPUT", reason) from error
    return result.content, result.signature, result.path


def _patch_ids(target: Path, patch: bytes) -> list[str]:
    if not patch.strip():
        raise RecordError("INVALID_INPUT", "candidate_patch_invalid_or_empty")
    completed = git(
        target,
        "patch-id",
        "--stable",
        allow_failure=True,
        binary=True,
        content=patch,
    )
    if completed.returncode != 0:
        raise RecordError("INVALID_INPUT", "candidate_patch_invalid_or_empty")
    identifiers = [os.fsdecode(line.split()[0]) for line in completed.stdout.splitlines() if line.split()]
    if not identifiers:
        raise RecordError("INVALID_INPUT", "candidate_patch_invalid_or_empty")
    return identifiers


def _evaluate_patch(target: Path, patch: bytes) -> tuple[bool, list[str], dict[str, Any]]:
    if len(patch) > _patch_limit():
        raise RecordError("INVALID_INPUT", "candidate_patch_too_large")
    identifiers = _patch_ids(target, patch)
    reverse = git(
        target,
        "apply",
        "--reverse",
        "--check",
        "--whitespace=nowarn",
        "-",
        allow_failure=True,
        binary=True,
        content=patch,
    )
    detail = {"kind": "patch", "patch_ids": identifiers}
    if reverse.returncode == 0:
        return True, ["candidate_patch_absorbed"], detail
    return False, ["candidate_not_integrated"], detail


def _resolve_commit(target: Path, candidate: str) -> str:
    resolved = git(
        target,
        "rev-parse",
        "--verify",
        "--end-of-options",
        f"{candidate}^{{commit}}",
        allow_failure=True,
    )
    if resolved.returncode != 0:
        raise RecordError("INVALID_INPUT", "candidate_ref_not_found")
    return resolved.stdout.strip()


def _commit_absorbed(target: Path, commit: str) -> tuple[bool, list[str], dict[str, Any]]:
    patch = git(
        target,
        "show",
        "--pretty=format:",
        "--binary",
        "--no-ext-diff",
        commit,
        "--",
        binary=True,
    ).stdout
    ancestor = git(
        target,
        "merge-base",
        "--is-ancestor",
        commit,
        "HEAD",
        allow_failure=True,
    )
    if not patch.strip():
        detail = {"kind": "commit", "commit": commit, "current_patch": None}
        if ancestor.returncode == 0:
            return True, ["candidate_commit_reachable"], detail
        return False, ["candidate_not_integrated"], detail
    patch_integrated, _, patch_detail = _evaluate_patch(target, patch)
    detail = {"kind": "commit", "commit": commit, "current_patch": patch_detail}
    if ancestor.returncode == 0 and patch_integrated:
        return True, ["candidate_commit_reachable"], detail
    if ancestor.returncode == 0:
        return False, ["candidate_not_integrated"], detail
    merged = git(
        target,
        "merge-tree",
        "--write-tree",
        "HEAD",
        commit,
        allow_failure=True,
    )
    if merged.returncode == 0 and merged.stdout.splitlines():
        merged_tree = merged.stdout.splitlines()[0].strip()
        target_tree = git(target, "rev-parse", "HEAD^{tree}").stdout.strip()
        if merged_tree == target_tree:
            detail["proof"] = "current_tree_merge"
            return True, ["candidate_patch_absorbed"], detail
    return False, ["candidate_not_integrated"], detail


def _path_kind(candidate: str) -> tuple[str, Path] | None:
    path = Path(candidate)
    try:
        os.lstat(path)
    except FileNotFoundError:
        return None
    except OSError as error:
        raise RecordError("INVALID_INPUT", "candidate_path_unreadable") from error
    try:
        return inspect_path(path)
    except ObservationError as error:
        raise RecordError("INVALID_INPUT", "candidate_path_not_regular") from error


def evaluate_candidate(target: Path, candidate: str) -> dict[str, Any]:
    path_kind = _path_kind(candidate)
    if path_kind is None:
        commit = _resolve_commit(target, candidate)
        integrated, reasons, detail = _commit_absorbed(target, commit)
        return {
            "integrated": integrated,
            "reasons": reasons,
            "detail": detail,
            "freeze": {"kind": "ref", "candidate": candidate, "signature": commit},
        }
    kind, path = path_kind
    if kind == "file":
        patch, signature, canonical = _read_patch(path)
        integrated, reasons, detail = _evaluate_patch(target, patch)
        detail["path"] = canonical
        return {
            "integrated": integrated,
            "reasons": reasons,
            "detail": detail,
            "freeze": {"kind": "file", "path": canonical, "signature": signature},
        }
    return _evaluate_worktree(target, path)


def _evaluate_worktree(target: Path, candidate_root: Path) -> dict[str, Any]:
    root_result = git(candidate_root, "rev-parse", "--show-toplevel", allow_failure=True)
    if root_result.returncode != 0:
        raise RecordError("INVALID_INPUT", "candidate_path_not_git_worktree")
    root = Path(root_result.stdout.strip()).resolve()
    if root != candidate_root.resolve():
        raise RecordError("INVALID_INPUT", "candidate_path_not_worktree_root")
    observation = observe_worktree(root)
    if observation["reasons"]:
        raise ObservationError(observation["reasons"][0])
    if observation["changes"]["untracked"]["paths"]:
        raise ObservationError("candidate_untracked_not_representable")
    head_integrated, head_reasons, detail = _commit_absorbed(target, observation["head"])
    dirty = any(observation["changes"][name]["paths"] for name in ("staged", "unstaged"))
    integrated, reasons = head_integrated, head_reasons
    if dirty:
        patch = git(
            root,
            "diff",
            "HEAD",
            "--binary",
            "--full-index",
            "--no-ext-diff",
            "--no-textconv",
            "--",
            binary=True,
        ).stdout
        patch_integrated, patch_reasons, patch_detail = _evaluate_patch(target, patch)
        integrated = head_integrated and patch_integrated
        reasons = sorted(set(head_reasons + patch_reasons))
        detail["worktree_patch"] = patch_detail
    detail.update({"kind": "worktree", "path": str(root), "head": observation["head"]})
    return {
        "integrated": integrated,
        "reasons": reasons,
        "detail": detail,
        "freeze": {"kind": "worktree", "path": str(root), "signature": observation["signature"]},
    }


def candidate_signature(target: Path, freeze: dict[str, str]) -> str:
    if freeze["kind"] == "file":
        return _read_patch(Path(freeze["path"]))[1]
    if freeze["kind"] == "worktree":
        return observe_worktree(Path(freeze["path"]))["signature"]
    return _resolve_commit(target, freeze["candidate"])
