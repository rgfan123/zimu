"""Explicit registry and work-item marker handling."""

from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any

from workspace_convergence_common import (
    DEFAULT_MAX_COORDINATION_BYTES,
    ObservationError,
    RecordError,
    digest_frames,
    env_limit,
    safe_read,
)
from workspace_convergence_snapshot import WORK_ITEM_MARKER


WORK_ITEM_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}\Z")
INTENT_PATTERN = re.compile(r"[a-z0-9][a-z0-9._:/-]{0,159}\Z")


def valid_work_item(value: Any) -> bool:
    return isinstance(value, str) and WORK_ITEM_PATTERN.fullmatch(value) is not None


def valid_intent(value: Any) -> bool:
    return isinstance(value, str) and INTENT_PATTERN.fullmatch(value) is not None


def _read_json(path: Path, verdict: str) -> tuple[Any, str, str]:
    maximum = env_limit(
        "WORKSPACE_CONVERGENCE_MAX_COORDINATION_BYTES",
        DEFAULT_MAX_COORDINATION_BYTES,
        "invalid_coordination_limit",
    )
    try:
        observed = safe_read(path, maximum, "coordination_record_too_large")
        document = json.loads(observed.content)
    except ObservationError as error:
        reason = {
            "safe_file_changed_during_read": "record_changed_during_scan",
            "coordination_record_too_large": "record_not_regular_or_too_large",
        }.get(error.reason, "record_not_regular_or_too_large")
        raise RecordError(verdict, reason) from error
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RecordError(verdict, "record_invalid_json") from error
    return document, observed.signature, observed.path


def _record(
    raw: Any,
    *,
    path: Path | None,
    verdict: str,
    evidence: str,
) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise RecordError(verdict, "record_not_object")
    work_item, intent = raw.get("work_item"), raw.get("intent")
    if not valid_work_item(work_item) or not valid_intent(intent):
        raise RecordError(verdict, "record_identity_invalid")
    resolved_path = _record_path(raw.get("path"), path, verdict)
    branch = raw.get("branch")
    if branch is not None and (not isinstance(branch, str) or not branch):
        raise RecordError(verdict, "record_branch_invalid")
    head = raw.get("head")
    if head is not None and (
        not isinstance(head, str) or re.fullmatch(r"[0-9a-f]{40,64}", head) is None
    ):
        raise RecordError(verdict, "record_head_invalid")
    return {
        "path": str(resolved_path),
        "work_item": work_item,
        "intent": intent,
        "branch": branch,
        "head": head,
        "evidence": [evidence],
    }


def _record_path(raw_path: Any, path: Path | None, verdict: str) -> Path:
    if path is None:
        if not isinstance(raw_path, str) or not raw_path or not Path(raw_path).is_absolute():
            raise RecordError(verdict, "record_path_invalid")
        lexical = os.path.abspath(raw_path)
        canonical = os.path.realpath(lexical)
        allowed_lexical = "/private" + lexical if lexical.startswith("/var/") else lexical
        if canonical != allowed_lexical:
            raise RecordError(verdict, "record_path_symlink")
        return Path(canonical)
    resolved = path.resolve()
    if raw_path is not None and (
        not isinstance(raw_path, str) or Path(raw_path).resolve() != resolved
    ):
        raise RecordError(verdict, "marker_path_mismatch")
    return resolved


def coordination_records(
    worktrees: dict[str, dict[str, str | None]],
    registry_path: Path | None,
) -> tuple[list[dict[str, Any]], str]:
    records: list[dict[str, Any]] = []
    evidence: list[bytes] = []
    if registry_path is not None:
        document, signature, canonical = _read_json(registry_path, "INVALID_REGISTRY")
        if not _valid_registry(document):
            raise RecordError("INVALID_REGISTRY", "unsupported_registry_schema")
        evidence.append(f"registry:{canonical}:{signature}".encode())
        records.extend(
            _record(raw, path=None, verdict="INVALID_REGISTRY", evidence="registry")
            for raw in document["workspaces"]
        )
    for path in sorted(worktrees):
        marker = Path(path) / WORK_ITEM_MARKER
        try:
            os.lstat(marker)
        except FileNotFoundError:
            continue
        except OSError as error:
            raise ObservationError("coordination_marker_scan_failed") from error
        document, signature, canonical = _read_json(marker, "INVALID_MARKER")
        if not isinstance(document, dict) or document.get("schema_version") != 1:
            raise RecordError("INVALID_MARKER", "unsupported_marker_schema")
        evidence.append(f"marker:{canonical}:{signature}".encode())
        records.append(_record(
            document,
            path=marker.parent,
            verdict="INVALID_MARKER",
            evidence="marker",
        ))
    coalesced = _coalesce(records)
    serialized = json.dumps(coalesced, ensure_ascii=False, sort_keys=True).encode()
    return coalesced, digest_frames(sorted(evidence) + [serialized])


def _valid_registry(document: Any) -> bool:
    return (
        isinstance(document, dict)
        and document.get("schema_version") == 1
        and isinstance(document.get("workspaces"), list)
    )


def _coalesce(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: dict[tuple[str, str, str], dict[str, Any]] = {}
    for record in records:
        key = record["path"], record["work_item"], record["intent"]
        current = merged.get(key)
        if current is None:
            merged[key] = record
            continue
        _merge_claims(current, record)
    return sorted(merged.values(), key=lambda item: (
        item["path"], item["work_item"], item["intent"],
    ))


def _merge_claims(current: dict[str, Any], record: dict[str, Any]) -> None:
    for claim in ("branch", "head"):
        values = {value for value in (current[claim], record[claim]) if value is not None}
        if len(values) > 1:
            raise RecordError("INVALID_REGISTRY", "conflicting_coordination_claims")
        if current[claim] is None:
            current[claim] = record[claim]
    current["evidence"] = sorted(set(current["evidence"] + record["evidence"]))
