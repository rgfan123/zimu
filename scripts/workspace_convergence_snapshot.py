"""Stable worktree and registry snapshots used by the convergence gate."""

from __future__ import annotations

import hashlib
import os
import stat
from pathlib import Path
from typing import Any

from workspace_convergence_common import (
    ObservationError,
    digest_frames,
    env_limit,
    git,
    safe_read,
)


DEFAULT_MAX_UNTRACKED_BYTES = 128 * 1024 * 1024
WORK_ITEM_MARKER = ".workspace-work-item.json"
GIT_OPERATION_MARKERS = (
    "MERGE_HEAD",
    "CHERRY_PICK_HEAD",
    "REVERT_HEAD",
    "REBASE_HEAD",
    "rebase-merge",
    "rebase-apply",
    "sequencer",
)


def nul_paths(output: bytes) -> list[bytes]:
    return sorted(value for value in output.split(b"\0") if value)


def branch(worktree: Path) -> str | None:
    completed = git(
        worktree,
        "symbolic-ref",
        "--quiet",
        "--short",
        "HEAD",
        allow_failure=True,
    )
    return completed.stdout.strip() if completed.returncode == 0 else None


def git_path(worktree: Path, name: str) -> Path:
    value = Path(git(worktree, "rev-parse", "--git-path", name).stdout.strip())
    return value if value.is_absolute() else worktree / value


def has_git_operation(worktree: Path) -> bool:
    return any(git_path(worktree, marker).exists() for marker in GIT_OPERATION_MARKERS)


def worktree_registry(target: Path) -> dict[str, dict[str, str | None]]:
    records: dict[str, dict[str, str | None]] = {}
    current: dict[str, str | None] | None = None
    output = git(target, "worktree", "list", "--porcelain").stdout
    for line in output.splitlines():
        if line.startswith("worktree "):
            path = str(Path(line.removeprefix("worktree ")).resolve())
            current = {"path": path, "head": None, "branch": None}
            records[path] = current
        elif current is not None and line.startswith("HEAD "):
            current["head"] = line.removeprefix("HEAD ")
        elif current is not None and line.startswith("branch refs/heads/"):
            current["branch"] = line.removeprefix("branch refs/heads/")
    return records


def _untracked_content(path: Path, observed: os.stat_result) -> tuple[bytes, bytes]:
    maximum = env_limit(
        "WORKSPACE_CONVERGENCE_MAX_UNTRACKED_BYTES",
        DEFAULT_MAX_UNTRACKED_BYTES,
        "invalid_untracked_limit",
    )
    if stat.S_ISLNK(observed.st_mode):
        target = os.readlink(path)
        content = os.fsencode(target)
        if len(content) > maximum:
            raise ObservationError("untracked_snapshot_too_large")
        current = os.lstat(path)
        if _stat_identity(current) != _stat_identity(observed):
            raise ObservationError("source_changed_during_scan")
        return b"symlink", content
    if not stat.S_ISREG(observed.st_mode):
        raise ObservationError("untracked_special_file")
    result = safe_read(path, maximum, "untracked_snapshot_too_large")
    executable = b"executable" if observed.st_mode & 0o111 else b"regular"
    return executable, result.content


def _stat_identity(value: os.stat_result) -> tuple[int, int, int, int]:
    return value.st_mode, value.st_ino, value.st_size, value.st_mtime_ns


def snapshot(
    worktree: Path,
    *,
    excluded_untracked: frozenset[bytes] = frozenset(),
) -> dict[str, Any]:
    staged_paths = nul_paths(git(
        worktree, "diff-index", "--cached", "--name-only", "-z", "HEAD", "--", binary=True,
    ).stdout)
    unstaged_paths = nul_paths(git(
        worktree, "diff-files", "--name-only", "-z", "--", binary=True,
    ).stdout)
    untracked_paths = [
        path for path in nul_paths(git(
            worktree, "ls-files", "--others", "--exclude-standard", "-z", binary=True,
        ).stdout)
        if path not in excluded_untracked
    ]
    staged_diff = git(
        worktree, "diff-index", "--cached", "-p", "--binary", "--full-index",
        "--no-ext-diff", "--no-textconv", "HEAD", "--", binary=True,
    ).stdout
    unstaged_diff = git(
        worktree, "diff-files", "-p", "--binary", "--full-index",
        "--no-ext-diff", "--no-textconv", "--", binary=True,
    ).stdout
    frames = _untracked_frames(worktree.resolve(), untracked_paths)
    paths = {
        "staged": [os.fsdecode(path) for path in staged_paths],
        "unstaged": [os.fsdecode(path) for path in unstaged_paths],
        "untracked": [os.fsdecode(path) for path in untracked_paths],
    }
    return {
        "status": {name: len(values) for name, values in paths.items()},
        "paths": paths,
        "fingerprints": {
            "staged_diff_sha256": hashlib.sha256(staged_diff).hexdigest(),
            "unstaged_diff_sha256": hashlib.sha256(unstaged_diff).hexdigest(),
            "untracked_paths_sha256": digest_frames(untracked_paths),
            "untracked_content_sha256": digest_frames(frames),
        },
    }


