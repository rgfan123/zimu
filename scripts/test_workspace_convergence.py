import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("check-baseline.sh")


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

    def ledger(self, expected: dict[str, int] | None = None, head: str | None = None) -> Path:
        path = self.root / "ledger.json"
        path.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "baseline_commit": self.baseline,
                    "sources": [
                        {
                            "id": "source-feature",
                            "branch": "feature/source",
                            "expected_head": head or self.baseline,
                            "expected_status": expected or {
                                "staged": 0,
                                "unstaged": 0,
                                "untracked": 0,
                            },
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
        staged = self.source / "staged.txt"
        staged.write_text("staged\n")
        git(self.source, "add", "staged.txt")
        (self.source / "backend" / "base.txt").write_text("changed\n")
        (self.source / "untracked.txt").write_text("untracked\n")

        completed = self.verify(self.ledger())

        self.assertNotEqual(completed.returncode, 0)
        report = json.loads(completed.stdout)
        self.assertEqual(report["verdict"], "SOURCE_CHANGED")
        self.assertEqual(report["sources"][0]["status"], {
            "staged": 1,
            "unstaged": 1,
            "untracked": 1,
        })

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
