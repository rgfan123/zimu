import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-baseline.sh")
PYTHON_SCRIPT = Path(__file__).with_name("workspace_convergence.py")


def git(path: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(path), *arguments],
        text=True,
        capture_output=True,
        check=check,
    )


def git_path(path: Path, name: str) -> Path:
    value = Path(git(path, "rev-parse", "--git-path", name).stdout.strip())
    return value if value.is_absolute() else path / value


def source_snapshot(path: Path) -> dict[str, object]:
    completed = subprocess.run(
        [
            sys.executable,
            str(PYTHON_SCRIPT),
            "--snapshot-worktree",
            str(path),
            "--json",
        ],
        text=True,
        capture_output=True,
        check=False,
        env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
    )
    if completed.returncode != 0:
        raise AssertionError(completed.stderr or completed.stdout)
    return json.loads(completed.stdout)["snapshot"]


class WorkspaceConvergenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repo"
        self.source = self.root / "source"
        self.repository.mkdir()
        git(self.repository, "init", "-b", "master")
        git(self.repository, "config", "user.name", "Workspace Test")
        git(self.repository, "config", "user.email", "workspace@test.invalid")
        (self.repository / "backend").mkdir()
        (self.repository / "frontend").mkdir()
        (self.repository / "backend" / "base.txt").write_text("backend\n")
        (self.repository / "frontend" / "base.txt").write_text("frontend\n")
        git(self.repository, "add", "backend", "frontend")
        git(self.repository, "commit", "-m", "base")
        self.baseline = git(self.repository, "rev-parse", "HEAD").stdout.strip()
        git(self.repository, "worktree", "add", "-b", "feature/source", str(self.source), self.baseline)

    def ledger(
        self,
        expected_snapshot: dict[str, object] | None = None,
        head: str | None = None,
        expected_path: Path | None = None,
    ) -> Path:
        path = self.root / "ledger.json"
        path.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "baseline_commit": self.baseline,
                    "sources": [
                        {
                            "id": "source-feature",
                            "branch": "feature/source",
                            "expected_path": str((expected_path or self.source).resolve()),
                            "expected_head": head or self.baseline,
                            "expected_snapshot": expected_snapshot or source_snapshot(self.source),
                            "disposition": "include",
                        }
                    ],
                }
            )
        )
        return path

    def verify(self, ledger: Path, target: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "bash",
                str(SCRIPT),
                "--ledger",
                str(ledger),
                "--target",
                str(target or self.repository),
                "--json",
            ],
            text=True,
            capture_output=True,
            check=False,
            env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"},
        )

    def pre_work(
        self,
        *,
        target: Path | None = None,
        work_item: str = "issue-123",
        intent: str = "bundle-read-tools",
        candidate: str | Path | None = None,
        registry: Path | None = None,
        baseline: str | None = None,
        extra_environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            "bash",
            str(SCRIPT),
            "--pre-work",
            "--target",
            str(target or self.repository),
            "--baseline",
            baseline or self.baseline,
            "--work-item",
            work_item,
            "--intent",
            intent,
            "--json",
        ]
        if candidate is not None:
            command.extend(("--candidate", str(candidate)))
        if registry is not None:
            command.extend(("--registry", str(registry)))
        return subprocess.run(
            command,
            text=True,
            capture_output=True,
            check=False,
            env={
                **os.environ,
                "GIT_OPTIONAL_LOCKS": "0",
                **(extra_environment or {}),
            },
        )

    def registry(
        self,
        *,
        path: Path | None = None,
        work_item: str = "issue-123",
        intent: str = "bundle-read-tools",
        branch: str | None = "feature/source",
        head: str | None = None,
    ) -> Path:
        registry = self.root / "workspaces.json"
        record: dict[str, object] = {
            "path": str((path or self.source).resolve()),
            "work_item": work_item,
            "intent": intent,
        }
        if branch is not None:
            record["branch"] = branch
        if head is not None:
            record["head"] = head
        registry.write_text(json.dumps({"schema_version": 1, "workspaces": [record]}))
        return registry

    def marker(
        self,
        path: Path | None = None,
        *,
        work_item: str = "issue-123",
        intent: str = "bundle-read-tools",
    ) -> Path:
        marker = (path or self.source) / ".workspace-work-item.json"
        marker.write_text(json.dumps({
            "schema_version": 1,
            "work_item": work_item,
            "intent": intent,
        }))
        return marker

    def test_pre_work_start_allowed_is_read_only_and_reports_missing_tracking(self) -> None:
        refs_before = git(self.repository, "for-each-ref", "--format=%(refname) %(objectname)").stdout
        index = git_path(self.repository, "index")
        status_before = git(self.repository, "status", "--porcelain=v1").stdout
        index_before = index.stat().st_mtime_ns

        completed = self.pre_work()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "START_ALLOWED")
        self.assertEqual(report["target"]["head"], self.baseline)
        self.assertEqual(report["target"]["branch"], "master")
        self.assertEqual(report["target"]["baseline"], self.baseline)
        self.assertEqual(report["matches"], [])
        self.assertEqual(report["remote"]["status"], "NO_TRACKING")
        self.assertEqual(report["changes"]["staged"]["paths"], [])
        self.assertEqual(report["changes"]["unstaged"]["paths"], [])
        self.assertEqual(report["changes"]["untracked"]["paths"], [])
        self.assertEqual(
            git(self.repository, "for-each-ref", "--format=%(refname) %(objectname)").stdout,
            refs_before,
        )
        self.assertEqual(index.stat().st_mtime_ns, index_before)
        self.assertEqual(git(self.repository, "status", "--porcelain=v1").stdout, status_before)

    def test_pre_work_resumes_explicit_registry_or_marker_match(self) -> None:
        (self.source / "backend" / "base.txt").write_text("source work\n")

        registry_result = self.pre_work(registry=self.registry(head=self.baseline))
        self.assertEqual(registry_result.returncode, 1, registry_result.stderr)
        registry_report = json.loads(registry_result.stdout)
        self.assertEqual(registry_report["verdict"], "RESUME_EXISTING")
        self.assertEqual(registry_report["matches"][0]["path"], str(self.source.resolve()))
        self.assertEqual(registry_report["matches"][0]["branch"], "feature/source")
        self.assertEqual(registry_report["matches"][0]["head"], self.baseline)
        self.assertEqual(registry_report["matches"][0]["work_item"], "issue-123")
        self.assertEqual(
            registry_report["matches"][0]["changes"]["unstaged"]["paths"],
            ["backend/base.txt"],
        )

        self.marker()
        marker_result = self.pre_work()
        self.assertEqual(marker_result.returncode, 1, marker_result.stderr)
        marker_report = json.loads(marker_result.stdout)
        self.assertEqual(marker_report["verdict"], "RESUME_EXISTING")
        self.assertIn("marker", marker_report["matches"][0]["evidence"])

    def test_pre_work_same_path_same_content_resumes_but_different_content_collides(self) -> None:
        target_file = self.repository / "backend" / "base.txt"
        source_file = self.source / "backend" / "base.txt"
        self.marker(self.repository)
        target_file.write_text("same implementation\n")
        source_file.write_text("same implementation\n")
        registry = self.registry()

        same = self.pre_work(registry=registry)

        self.assertEqual(same.returncode, 1, same.stderr)
        self.assertEqual(json.loads(same.stdout)["verdict"], "RESUME_EXISTING")

        source_file.write_text("different implementation\n")
        different = self.pre_work(registry=registry)

        self.assertEqual(different.returncode, 1, different.stderr)
        different_report = json.loads(different.stdout)
        self.assertEqual(different_report["verdict"], "COLLISION")
        self.assertIn("backend/base.txt", different_report["reasons"][-1])

    def test_pre_work_reports_reachable_or_normalized_patch_candidate_as_integrated(self) -> None:
        candidate_file = self.source / "backend" / "candidate.txt"
        candidate_file.write_text("candidate\n")
        git(self.source, "add", "backend/candidate.txt")
        git(self.source, "commit", "-m", "candidate")
        candidate_head = git(self.source, "rev-parse", "HEAD").stdout.strip()
        git(self.repository, "merge", "--ff-only", candidate_head)

        reachable = self.pre_work(candidate=candidate_head)

        self.assertEqual(reachable.returncode, 1, reachable.stderr)
        reachable_report = json.loads(reachable.stdout)
        self.assertEqual(reachable_report["verdict"], "ALREADY_INTEGRATED")
        self.assertIn("candidate_commit_reachable", reachable_report["reasons"])

        patch = self.root / "candidate.patch"
        patch.write_text(git(self.source, "show", "--pretty=format:", "--binary", candidate_head).stdout)
        normalized = self.pre_work(candidate=patch)

        self.assertEqual(normalized.returncode, 1, normalized.stderr)
        normalized_report = json.loads(normalized.stdout)
        self.assertEqual(normalized_report["verdict"], "ALREADY_INTEGRATED")
        self.assertIn("candidate_patch_absorbed", normalized_report["reasons"])

    def test_pre_work_fails_closed_for_unrelated_baseline_detached_or_merge_state(self) -> None:
        git(self.repository, "checkout", "--orphan", "unrelated")
        git(self.repository, "rm", "-rf", ".")
        (self.repository / "unrelated.txt").write_text("unrelated\n")
        git(self.repository, "add", "unrelated.txt")
        git(self.repository, "commit", "-m", "unrelated")
        unrelated = git(self.repository, "rev-parse", "HEAD").stdout.strip()
        git(self.repository, "checkout", "master")

        wrong_line = self.pre_work(baseline=unrelated)
        self.assertEqual(wrong_line.returncode, 1, wrong_line.stderr)
        self.assertEqual(json.loads(wrong_line.stdout)["verdict"], "PROTECTED_UNKNOWN")

        git(self.repository, "checkout", "--detach")
        detached = self.pre_work()
        self.assertEqual(detached.returncode, 1, detached.stderr)
        self.assertIn("target_detached", json.loads(detached.stdout)["reasons"])
        git(self.repository, "checkout", "master")

        marker = git_path(self.repository, "MERGE_HEAD")
        marker.write_text(self.baseline + "\n")
        merge_state = self.pre_work()
        self.assertEqual(merge_state.returncode, 1, merge_state.stderr)
        self.assertIn("target_git_operation_in_progress", json.loads(merge_state.stdout)["reasons"])
        marker.unlink()

    def test_pre_work_ignores_ignored_artifacts_and_fails_closed_on_timeout(self) -> None:
        (self.repository / ".gitignore").write_text("node_modules/\n")
        git(self.repository, "add", ".gitignore")
        git(self.repository, "commit", "-m", "ignore artifacts")
        (self.repository / "node_modules").mkdir()
        (self.repository / "node_modules" / "artifact.js").write_text("generated\n")

        ignored = self.pre_work()
        self.assertEqual(ignored.returncode, 0, ignored.stderr)
        self.assertEqual(json.loads(ignored.stdout)["changes"]["untracked"]["paths"], [])

        fake_git = self.root / "slow-git"
        fake_git.write_text("#!/bin/sh\nsleep 1\n")
        fake_git.chmod(0o755)
        timed_out = self.pre_work(extra_environment={
            "WORKSPACE_CONVERGENCE_GIT_BIN": str(fake_git),
            "WORKSPACE_CONVERGENCE_GIT_TIMEOUT_SECONDS": "0.01",
        })
        self.assertEqual(timed_out.returncode, 1, timed_out.stderr)
        timeout_report = json.loads(timed_out.stdout)
        self.assertEqual(timeout_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("scan_timeout", timeout_report["reasons"])

    def test_pre_work_invalid_parameters_or_registry_exit_two(self) -> None:
        invalid_intent = self.pre_work(intent="Not Normalized")
        self.assertEqual(invalid_intent.returncode, 2)
        self.assertEqual(json.loads(invalid_intent.stdout)["verdict"], "INVALID_INPUT")

        registry = self.root / "invalid.json"
        registry.write_text(json.dumps({"schema_version": 1, "workspaces": [{}]}))
        invalid_registry = self.pre_work(registry=registry)
        self.assertEqual(invalid_registry.returncode, 2)
        self.assertEqual(json.loads(invalid_registry.stdout)["verdict"], "INVALID_REGISTRY")

    def test_pre_work_remote_status_uses_only_local_tracking_ref_evidence(self) -> None:
        remote_path = self.root / "remote.git"
        git(self.repository, "init", "--bare", str(remote_path))
        git(self.repository, "remote", "add", "origin", str(remote_path))
        git(self.repository, "update-ref", "refs/remotes/origin/master", self.baseline)
        git(self.repository, "config", "branch.master.remote", "origin")
        git(self.repository, "config", "branch.master.merge", "refs/heads/master")

        current = self.pre_work()

        self.assertEqual(current.returncode, 0, current.stderr)
        current_remote = json.loads(current.stdout)["remote"]
        self.assertEqual(current_remote["status"], "REMOTE_UNVERIFIED")
        self.assertEqual(current_remote["evidence"], "LOCAL_REMOTE_TRACKING_REF_ONLY")
        self.assertEqual(current_remote["local_tracking_relation"], "EQUAL")

        (self.repository / "backend" / "local.txt").write_text("local\n")
        git(self.repository, "add", "backend/local.txt")
        git(self.repository, "commit", "-m", "local only")
        local_only = self.pre_work()

        self.assertEqual(local_only.returncode, 0, local_only.stderr)
        local_remote = json.loads(local_only.stdout)["remote"]
        self.assertEqual(local_remote["status"], "LOCAL_REF_ONLY")
        self.assertEqual(local_remote["local_ref_head"], self.baseline)
        self.assertEqual(local_remote["local_tracking_relation"], "AHEAD")

    def test_pre_work_never_starts_over_target_wip_or_another_claim(self) -> None:
        (self.repository / "backend" / "wip.txt").write_text("wip\n")

        dirty = self.pre_work()

        self.assertEqual(dirty.returncode, 1, dirty.stderr)
        dirty_report = json.loads(dirty.stdout)
        self.assertEqual(dirty_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("target_has_unclaimed_wip", dirty_report["reasons"])

        related_elsewhere = self.pre_work(registry=self.registry(head=self.baseline))
        self.assertEqual(related_elsewhere.returncode, 1, related_elsewhere.stderr)
        related_report = json.loads(related_elsewhere.stdout)
        self.assertEqual(related_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("target_has_unclaimed_wip", related_report["reasons"])

        (self.repository / "backend" / "wip.txt").unlink()
        self.marker(self.repository, work_item="other-ticket", intent="other-intent")
        claimed = self.pre_work()

        self.assertEqual(claimed.returncode, 1, claimed.stderr)
        claimed_report = json.loads(claimed.stdout)
        self.assertEqual(claimed_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("target_claimed_by_other_work", claimed_report["reasons"])

    def test_pre_work_same_ticket_target_is_resume(self) -> None:
        self.marker(self.repository)
        (self.repository / "backend" / "wip.txt").write_text("same ticket\n")

        completed = self.pre_work()

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "RESUME_EXISTING")
        self.assertEqual(report["matches"][0]["path"], str(self.repository.resolve()))

    def test_pre_work_clean_committed_overlap_compares_final_content(self) -> None:
        target_file = self.repository / "backend" / "base.txt"
        source_file = self.source / "backend" / "base.txt"
        target_file.write_text("target implementation\n")
        git(self.repository, "add", "backend/base.txt")
        git(self.repository, "commit", "-m", "target implementation")
        source_file.write_text("source implementation\n")
        git(self.source, "add", "backend/base.txt")
        git(self.source, "commit", "-m", "source implementation")

        completed = self.pre_work(registry=self.registry(
            head=git(self.source, "rev-parse", "HEAD").stdout.strip(),
        ))

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "COLLISION")
        self.assertIn("path_content_collision:backend/base.txt", report["reasons"])

    def test_pre_work_reverted_or_partial_multi_patch_is_not_integrated(self) -> None:
        first = self.source / "backend" / "first.txt"
        first.write_text("first\n")
        git(self.source, "add", "backend/first.txt")
        git(self.source, "commit", "-m", "first candidate")
        first_commit = git(self.source, "rev-parse", "HEAD").stdout.strip()
        git(self.repository, "cherry-pick", first_commit)
        git(self.repository, "revert", "--no-edit", first_commit)

        reverted = self.pre_work(candidate=first_commit)

        self.assertEqual(reverted.returncode, 0, reverted.stderr or reverted.stdout)
        self.assertEqual(json.loads(reverted.stdout)["verdict"], "START_ALLOWED")

        second = self.source / "backend" / "second.txt"
        second.write_text("second\n")
        git(self.source, "add", "backend/second.txt")
        git(self.source, "commit", "-m", "second candidate")
        second_commit = git(self.source, "rev-parse", "HEAD").stdout.strip()
        patch = self.root / "multi.patch"
        patch.write_text(
            git(self.source, "show", "--pretty=format:", "--binary", first_commit).stdout
            + git(self.source, "show", "--pretty=format:", "--binary", second_commit).stdout
        )
        git(self.repository, "cherry-pick", first_commit)

        partial = self.pre_work(candidate=patch)

        self.assertEqual(partial.returncode, 0, partial.stderr)
        self.assertEqual(json.loads(partial.stdout)["verdict"], "START_ALLOWED")

    def test_safe_records_reject_symlink_special_and_oversize_files(self) -> None:
        real_registry = self.registry()
        linked_registry = self.root / "registry-link.json"
        linked_registry.symlink_to(real_registry)

        linked = self.pre_work(registry=linked_registry)

        self.assertEqual(linked.returncode, 2, linked.stderr)
        self.assertEqual(json.loads(linked.stdout)["verdict"], "INVALID_REGISTRY")

        fifo = self.root / "candidate.fifo"
        os.mkfifo(fifo)
        special = self.pre_work(candidate=fifo)
        self.assertEqual(special.returncode, 2, special.stderr)
        self.assertEqual(json.loads(special.stdout)["verdict"], "INVALID_INPUT")

        huge = self.root / "huge.patch"
        huge.write_bytes(b"x" * 2048)
        oversized = self.pre_work(
            candidate=huge,
            extra_environment={"WORKSPACE_CONVERGENCE_MAX_PATCH_BYTES": "1024"},
        )
        self.assertEqual(oversized.returncode, 2, oversized.stderr)
        self.assertIn("too_large", json.loads(oversized.stdout)["reasons"][0])

        real_parent = self.root / "real-parent"
        real_parent.mkdir()
        nested_registry = real_parent / "registry.json"
        nested_registry.write_text(real_registry.read_text())
        alias_parent = self.root / "alias-parent"
        alias_parent.symlink_to(real_parent, target_is_directory=True)
        intermediate = self.pre_work(registry=alias_parent / "registry.json")
        self.assertEqual(intermediate.returncode, 2, intermediate.stderr)
        self.assertEqual(json.loads(intermediate.stdout)["verdict"], "INVALID_REGISTRY")

    def test_ledger_requires_exact_registered_source_path_and_safe_file(self) -> None:
        wrong = self.root / "wrong-source"
        wrong.mkdir()
        mismatch = self.verify(self.ledger(expected_path=wrong))

        self.assertEqual(mismatch.returncode, 1, mismatch.stderr)
        mismatch_report = json.loads(mismatch.stdout)
        self.assertEqual(mismatch_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("worktree_path_changed", mismatch_report["sources"][0]["reasons"])

        ledger = self.ledger()
        linked = self.root / "ledger-link.json"
        linked.symlink_to(ledger)
        unsafe = self.verify(linked)
        self.assertEqual(unsafe.returncode, 2, unsafe.stderr)
        self.assertEqual(json.loads(unsafe.stdout)["verdict"], "INVALID_LEDGER")

        malformed = self.ledger()
        document = json.loads(malformed.read_text())
        document["sources"][0]["expected_snapshot"] = []
        malformed.write_text(json.dumps(document))
        invalid_nested = self.verify(malformed)
        self.assertEqual(invalid_nested.returncode, 2, invalid_nested.stderr)
        invalid_report = json.loads(invalid_nested.stdout)
        self.assertEqual(invalid_report["verdict"], "INVALID_LEDGER")
        self.assertEqual(invalid_report["reason"], "invalid_source_record")

    def test_ledger_reports_deferred_source_drift_without_blocking_ready(self) -> None:
        deferred = self.root / "deferred"
        git(self.repository, "worktree", "add", "-b", "feature/deferred", str(deferred), self.baseline)
        initial = source_snapshot(deferred)
        ledger = self.ledger()
        document = json.loads(ledger.read_text())
        document["deferred_sources"] = [{
            "id": "deferred-active-work",
            "branch": "feature/deferred",
            "expected_path": str(deferred.resolve()),
            "observed_head": self.baseline,
            "observed_status": initial["status"],
            "observed_fingerprints": initial["fingerprints"],
        }]
        ledger.write_text(json.dumps(document))

        current = self.verify(ledger)

        self.assertEqual(current.returncode, 0, current.stderr)
        current_item = json.loads(current.stdout)["deferred_sources"][0]
        self.assertTrue(current_item["current"])
        self.assertFalse(current_item["changed"])

        (deferred / "active.txt").write_text("moving source\n")
        changed = self.verify(ledger)

        self.assertEqual(changed.returncode, 0, changed.stderr)
        changed_report = json.loads(changed.stdout)
        self.assertEqual(changed_report["verdict"], "READY")
        changed_item = changed_report["deferred_sources"][0]
        self.assertFalse(changed_item["current"])
        self.assertTrue(changed_item["changed"])
        self.assertIn("status_changed", changed_item["reasons"])
        self.assertIn("fingerprint_changed", changed_item["reasons"])

    def test_pre_work_freezes_candidate_and_coordination_after_candidate_scan(self) -> None:
        candidate_file = self.source / "backend" / "candidate.txt"
        candidate_file.write_text("candidate\n")
        git(self.source, "add", "backend/candidate.txt")
        git(self.source, "commit", "-m", "candidate")
        candidate_head = git(self.source, "rev-parse", "HEAD").stdout.strip()
        patch = self.root / "candidate.patch"
        patch.write_text(git(
            self.source, "show", "--pretty=format:", "--binary", candidate_head,
        ).stdout)
        registry = self.registry(work_item="other", intent="other-intent")
        real_git = shutil.which("git")
        wrapper = self.root / "mutate-candidate-git"
        flag = self.root / "mutated.flag"
        wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import subprocess, sys\n"
            f"result = subprocess.run([{real_git!r}, *sys.argv[1:]], input=sys.stdin.buffer.read(), capture_output=True)\n"
            "sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr)\n"
            f"flag = {str(flag)!r}\n"
            "if 'patch-id' in sys.argv and not __import__('os').path.exists(flag):\n"
            f"    open({str(patch)!r}, 'ab').write(b'\\n# changed\\n')\n"
            f"    open({str(registry)!r}, 'a').write(' ')\n"
            "    open(flag, 'w').write('done')\n"
            "raise SystemExit(result.returncode)\n"
        )
        wrapper.chmod(0o755)

        completed = self.pre_work(
            candidate=patch,
            registry=registry,
            extra_environment={"WORKSPACE_CONVERGENCE_GIT_BIN": str(wrapper)},
        )

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("candidate_changed_during_scan", report["reasons"])
        self.assertIn("coordination_record_changed_during_scan", report["reasons"])

    def test_ledger_globally_rechecks_every_source_after_registry_scan(self) -> None:
        ledger = self.ledger()
        real_git = shutil.which("git")
        wrapper = self.root / "mutate-source-git"
        counter = self.root / "counter"
        wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import os, subprocess, sys\n"
            f"result = subprocess.run([{real_git!r}, *sys.argv[1:]], capture_output=True)\n"
            "sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr)\n"
            "if 'worktree' in sys.argv and 'list' in sys.argv:\n"
            f"    counter = {str(counter)!r}\n"
            "    value = int(open(counter).read()) if os.path.exists(counter) else 0\n"
            "    value += 1; open(counter, 'w').write(str(value))\n"
            "    if value == 2:\n"
            f"        open({str(self.source / 'backend' / 'base.txt')!r}, 'w').write('changed globally\\n')\n"
            "raise SystemExit(result.returncode)\n"
        )
        wrapper.chmod(0o755)

        completed = subprocess.run(
            ["bash", str(SCRIPT), "--ledger", str(ledger), "--target", str(self.repository), "--json"],
            text=True,
            capture_output=True,
            check=False,
            env={**os.environ, "WORKSPACE_CONVERGENCE_GIT_BIN": str(wrapper)},
        )

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "PROTECTED_UNKNOWN")
        self.assertTrue(any(reason.startswith("source_changed_during_global_scan:")
                            for reason in report["reasons"]))

    def test_pre_work_freezes_shared_worktree_registry_and_matching_source(self) -> None:
        registry = self.registry()
        candidate_file = self.source / "backend" / "candidate.txt"
        candidate_file.write_text("candidate\n")
        git(self.source, "add", "backend/candidate.txt")
        git(self.source, "commit", "-m", "candidate")
        candidate = git(self.source, "rev-parse", "HEAD").stdout.strip()
        real_git = shutil.which("git")
        wrapper = self.root / "mutate-registry-git"
        added = self.root / "late-worktree"
        flag = self.root / "late.flag"
        wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import os, subprocess, sys\n"
            f"result = subprocess.run([{real_git!r}, *sys.argv[1:]], input=sys.stdin.buffer.read(), capture_output=True)\n"
            "sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr)\n"
            f"flag = {str(flag)!r}\n"
            "if 'merge-tree' in sys.argv and not os.path.exists(flag):\n"
            f"    open({str(self.source / 'backend' / 'base.txt')!r}, 'w').write('match changed\\n')\n"
            f"    subprocess.run([{real_git!r}, '-C', {str(self.repository)!r}, 'worktree', 'add', '-b', 'feature/late', {str(added)!r}, {self.baseline!r}], check=True, capture_output=True)\n"
            "    open(flag, 'w').write('done')\n"
            "raise SystemExit(result.returncode)\n"
        )
        wrapper.chmod(0o755)

        completed = self.pre_work(
            candidate=candidate,
            registry=registry,
            extra_environment={"WORKSPACE_CONVERGENCE_GIT_BIN": str(wrapper)},
        )

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("worktree_registry_changed_during_scan", report["reasons"])
        self.assertIn(f"match_changed_during_scan:{self.source.resolve()}", report["reasons"])

    def test_pre_work_matches_intent_and_protects_stale_registry_identity(self) -> None:
        by_intent = self.pre_work(registry=self.registry(
            work_item="different-issue",
            intent="bundle-read-tools",
        ))

        self.assertEqual(by_intent.returncode, 1, by_intent.stderr)
        intent_report = json.loads(by_intent.stdout)
        self.assertEqual(intent_report["verdict"], "RESUME_EXISTING")
        self.assertEqual(intent_report["matches"][0]["matching_on"], ["intent"])

        stale = self.pre_work(registry=self.registry(branch="wrong/branch"))

        self.assertEqual(stale.returncode, 1, stale.stderr)
        stale_report = json.loads(stale.stdout)
        self.assertEqual(stale_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("registry_branch_changed", stale_report["matches"][0]["reasons"])

    def test_pre_work_protects_matching_worktree_merge_detached_and_unrelated_states(self) -> None:
        self.marker()
        merge_marker = git_path(self.source, "MERGE_HEAD")
        merge_marker.write_text(self.baseline + "\n")

        merging = self.pre_work()

        self.assertEqual(merging.returncode, 1, merging.stderr)
        merging_report = json.loads(merging.stdout)
        self.assertEqual(merging_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("git_operation_in_progress", merging_report["matches"][0]["reasons"])
        merge_marker.unlink()

        git(self.source, "checkout", "--detach")
        detached = self.pre_work()

        self.assertEqual(detached.returncode, 1, detached.stderr)
        detached_report = json.loads(detached.stdout)
        self.assertIn("matching_worktree_detached", detached_report["matches"][0]["reasons"])

        git(self.source, "checkout", "--orphan", "unrelated-source")
        git(self.source, "rm", "-rf", ".")
        (self.source / "unrelated.txt").write_text("unrelated\n")
        git(self.source, "add", "unrelated.txt")
        git(self.source, "commit", "-m", "unrelated source")
        self.marker()
        unrelated = self.pre_work()

        self.assertEqual(unrelated.returncode, 1, unrelated.stderr)
        unrelated_report = json.loads(unrelated.stdout)
        self.assertIn("matching_baseline_not_ancestor", unrelated_report["matches"][0]["reasons"])

    def test_pre_work_does_not_use_historical_patch_id_after_later_modification(self) -> None:
        candidate_file = self.source / "backend" / "candidate.txt"
        candidate_file.write_text("candidate\n")
        git(self.source, "add", "backend/candidate.txt")
        git(self.source, "commit", "-m", "candidate source")
        candidate_head = git(self.source, "rev-parse", "HEAD").stdout.strip()

        not_integrated = self.pre_work(candidate=candidate_head)

        self.assertEqual(not_integrated.returncode, 0, not_integrated.stderr)
        self.assertEqual(json.loads(not_integrated.stdout)["verdict"], "START_ALLOWED")

        git(self.repository, "cherry-pick", "--no-commit", candidate_head)
        git(self.repository, "commit", "-m", "manual port with different identity")
        with candidate_file.open("a") as stream:
            stream.write("source-only later change\n")
        target_candidate = self.repository / "backend" / "candidate.txt"
        with target_candidate.open("a") as stream:
            stream.write("later target change\n")
        git(self.repository, "add", "backend/candidate.txt")
        git(self.repository, "commit", "-m", "later target change")

        unproven = self.pre_work(candidate=candidate_head)

        self.assertEqual(unproven.returncode, 0, unproven.stderr)
        unproven_report = json.loads(unproven.stdout)
        self.assertEqual(unproven_report["verdict"], "START_ALLOWED")
        self.assertIn("candidate_not_integrated", unproven_report["reasons"])

    def test_pre_work_detects_target_change_during_whole_scan(self) -> None:
        real_git = shutil.which("git")
        self.assertIsNotNone(real_git)
        wrapper = self.root / "mutating-git"
        changed_file = self.repository / "backend" / "base.txt"
        wrapper.write_text(
            "#!/usr/bin/env python3\n"
            "import subprocess\n"
            "import sys\n"
            f"result = subprocess.run([{real_git!r}, *sys.argv[1:]], capture_output=True)\n"
            "sys.stdout.buffer.write(result.stdout)\n"
            "sys.stderr.buffer.write(result.stderr)\n"
            "if result.returncode == 0 and 'worktree' in sys.argv and 'list' in sys.argv:\n"
            f"    open({str(changed_file)!r}, 'w').write('changed during scan\\n')\n"
            "raise SystemExit(result.returncode)\n"
        )
        wrapper.chmod(0o755)

        completed = self.pre_work(extra_environment={
            "WORKSPACE_CONVERGENCE_GIT_BIN": str(wrapper),
        })

        self.assertEqual(completed.returncode, 1, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("target_changed_during_scan", report["reasons"])

    def test_accepts_development_line_and_matching_source_snapshot_without_mutation(self) -> None:
        ledger = self.ledger()
        refs_before = git(self.repository, "for-each-ref", "--format=%(refname) %(objectname)").stdout
        target_index = git_path(self.repository, "index")
        source_index = git_path(self.source, "index")
        statuses_before = (
            git(self.repository, "status", "--porcelain=v1").stdout,
            git(self.source, "status", "--porcelain=v1").stdout,
        )
        target_index_before = target_index.stat().st_mtime_ns
        source_index_before = source_index.stat().st_mtime_ns

        completed = self.verify(ledger)

        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "READY")
        self.assertEqual(report["sources"][0]["status"], {
            "staged": 0,
            "unstaged": 0,
            "untracked": 0,
        })
        self.assertEqual(git(self.repository, "for-each-ref", "--format=%(refname) %(objectname)").stdout, refs_before)
        self.assertEqual(target_index.stat().st_mtime_ns, target_index_before)
        self.assertEqual(source_index.stat().st_mtime_ns, source_index_before)
        self.assertEqual(
            (
                git(self.repository, "status", "--porcelain=v1").stdout,
                git(self.source, "status", "--porcelain=v1").stdout,
            ),
            statuses_before,
        )

    def test_reports_staged_unstaged_and_untracked_evidence_separately(self) -> None:
        ledger = self.ledger()
        staged = self.source / "staged.txt"
        staged.write_text("staged\n")
        git(self.source, "add", "staged.txt")
        (self.source / "backend" / "base.txt").write_text("changed\n")
        (self.source / "untracked.txt").write_text("untracked\n")

        completed = self.verify(ledger)

        self.assertNotEqual(completed.returncode, 0)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "SOURCE_CHANGED")
        self.assertEqual(report["sources"][0]["status"], {
            "staged": 1,
            "unstaged": 1,
            "untracked": 1,
        })
        self.assertEqual(report["sources"][0]["paths"], {
            "staged": ["staged.txt"],
            "unstaged": ["backend/base.txt"],
            "untracked": ["untracked.txt"],
        })

    def test_rejects_same_count_source_replacement_by_content_fingerprint(self) -> None:
        staged = self.source / "staged.txt"
        tracked = self.source / "backend" / "base.txt"
        untracked = self.source / "untracked.txt"
        staged.write_text("staged-a\n")
        git(self.source, "add", "staged.txt")
        tracked.write_text("unstaged-a\n")
        untracked.write_text("untracked-a\n")
        ledger = self.ledger()

        staged.write_text("staged-b\n")
        git(self.source, "add", "staged.txt")
        tracked.write_text("unstaged-b\n")
        untracked.write_text("untracked-b\n")
        completed = self.verify(ledger)

        self.assertNotEqual(completed.returncode, 0)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "SOURCE_CHANGED")
        self.assertEqual(report["sources"][0]["status"], {
            "staged": 1,
            "unstaged": 1,
            "untracked": 1,
        })
        self.assertIn("fingerprint_changed", report["sources"][0]["reasons"])

    def test_rejects_unrelated_mirror_layout(self) -> None:
        mirror = self.root / "mirror"
        mirror.mkdir()
        git(mirror, "init", "-b", "main")
        git(mirror, "config", "user.name", "Workspace Test")
        git(mirror, "config", "user.email", "workspace@test.invalid")
        (mirror / "jry" / "backend").mkdir(parents=True)
        (mirror / "jry" / "frontend").mkdir()
        (mirror / "jry" / "backend" / "base.txt").write_text("backend\n")
        (mirror / "jry" / "frontend" / "base.txt").write_text("frontend\n")
        git(mirror, "add", "jry")
        git(mirror, "commit", "-m", "mirror")

        completed = self.verify(self.ledger(), mirror)

        self.assertNotEqual(completed.returncode, 0)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "WRONG_BASELINE")

    def test_fails_closed_when_source_head_moves_or_git_operation_is_in_progress(self) -> None:
        (self.source / "new.txt").write_text("new\n")
        git(self.source, "add", "new.txt")
        git(self.source, "commit", "-m", "move source")

        moved = self.verify(self.ledger())

        self.assertNotEqual(moved.returncode, 0)
        moved_report = json.loads(moved.stdout)
        self.assertEqual(moved_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("head_changed", moved_report["sources"][0]["reasons"])

        current_head = git(self.source, "rev-parse", "HEAD").stdout.strip()
        operation_marker = git_path(self.source, "MERGE_HEAD")
        operation_marker.write_text(current_head + "\n")
        self.addCleanup(operation_marker.unlink, missing_ok=True)

        operation = self.verify(self.ledger(head=current_head))

        self.assertNotEqual(operation.returncode, 0)
        operation_report = json.loads(operation.stdout)
        self.assertEqual(operation_report["verdict"], "PROTECTED_UNKNOWN")
        self.assertIn("git_operation_in_progress", operation_report["sources"][0]["reasons"])

    def test_fails_closed_when_target_has_an_unfinished_git_operation(self) -> None:
        operation_marker = git_path(self.repository, "MERGE_HEAD")
        operation_marker.write_text(self.baseline + "\n")
        self.addCleanup(operation_marker.unlink, missing_ok=True)

        completed = self.verify(self.ledger())

        self.assertNotEqual(completed.returncode, 0)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "PROTECTED_UNKNOWN")
        self.assertEqual(report["reason"], "target_git_operation_in_progress")


if __name__ == "__main__":
    unittest.main()