def _untracked_frames(root: Path, paths: list[bytes]) -> list[bytes]:
    frames: list[bytes] = []
    for relative in paths:
        path = Path(os.fsdecode(os.path.join(os.fsencode(root), relative)))
        try:
            observed = os.lstat(path)
            kind, content = _untracked_content(path, observed)
        except ObservationError:
            raise
        except OSError as error:
            raise ObservationError("untracked_snapshot_failed") from error
        frames.extend((relative, kind, content))
    return frames


def _final_content_hash(path: Path) -> str:
    try:
        observed = os.lstat(path)
    except FileNotFoundError:
        return hashlib.sha256(b"deleted\0").hexdigest()
    except OSError as error:
        raise ObservationError("worktree_content_snapshot_failed") from error
    if stat.S_ISLNK(observed.st_mode):
        content = os.fsencode(os.readlink(path))
        kind = b"symlink"
    elif stat.S_ISREG(observed.st_mode):
        maximum = env_limit(
            "WORKSPACE_CONVERGENCE_MAX_UNTRACKED_BYTES",
            DEFAULT_MAX_UNTRACKED_BYTES,
            "invalid_untracked_limit",
        )
        content = safe_read(path, maximum, "worktree_content_too_large").content
        kind = b"executable" if observed.st_mode & 0o111 else b"regular"
    else:
        raise ObservationError("worktree_special_file")
    digest = hashlib.sha256()
    digest.update(kind)
    digest.update(b"\0")
    digest.update(content)
    return digest.hexdigest()


def _committed_paths(worktree: Path, baseline: str | None, head: str) -> list[str]:
    if baseline is None:
        return []
    completed = git(
        worktree,
        "diff",
        "--name-only",
        "-z",
        f"{baseline}..{head}",
        "--",
        binary=True,
        allow_failure=True,
    )
    if completed.returncode != 0:
        raise ObservationError("baseline_diff_failed")
    return [os.fsdecode(path) for path in nul_paths(completed.stdout)]


def _content_hashes(
    worktree: Path,
    current: dict[str, Any],
    committed_paths: list[str],
) -> dict[str, str]:
    paths = sorted({
        *committed_paths,
        *(path for category in ("staged", "unstaged", "untracked")
          for path in current["paths"][category]),
    })
    return {path: _final_content_hash(worktree / path) for path in paths}


def _changes(current: dict[str, Any], hashes: dict[str, str]) -> dict[str, Any]:
    keys = {
        "staged": "staged_diff_sha256",
        "unstaged": "unstaged_diff_sha256",
        "untracked": "untracked_content_sha256",
    }
    result: dict[str, Any] = {}
    for category, fingerprint in keys.items():
        paths = current["paths"][category]
        item: dict[str, Any] = {
            "paths": paths,
            "sha256": current["fingerprints"][fingerprint],
            "path_content_sha256": {path: hashes[path] for path in paths},
        }
        if category == "untracked":
            item["paths_sha256"] = current["fingerprints"]["untracked_paths_sha256"]
        result[category] = item
    return result


def observe_worktree(
    worktree: Path,
    *,
    baseline: str | None = None,
    exclude_marker: bool = True,
) -> dict[str, Any]:
    worktree = worktree.resolve()
    excluded = frozenset({os.fsencode(WORK_ITEM_MARKER)}) if exclude_marker else frozenset()
    before = _observe_once(worktree, baseline, excluded)
    after = _observe_once(worktree, baseline, excluded)
    reasons: list[str] = []
    if before["operation"] or after["operation"]:
        reasons.append("git_operation_in_progress")
    for field, reason in (("head", "identity_changed_during_scan"),
                          ("branch", "identity_changed_during_scan"),
                          ("snapshot", "source_changed_during_scan"),
                          ("content_hashes", "source_changed_during_scan")):
        if before[field] != after[field]:
            reasons.append(reason)
    result = {key: value for key, value in before.items() if key != "operation"}
    result["reasons"] = sorted(set(reasons))
    result["signature"] = digest_frames([repr({
        key: result[key] for key in ("path", "head", "branch", "snapshot", "content_hashes")
    }).encode()])
    return result


def _observe_once(
    worktree: Path,
    baseline: str | None,
    excluded: frozenset[bytes],
) -> dict[str, Any]:
    head = git(worktree, "rev-parse", "HEAD").stdout.strip()
    current_branch = branch(worktree)
    operation = has_git_operation(worktree)
    current = snapshot(worktree, excluded_untracked=excluded)
    committed = _committed_paths(worktree, baseline, head)
    hashes = _content_hashes(worktree, current, committed)
    return {
        "path": str(worktree),
        "branch": current_branch,
        "head": head,
        "operation": operation,
        "snapshot": current,
        "content_hashes": hashes,
        "committed_paths": committed,
        "changes": _changes(current, hashes),
    }
