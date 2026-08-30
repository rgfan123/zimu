"""Ledger verification with global before/after freezing."""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any

from workspace_convergence_common import (
    DEFAULT_MAX_LEDGER_BYTES,
    ObservationError,
    env_limit,
    git,
    safe_read,
)
from workspace_convergence_snapshot import (
    has_git_operation,
    observe_worktree,
    worktree_registry,
)


def _read_ledger(path: Path) -> tuple[dict[str, Any], str]:
    maximum = env_limit(
        "WORKSPACE_CONVERGENCE_MAX_LEDGER_BYTES",
        DEFAULT_MAX_LEDGER_BYTES,
        "invalid_ledger_limit",
    )
    try:
        result = safe_read(path, maximum, "ledger_too_large")
        document = json.loads(result.content)
    except (ObservationError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ObservationError("invalid_ledger_file") from error
    if not isinstance(document, dict):
        raise ObservationError("invalid_ledger_file")
    return document, result.signature


def _source_record_valid(record: Any) -> bool:
    if not isinstance(record, dict):
        return False
    if not all(
        isinstance(record.get(key), str) and bool(record[key])
        for key in ("id", "branch", "expected_head", "disposition")
    ):
        return False
    if re.fullmatch(r"[0-9a-f]{40,64}", record["expected_head"]) is None:
        return False
    expected_path = record.get("expected_path")
    if expected_path is not None and (
        not isinstance(expected_path, str) or not Path(expected_path).is_absolute()
    ):
        return False
    snapshot = record.get("expected_snapshot")
    if not isinstance(snapshot, dict):
        return False
    status = snapshot.get("status")
    paths = snapshot.get("paths")
    fingerprints = snapshot.get("fingerprints")
    categories = ("staged", "unstaged", "untracked")
    if not isinstance(status, dict) or any(
        type(status.get(category)) is not int or status[category] < 0
        for category in categories
    ):
        return False
    if not isinstance(paths, dict) or any(
        not isinstance(paths.get(category), list)
        or any(not isinstance(path, str) for path in paths[category])
        for category in categories
    ):
        return False
    if any(status[category] != len(paths[category]) for category in categories):
        return False
    digest_keys = (
        "staged_diff_sha256",
        "unstaged_diff_sha256",
        "untracked_paths_sha256",
        "untracked_content_sha256",
    )
    return isinstance(fingerprints, dict) and all(
        isinstance(fingerprints.get(key), str)
        and re.fullmatch(r"[0-9a-f]{64}", fingerprints[key]) is not None
        for key in digest_keys
    )


def _deferred_record_valid(record: Any) -> bool:
    if not isinstance(record, dict) or any(
        not isinstance(record.get(key), str) or not record[key]
        for key in ("id", "branch")
    ):
        return False
    expected_path = record.get("expected_path")
    if expected_path is not None and (
        not isinstance(expected_path, str) or not Path(expected_path).is_absolute()
    ):
        return False
    observed_head = record.get("observed_head")
    if observed_head is not None and (
        not isinstance(observed_head, str)
        or re.fullmatch(r"[0-9a-f]{40,64}", observed_head) is None
    ):
        return False
    observed_status = record.get("observed_status")
    categories = ("staged", "unstaged", "untracked")
    if observed_status is not None and (
        not isinstance(observed_status, dict)
        or any(type(observed_status.get(category)) is not int or observed_status[category] < 0
               for category in categories)
    ):
        return False
    fingerprints = record.get("observed_fingerprints", record.get("fingerprints"))
    if fingerprints is None:
        return True
    digest_keys = (
        "staged_diff_sha256",
        "unstaged_diff_sha256",
        "untracked_paths_sha256",
        "untracked_content_sha256",
    )
    return isinstance(fingerprints, dict) and all(
        isinstance(fingerprints.get(key), str)
        and re.fullmatch(r"[0-9a-f]{64}", fingerprints[key]) is not None
        for key in digest_keys
    )


def _observe_source(
    record: dict[str, Any],
    worktree: Path,
    baseline: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    observation = observe_worktree(worktree, baseline=baseline, exclude_marker=False)
    reasons = list(observation["reasons"])
    expected_path = record.get("expected_path")
    if expected_path is not None and str(Path(expected_path).resolve()) != observation["path"]:
        reasons.append("worktree_path_changed")
    if observation["head"] != record["expected_head"]:
        reasons.append("head_changed")
    expected = record["expected_snapshot"]
    if observation["snapshot"]["status"] != expected.get("status"):
        reasons.append("status_changed")
    if (
        observation["snapshot"]["paths"] != expected.get("paths")
        or observation["snapshot"]["fingerprints"] != expected.get("fingerprints")
    ):
        reasons.append("fingerprint_changed")
    report = {
        "id": record["id"],
        "branch": observation["branch"],
        "path": observation["path"],
        "head": observation["head"],
        "disposition": record["disposition"],
        "status": observation["snapshot"]["status"],
        "paths": observation["snapshot"]["paths"],
        "fingerprints": observation["snapshot"]["fingerprints"],
        "reasons": sorted(set(reasons)),
    }
    return report, observation


def _missing_source(record: dict[str, Any], reason: str) -> dict[str, Any]:
    return {
        "id": record.get("id"),
        "branch": record.get("branch"),
        "head": None,
        "disposition": record.get("disposition"),
        "status": None,
        "reasons": [reason],
    }


def _find_source(
    record: dict[str, Any],
    registry: dict[str, dict[str, str | None]],
) -> tuple[Path | None, str | None]:
    expected_path = record.get("expected_path")
    if expected_path is not None:
        if not isinstance(expected_path, str) or not Path(expected_path).is_absolute():
            return None, "expected_path_invalid"
        lexical = os.path.abspath(expected_path)
        canonical = os.path.realpath(lexical)
        allowed = "/private" + lexical if lexical.startswith("/var/") else lexical
        path = canonical if canonical == allowed else lexical
        registered = registry.get(path)
        if registered is None:
            branch_matches = [
                Path(candidate)
                for candidate, value in registry.items()
                if value["branch"] == record.get("branch")
            ]
            if len(branch_matches) == 1:
                return branch_matches[0], "worktree_path_changed"
            return None, "worktree_missing"
        if registered["branch"] != record.get("branch"):
            return Path(path), "worktree_path_changed"
        return Path(path), None
    matches = [Path(path) for path, value in registry.items() if value["branch"] == record.get("branch")]
    return (matches[0], None) if len(matches) == 1 else (None, "worktree_missing")


def _deferred_report(
    record: dict[str, Any],
    registry: dict[str, dict[str, str | None]],
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    path, missing_reason = _find_source(record, registry)
    if path is None or missing_reason is not None:
        reasons = [missing_reason or "worktree_missing"]
        return {
            "id": record.get("id"),
            "branch": record.get("branch"),
            "path": str(path) if path else record.get("expected_path"),
            "current": False,
            "changed": True,
            "reasons": reasons,
        }, None
    observation = observe_worktree(path, exclude_marker=False)
    reasons = list(observation["reasons"])
    if record.get("observed_head") is not None and record["observed_head"] != observation["head"]:
        reasons.append("head_changed")
    status = observation["snapshot"]["status"]
    if record.get("observed_status") is not None and record["observed_status"] != status:
        reasons.append("status_changed")
    expected_fingerprints = record.get("observed_fingerprints", record.get("fingerprints"))
    fingerprints = observation["snapshot"]["fingerprints"]
    if expected_fingerprints is not None and expected_fingerprints != fingerprints:
        reasons.append("fingerprint_changed")
    reasons = sorted(set(reasons))
    return {
        "id": record.get("id"),
        "branch": record.get("branch"),
        "path": observation["path"],
        "head": observation["head"],
        "status": status,
        "fingerprints": fingerprints,
        "current": not reasons,
        "changed": bool(reasons),
        "reasons": reasons,
    }, observation


def _global_changes(
    target_before: dict[str, Any],
    target: Path,
    registry_before: dict[str, dict[str, str | None]],
    source_observations: list[dict[str, Any]],
    baseline: str,
) -> list[str]:
    reasons: list[str] = []
    target_after = observe_worktree(target, exclude_marker=False)
    if target_before["signature"] != target_after["signature"] or target_after["reasons"]:
        reasons.append("target_changed_during_scan")
    if registry_before != worktree_registry(target):
        reasons.append("worktree_registry_changed_during_scan")
    for observation in source_observations:
        current = observe_worktree(
            Path(observation["path"]), baseline=baseline, exclude_marker=False,
        )
        if observation["signature"] != current["signature"] or current["reasons"]:
            reasons.append(f"source_changed_during_global_scan:{observation['path']}")
    return sorted(set(reasons))


def verify(ledger_path: Path, target: Path) -> tuple[int, dict[str, Any]]:
    try:
        ledger, ledger_signature = _read_ledger(ledger_path)
    except ObservationError as error:
        return 2, {"verdict": "INVALID_LEDGER", "reason": error.reason}
    sources = ledger.get("sources")
    deferred = ledger.get("deferred_sources", [])
    if ledger.get("schema_version") != 2 or not isinstance(sources, list):
        return 2, {"verdict": "INVALID_LEDGER", "reason": "unsupported_schema"}
    if any(not _source_record_valid(record) for record in sources):
        return 2, {"verdict": "INVALID_LEDGER", "reason": "invalid_source_record"}
    if not isinstance(deferred, list) or any(not _deferred_record_valid(record) for record in deferred):
        return 2, {"verdict": "INVALID_LEDGER", "reason": "invalid_deferred_source_record"}
    baseline = ledger.get("baseline_commit")
    report: dict[str, Any] = {
        "verdict": "READY",
        "target": str(target.resolve()),
        "baseline_commit": baseline,
        "sources": [],
        "deferred_sources": [],
    }
    if not isinstance(baseline, str) or not baseline:
        return 2, {**report, "verdict": "INVALID_LEDGER", "reason": "missing_baseline_commit"}
    return _verify_observations(ledger_path, ledger_signature, ledger, target, baseline, report)


def _verify_observations(
    ledger_path: Path,
    ledger_signature: str,
    ledger: dict[str, Any],
    target: Path,
    baseline: str,
    report: dict[str, Any],
) -> tuple[int, dict[str, Any]]:
    target = target.resolve()
    try:
        if not (target / "backend").is_dir() or not (target / "frontend").is_dir():
            report["verdict"] = "WRONG_BASELINE"
            return 1, report
        if has_git_operation(target):
            report.update({"verdict": "PROTECTED_UNKNOWN", "reason": "target_git_operation_in_progress"})
            return 1, report
        if git(target, "merge-base", "--is-ancestor", baseline, "HEAD", allow_failure=True).returncode:
            report["verdict"] = "WRONG_BASELINE"
            return 1, report
        target_before = observe_worktree(target, exclude_marker=False)
        registry = worktree_registry(target)
        protected, changed, observed = _required_sources(ledger["sources"], registry, baseline, report)
        deferred_observed = _deferred_sources(ledger.get("deferred_sources", []), registry, report)
        global_reasons = _global_changes(target_before, target, registry, observed, baseline)
        _, final_ledger_signature = _read_ledger(ledger_path)
        if ledger_signature != final_ledger_signature:
            global_reasons.append("ledger_changed_during_scan")
        _mark_deferred_scan_changes(report, deferred_observed)
    except ObservationError as error:
        report.update({"verdict": "PROTECTED_UNKNOWN", "reason": error.reason})
        return 1, report
    if global_reasons or protected:
        report["verdict"] = "PROTECTED_UNKNOWN"
        if global_reasons:
            report["reasons"] = sorted(set(global_reasons))
        return 1, report
    if changed:
        report["verdict"] = "SOURCE_CHANGED"
        return 1, report
    return 0, report


def _required_sources(
    records: list[Any],
    registry: dict[str, dict[str, str | None]],
    baseline: str,
    report: dict[str, Any],
) -> tuple[bool, bool, list[dict[str, Any]]]:
    protected = changed = False
    observations: list[dict[str, Any]] = []
    for record in records:
        if not _source_record_valid(record):
            raise ObservationError("invalid_source_record")
        path, path_reason = _find_source(record, registry)
        if path is None or path_reason:
            report["sources"].append(_missing_source(record, path_reason or "worktree_missing"))
            protected = True
            continue
        source_report, observation = _observe_source(record, path, baseline)
        if path_reason:
            source_report["reasons"].append(path_reason)
        report["sources"].append(source_report)
        observations.append(observation)
        reasons = set(source_report["reasons"])
        if reasons - {"status_changed", "fingerprint_changed"}:
            protected = True
        elif reasons:
            changed = True
    return protected, changed, observations


def _deferred_sources(
    records: Any,
    registry: dict[str, dict[str, str | None]],
    report: dict[str, Any],
) -> list[dict[str, Any]]:
    if not isinstance(records, list):
        raise ObservationError("invalid_deferred_sources")
    observations: list[dict[str, Any]] = []
    for record in records:
        if not isinstance(record, dict) or not isinstance(record.get("id"), str):
            raise ObservationError("invalid_deferred_source_record")
        item, observation = _deferred_report(record, registry)
        report["deferred_sources"].append(item)
        if observation is not None:
            observations.append(observation)
    return observations


def _mark_deferred_scan_changes(
    report: dict[str, Any],
    observations: list[dict[str, Any]],
) -> None:
    by_path = {item.get("path"): item for item in report["deferred_sources"]}
    for observation in observations:
        current = observe_worktree(Path(observation["path"]), exclude_marker=False)
        if current["signature"] == observation["signature"] and not current["reasons"]:
            continue
        item = by_path[observation["path"]]
        item["current"] = False
        item["changed"] = True
        item["reasons"] = sorted(set(item["reasons"] + ["changed_during_scan"]))
