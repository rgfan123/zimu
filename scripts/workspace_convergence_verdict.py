"""Pre-work duplicate-development verdict orchestration."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from workspace_convergence_candidate import candidate_signature, evaluate_candidate
from workspace_convergence_common import ObservationError, RecordError, git
from workspace_convergence_coordination import (
    coordination_records,
    valid_intent,
    valid_work_item,
)
from workspace_convergence_snapshot import observe_worktree, worktree_registry


def _empty_changes() -> dict[str, Any]:
    return {
        category: {"paths": [], "sha256": None, "path_content_sha256": {}}
        for category in ("staged", "unstaged", "untracked")
    }


def _remote_status(target: Path, head: str, branch: str | None) -> dict[str, Any]:
    base = {
        "status": "NO_TRACKING",
        "upstream": None,
        "local_ref_head": None,
        "local_tracking_relation": "NONE",
        "evidence": "NO_LOCAL_TRACKING_REF",
    }
    if branch is None:
        return base
    upstream = git(
        target,
        "rev-parse",
        "--abbrev-ref",
        "--symbolic-full-name",
        "@{upstream}",
        allow_failure=True,
    )
    if upstream.returncode != 0 or not upstream.stdout.strip():
        return base
    upstream_name = upstream.stdout.strip()
    result = git(target, "rev-parse", "--verify", f"{upstream_name}^{{commit}}", allow_failure=True)
    if result.returncode != 0:
        return {
            **base,
            "upstream": upstream_name,
            "local_tracking_relation": "UNKNOWN",
            "evidence": "TRACKING_REF_UNRESOLVED",
        }
    upstream_head = result.stdout.strip()
    relation_result = git(
        target,
        "rev-list",
        "--left-right",
        "--count",
        f"{upstream_name}...HEAD",
        allow_failure=True,
    )
    relation = "UNKNOWN"
    if relation_result.returncode == 0:
        behind, ahead = (int(value) for value in relation_result.stdout.split())
        relation = (
            "EQUAL" if behind == 0 and ahead == 0
            else "AHEAD" if behind == 0
            else "BEHIND" if ahead == 0
            else "DIVERGED"
        )
    equal = relation == "EQUAL"
    return {
        "status": "REMOTE_UNVERIFIED" if equal else "LOCAL_REF_ONLY",
        "upstream": upstream_name,
        "local_ref_head": upstream_head,
        "local_tracking_relation": relation,
        "evidence": "LOCAL_REMOTE_TRACKING_REF_ONLY",
    }


def _report(target: Path, baseline: str, work_item: str, intent: str, candidate: str | None) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "mode": "pre-work",
        "verdict": "PROTECTED_UNKNOWN",
        "target": {
            "path": str(target.resolve()),
            "head": None,
            "branch": None,
            "baseline": baseline,
        },
        "request": {"work_item": work_item, "intent": intent, "candidate": candidate},
        "matches": [],
        "changes": _empty_changes(),
        "remote": _remote_status_default(),
        "candidate": None,
        "reasons": [],
    }


def _remote_status_default() -> dict[str, Any]:
    return {
        "status": "NO_TRACKING",
        "upstream": None,
        "local_ref_head": None,
        "local_tracking_relation": "NONE",
        "evidence": "NO_LOCAL_TRACKING_REF",
    }


def _validate_target(target: Path, baseline: str, report: dict[str, Any]) -> tuple[Path, str] | None:
    if not target.is_dir():
        report.update({"verdict": "INVALID_INPUT", "reasons": ["target_not_directory"]})
        return None
    root_result = git(target, "rev-parse", "--show-toplevel", allow_failure=True)
    if root_result.returncode != 0:
        report.update({"verdict": "INVALID_INPUT", "reasons": ["target_not_git_worktree"]})
        return None
    root = Path(root_result.stdout.strip()).resolve()
    if target.resolve() != root:
        report.update({"verdict": "INVALID_INPUT", "reasons": ["target_not_worktree_root"]})
        return None
    resolved = git(
        root, "rev-parse", "--verify", "--end-of-options", f"{baseline}^{{commit}}",
        allow_failure=True,
    )
    if resolved.returncode != 0:
        report.update({"verdict": "INVALID_INPUT", "reasons": ["baseline_not_commit"]})
        return None
    return root, resolved.stdout.strip()


def _initial_protections(
    target: Path,
    baseline: str,
    observation: dict[str, Any],
) -> list[str]:
    reasons = [
        "target_git_operation_in_progress" if reason == "git_operation_in_progress" else reason
        for reason in observation["reasons"]
    ]
    if observation["branch"] is None:
        reasons.append("target_detached")
    if not (target / "backend").is_dir() or not (target / "frontend").is_dir():
        reasons.append("target_layout_not_development")
    if git(target, "merge-base", "--is-ancestor", baseline, observation["head"],
           allow_failure=True).returncode != 0:
        reasons.append("baseline_not_ancestor")
    return reasons


def _matching_records(
    records: list[dict[str, Any]],
    work_item: str,
    intent: str,
) -> list[dict[str, Any]]:
    return [record for record in records if record["work_item"] == work_item or record["intent"] == intent]


def _target_claim_protections(
    records: list[dict[str, Any]],
    target_path: str,
    work_item: str,
    intent: str,
    observation: dict[str, Any],
) -> list[str]:
    claims = [record for record in records if record["path"] == target_path]
    exact = [record for record in claims if record["work_item"] == work_item and record["intent"] == intent]
    reasons: list[str] = []
    if any(record not in exact for record in claims):
        reasons.append("target_claimed_by_other_work")
    dirty = any(observation["changes"][name]["paths"] for name in ("staged", "unstaged", "untracked"))
    if dirty and not exact:
        reasons.append("target_has_unclaimed_wip")
    return reasons


def _observe_matches(
    records: list[dict[str, Any]],
    registered: dict[str, dict[str, str | None]],
    target: Path,
    target_observation: dict[str, Any],
    baseline: str,
    work_item: str,
    intent: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[str]]:
    reports: list[dict[str, Any]] = []
    observations: list[dict[str, Any]] = []
    protections: list[str] = []
    identities: dict[str, tuple[str, str]] = {}
    for record in records:
        report, observation = _observe_match(
            record, registered, target, target_observation, baseline, work_item, intent,
        )
        reports.append(report)
        if observation is not None:
            observations.append(observation)
        protections.extend(f"match:{reason}" for reason in report["reasons"])
        identity = record["work_item"], record["intent"]
        if record["path"] in identities and identities[record["path"]] != identity:
            protections.append("conflicting_coordination_evidence")
        identities[record["path"]] = identity
    return reports, observations, protections


def _observe_match(
    record: dict[str, Any],
    registered: dict[str, dict[str, str | None]],
    target: Path,
    target_observation: dict[str, Any],
    baseline: str,
    work_item: str,
    intent: str,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    report: dict[str, Any] = {
        "path": record["path"], "branch": None, "head": None,
        "work_item": record["work_item"], "intent": record["intent"],
        "matching_on": [key for key, value in (("work_item", work_item), ("intent", intent))
                        if record[key] == value],
        "evidence": record["evidence"], "changes": None, "reasons": [],
    }
    if record["path"] not in registered:
        report["reasons"].append("worktree_missing_or_unregistered")
        return report, None
    observation = target_observation if record["path"] == str(target) else observe_worktree(
        Path(record["path"]), baseline=baseline,
    )
    report.update({
        "branch": observation["branch"], "head": observation["head"],
        "changes": observation["changes"], "reasons": list(observation["reasons"]),
    })
    if observation["branch"] is None:
        report["reasons"].append("matching_worktree_detached")
    if git(target, "merge-base", "--is-ancestor", baseline, observation["head"],
           allow_failure=True).returncode != 0:
        report["reasons"].append("matching_baseline_not_ancestor")
    if record["branch"] is not None and record["branch"] != observation["branch"]:
        report["reasons"].append("registry_branch_changed")
    if record["head"] is not None and record["head"] != observation["head"]:
        report["reasons"].append("registry_head_changed")
    report["reasons"] = sorted(set(report["reasons"]))
    return report, observation


def _collision_reasons(observations: list[dict[str, Any]]) -> list[str]:
    reasons: set[str] = set()
    unique = {observation["path"]: observation for observation in observations}
    values = list(unique.values())
    for index, left in enumerate(values):
        for right in values[index + 1:]:
            overlap = set(left["content_hashes"]) & set(right["content_hashes"])
            reasons.update(
                f"path_content_collision:{path}"
                for path in overlap
                if left["content_hashes"][path] != right["content_hashes"][path]
            )
    return sorted(reasons)


def _global_freeze_reasons(
    target: Path,
    target_before: dict[str, Any],
    worktrees_before: dict[str, dict[str, str | None]],
    coordination_before: str,
    registry: Path | None,
    matches: list[dict[str, Any]],
    candidate_freeze: dict[str, str] | None,
) -> list[str]:
    reasons: list[str] = []
    worktrees_after = worktree_registry(target)
    if worktrees_before != worktrees_after:
        reasons.append("worktree_registry_changed_during_scan")
    try:
        _, coordination_after = coordination_records(worktrees_after, registry)
    except (RecordError, ObservationError):
        reasons.append("coordination_record_changed_during_scan")
    else:
        if coordination_before != coordination_after:
            reasons.append("coordination_record_changed_during_scan")
    target_after = observe_worktree(target, baseline=target_before.get("baseline"))
    if target_before["signature"] != target_after["signature"] or target_after["reasons"]:
        reasons.append("target_changed_during_scan")
    for observation in {item["path"]: item for item in matches}.values():
        if observation["path"] == str(target):
            continue
        final = observe_worktree(Path(observation["path"]), baseline=target_before.get("baseline"))
        if observation["signature"] != final["signature"] or final["reasons"]:
            reasons.append(f"match_changed_during_scan:{observation['path']}")
    if candidate_freeze is not None:
        try:
            final_signature = candidate_signature(target, candidate_freeze)
        except (RecordError, ObservationError):
            reasons.append("candidate_changed_during_scan")
        else:
            if final_signature != candidate_freeze["signature"]:
                reasons.append("candidate_changed_during_scan")
    return sorted(set(reasons))


def pre_work(
    *,
    target: Path,
    baseline: str,
    work_item: str,
    intent: str,
    candidate: str | None,
    registry: Path | None,
) -> tuple[int, dict[str, Any]]:
    report = _report(target, baseline, work_item, intent, candidate)
    if not valid_work_item(work_item):
        report.update({"verdict": "INVALID_INPUT", "reasons": ["work_item_invalid"]})
        return 2, report
    if not valid_intent(intent):
        report.update({"verdict": "INVALID_INPUT", "reasons": ["intent_not_normalized"]})
        return 2, report
    try:
        validated = _validate_target(target, baseline, report)
        if validated is None:
            return 2, report
        root, baseline_commit = validated
        return _scan(root, baseline_commit, work_item, intent, candidate, registry, report)
    except RecordError as error:
        report.update({"verdict": error.verdict, "reasons": [error.reason]})
        return 2, report
    except ObservationError as error:
        report.update({"verdict": "PROTECTED_UNKNOWN", "reasons": [error.reason]})
        return 1, report


def _scan(
    target: Path,
    baseline: str,
    work_item: str,
    intent: str,
    candidate: str | None,
    registry: Path | None,
    report: dict[str, Any],
) -> tuple[int, dict[str, Any]]:
    observation = observe_worktree(target, baseline=baseline)
    observation["baseline"] = baseline
    report["target"].update({
        "path": str(target), "head": observation["head"],
        "branch": observation["branch"], "baseline": baseline,
    })
    report["changes"] = observation["changes"]
    report["remote"] = _remote_status(target, observation["head"], observation["branch"])
    protections = _initial_protections(target, baseline, observation)
    worktrees = worktree_registry(target)
    records, coordination_signature = coordination_records(worktrees, registry)
    matches = _matching_records(records, work_item, intent)
    protections.extend(_target_claim_protections(
        records, str(target), work_item, intent, observation,
    ))
    match_reports, match_observations, match_protections = _observe_matches(
        matches, worktrees, target, observation, baseline, work_item, intent,
    )
    report["matches"] = match_reports
    protections.extend(match_protections)
    candidate_result = evaluate_candidate(target, candidate) if candidate is not None else None
    if candidate_result is not None:
        report["candidate"] = candidate_result["detail"]
    protections.extend(_global_freeze_reasons(
        target, observation, worktrees, coordination_signature, registry,
        match_observations, candidate_result["freeze"] if candidate_result else None,
    ))
    if protections:
        report.update({"verdict": "PROTECTED_UNKNOWN", "reasons": sorted(set(protections))})
        return 1, report
    collisions = _collision_reasons([observation, *match_observations])
    if collisions:
        report.update({"verdict": "COLLISION", "reasons": collisions})
        return 1, report
    if candidate_result is not None and candidate_result["integrated"]:
        report.update({"verdict": "ALREADY_INTEGRATED", "reasons": candidate_result["reasons"]})
        return 1, report
    if match_reports:
        report.update({"verdict": "RESUME_EXISTING", "reasons": ["explicit_existing_work"]})
        return 1, report
    reasons = candidate_result["reasons"] if candidate_result else ["no_explicit_existing_work"]
    report.update({"verdict": "START_ALLOWED", "reasons": reasons})
    return 0, report
