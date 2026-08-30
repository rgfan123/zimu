#!/usr/bin/env python3
"""Read-only verification for an isolated workspace convergence ledger."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


DEFAULT_TIMEOUT_SECONDS = 8.0
GIT_OPERATION_MARKERS = (
    "MERGE_HEAD",
    "CHERRY_PICK_HEAD",
    "REVERT_HEAD",
    "REBASE_HEAD",
    "rebase-merge",
    "rebase-apply",
    "sequencer",
)


class ObservationError(RuntimeError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _git(
    worktree: Path,
    *arguments: str,
    allow_failure: bool = False,
) -> subprocess.CompletedProcess[str]:
    timeout = float(os.environ.get("WORKSPACE_CONVERGENCE_GIT_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS))
    executable = os.environ.get("WORKSPACE_CONVERGENCE_GIT_BIN", "git")
    environment = os.environ.copy()
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    try:
        completed = subprocess.run(
            [
                executable,
                "-C",
                str(worktree),
                "-c",
                "core.fsmonitor=false",
                *arguments,
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout,
            env=environment,
        )
    except subprocess.TimeoutExpired as error:
        raise ObservationError("scan_timeout") from error
    if completed.returncode != 0 and not allow_failure:
        raise ObservationError("git_observation_failed")
    return completed


def _git_path(worktree: Path, name: str) -> Path:
    value = Path(_git(worktree, "rev-parse", "--git-path", name).stdout.strip())
    return value if value.is_absolute() else worktree / value


def _nul_count(output: str) -> int:
    return sum(1 for value in output.split("\0") if value)


def _branch(worktree: Path) -> str | None:
    completed = _git(worktree, "symbolic-ref", "--quiet", "--short", "HEAD", allow_failure=True)
    return completed.stdout.strip() if completed.returncode == 0 else None


def _has_git_operation(worktree: Path) -> bool:
    return any(_git_path(worktree, marker).exists() for marker in GIT_OPERATION_MARKERS)


def _worktrees(target: Path) -> dict[str, dict[str, str | None]]:
    records: dict[str, dict[str, str | None]] = {}
    current: dict[str, str | None] | None = None
    for line in _git(target, "worktree", "list", "--porcelain").stdout.splitlines():
        if line.startswith("worktree "):
            path = line.removeprefix("worktree ")
            current = {"path": path, "head": None, "branch": None}
            records[path] = current
        elif current is not None and line.startswith("HEAD "):
            current["head"] = line.removeprefix("HEAD ")
        elif current is not None and line.startswith("branch refs/heads/"):
            current["branch"] = line.removeprefix("branch refs/heads/")
    return records


def _observe_source(record: dict[str, Any], worktree: Path) -> dict[str, Any]:
    reasons: list[str] = []
    before_head = _git(worktree, "rev-parse", "HEAD").stdout.strip()
    before_branch = _branch(worktree)
    operation = _has_git_operation(worktree)
    status = {
        "staged": _nul_count(_git(worktree, "diff", "--cached", "--name-only", "-z", "--").stdout),
        "unstaged": _nul_count(_git(worktree, "diff", "--name-only", "-z", "--").stdout),
        "untracked": _nul_count(
            _git(worktree, "ls-files", "--others", "--exclude-standard", "-z").stdout
        ),
    }
    after_head = _git(worktree, "rev-parse", "HEAD").stdout.strip()
    after_branch = _branch(worktree)
    if operation:
        reasons.append("git_operation_in_progress")
    if before_head != after_head or before_branch != after_branch:
        reasons.append("identity_changed_during_scan")
    if before_head != record["expected_head"]:
        reasons.append("head_changed")
    if before_branch != record["branch"]:
        reasons.append("branch_changed")
    expected_status = record["expected_status"]
    if status != expected_status:
        reasons.append("status_changed")
    return {
        "id": record["id"],
        "branch": before_branch,
        "head": before_head,
        "disposition": record["disposition"],
        "status": status,
        "reasons": reasons,
    }


def verify(ledger_path: Path, target: Path) -> tuple[int, dict[str, Any]]:
    try:
        ledger = json.loads(ledger_path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        return 2, {"verdict": "INVALID_LEDGER", "reason": type(error).__name__}
    if ledger.get("schema_version") != 1 or not isinstance(ledger.get("sources"), list):
        return 2, {"verdict": "INVALID_LEDGER", "reason": "unsupported_schema"}

    target = target.resolve()
    report: dict[str, Any] = {
        "verdict": "READY",
        "target": str(target),
        "baseline_commit": ledger.get("baseline_commit"),
        "sources": [],
    }
    if not (target / "backend").is_dir() or not (target / "frontend").is_dir():
        report["verdict"] = "WRONG_BASELINE"
        return 1, report

    baseline = ledger.get("baseline_commit")
    if not isinstance(baseline, str) or not baseline:
        report["verdict"] = "INVALID_LEDGER"
        report["reason"] = "missing_baseline_commit"
        return 2, report
    try:
        if _has_git_operation(target):
            report["verdict"] = "PROTECTED_UNKNOWN"
            report["reason"] = "target_git_operation_in_progress"
            return 1, report
        ancestor = _git(target, "merge-base", "--is-ancestor", baseline, "HEAD", allow_failure=True)
        if ancestor.returncode != 0:
            report["verdict"] = "WRONG_BASELINE"
            return 1, report
        worktrees = _worktrees(target)
    except ObservationError as error:
        report["verdict"] = "PROTECTED_UNKNOWN"
        report["reason"] = error.reason
        return 1, report

    by_branch = {
        value["branch"]: Path(path)
        for path, value in worktrees.items()
        if value["branch"] is not None
    }
    protected = False
    changed = False
    for source in ledger["sources"]:
        if not isinstance(source, dict) or not all(
            key in source
            for key in ("id", "branch", "expected_head", "expected_status", "disposition")
        ):
            report["verdict"] = "INVALID_LEDGER"
            report["reason"] = "invalid_source_record"
            return 2, report
        worktree = by_branch.get(source["branch"])
        if worktree is None:
            protected = True
            report["sources"].append(
                {
                    "id": source["id"],
                    "branch": source["branch"],
                    "head": None,
                    "disposition": source["disposition"],
                    "status": None,
                    "reasons": ["worktree_missing"],
                }
            )
            continue
        try:
            observation = _observe_source(source, worktree)
        except ObservationError as error:
            protected = True
            observation = {
                "id": source["id"],
                "branch": source["branch"],
                "head": None,
                "disposition": source["disposition"],
                "status": None,
                "reasons": [error.reason],
            }
        report["sources"].append(observation)
        if any(
            reason in observation["reasons"]
            for reason in (
                "git_operation_in_progress",
                "identity_changed_during_scan",
                "head_changed",
                "branch_changed",
                "scan_timeout",
                "git_observation_failed",
            )
        ):
            protected = True
        elif "status_changed" in observation["reasons"]:
            changed = True

    if protected:
        report["verdict"] = "PROTECTED_UNKNOWN"
        return 1, report
    if changed:
        report["verdict"] = "SOURCE_CHANGED"
        return 1, report
    return 0, report


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--json", action="store_true")
    options = parser.parse_args(arguments)
    code, report = verify(options.ledger, options.target)
    if options.json:
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    else:
        print(f"{report['verdict']}: {len(report.get('sources', []))} source(s)")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
