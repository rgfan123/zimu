#!/usr/bin/env python3
"""Read-only convergence ledger and duplicate-development gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from workspace_convergence_common import ObservationError
from workspace_convergence_ledger import verify
from workspace_convergence_snapshot import snapshot
from workspace_convergence_verdict import pre_work


def _emit(report: dict, *, json_output: bool, plain: str) -> None:
    if json_output:
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    else:
        print(plain)


def _pre_work(options: argparse.Namespace) -> int:
    invalid = options.ledger is not None or options.snapshot_worktree is not None
    missing = [
        name for name, value in (
            ("target", options.target),
            ("baseline", options.baseline),
            ("work_item", options.work_item),
            ("intent", options.intent),
        ) if value is None
    ]
    if invalid or missing:
        reason = "pre_work_arguments_invalid" if invalid else "missing:" + ",".join(missing)
        report = {"verdict": "INVALID_INPUT", "reasons": [reason]}
        _emit(report, json_output=options.json, plain=f"INVALID_INPUT: {reason}")
        return 2
    code, report = pre_work(
        target=options.target,
        baseline=options.baseline,
        work_item=options.work_item,
        intent=options.intent,
        candidate=options.candidate,
        registry=options.registry,
    )
    _emit(
        report,
        json_output=options.json,
        plain=f"{report['verdict']}: {', '.join(report.get('reasons', []))}",
    )
    return code


def _snapshot(options: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    conflicts = (
        options.ledger, options.target, options.baseline, options.work_item,
        options.intent, options.candidate, options.registry,
    )
    if any(value is not None for value in conflicts):
        parser.error("--snapshot-worktree cannot be combined with other modes")
    try:
        report = {
            "worktree": str(options.snapshot_worktree.resolve()),
            "snapshot": snapshot(options.snapshot_worktree),
        }
    except ObservationError as error:
        report = {"verdict": "PROTECTED_UNKNOWN", "reason": error.reason}
        _emit(report, json_output=options.json, plain=f"PROTECTED_UNKNOWN: {error.reason}")
        return 1
    _emit(report, json_output=options.json, plain=f"SNAPSHOT: {report['worktree']}")
    return 0


def _verify(options: argparse.Namespace, parser: argparse.ArgumentParser) -> int:
    pre_work_values = (
        options.baseline, options.work_item, options.intent, options.candidate, options.registry,
    )
    if any(value is not None for value in pre_work_values):
        parser.error("pre-work arguments require --pre-work")
    if options.ledger is None or options.target is None:
        parser.error("--ledger and --target are required")
    code, report = verify(options.ledger, options.target)
    _emit(
        report,
        json_output=options.json,
        plain=f"{report['verdict']}: {len(report.get('sources', []))} source(s)",
    )
    return code


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pre-work", action="store_true")
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--target", type=Path)
    parser.add_argument("--snapshot-worktree", type=Path)
    parser.add_argument("--baseline")
    parser.add_argument("--work-item")
    parser.add_argument("--intent")
    parser.add_argument("--candidate")
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--json", action="store_true")
    options = parser.parse_args(arguments)
    if options.pre_work:
        return _pre_work(options)
    if options.snapshot_worktree is not None:
        return _snapshot(options, parser)
    return _verify(options, parser)


if __name__ == "__main__":
    raise SystemExit(main())
