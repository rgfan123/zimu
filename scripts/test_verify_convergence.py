import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


def run(*arguments: str, cwd: Path, check: bool = True, env: dict[str, str] | None = None):
    return subprocess.run(
        list(arguments),
        cwd=cwd,
        text=True,
        capture_output=True,
        check=check,
        env=env,
    )


class VerifyConvergenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repo"
        self.root.mkdir()
        run("git", "init", "-b", "master", cwd=self.root)
        run("git", "config", "user.name", "Convergence Test", cwd=self.root)
        run("git", "config", "user.email", "convergence@test.invalid", cwd=self.root)

        (self.root / "backend/src/main/resources/db/migration").mkdir(parents=True)
        (self.root / "frontend/node_modules").mkdir(parents=True)
        (self.root / "scripts").mkdir()
        (self.root / ".scratch/workspace-convergence-20260830").mkdir(parents=True)
        (self.root / "backend/src/main/resources/db/migration/V1__base.sql").write_text("SELECT 1;\n")
        (self.root / ".gitignore").write_text("__pycache__/\n")
        (self.root / "frontend/package.json").write_text('{"name":"fixture","version":"1.0.0"}\n')
        (self.root / "frontend/package-lock.json").write_text(
            '{"name":"fixture","version":"1.0.0","lockfileVersion":3,"packages":{}}\n'
        )
        shutil.copy(REPOSITORY_ROOT / "scripts/verify-convergence.sh", self.root / "scripts")
        shutil.copy(REPOSITORY_ROOT / "scripts/check-baseline.sh", self.root / "scripts")
        for source in (REPOSITORY_ROOT / "scripts").glob("workspace_convergence*.py"):
            shutil.copy(source, self.root / "scripts")
        (self.root / "scripts/test_workspace_convergence.py").write_text(
            "import unittest\n\n"
            "class SmokeTest(unittest.TestCase):\n"
            "    def test_smoke(self):\n"
            "        self.assertTrue(True)\n"
        )
        (self.root / "scripts/test_verify_convergence.py").write_text(
            "import unittest\n\n"
            "class VerifierSmokeTest(unittest.TestCase):\n"
            "    def test_smoke(self):\n"
            "        self.assertTrue(True)\n"
        )
        (self.root / ".scratch/workspace-convergence-20260830/sources.json").write_text(
            json.dumps({"schema_version": 2, "baseline_commit": "PENDING", "sources": []})
        )
        run("git", "add", ".", cwd=self.root)
        run("git", "commit", "-m", "fixture", cwd=self.root)
        self.baseline = run("git", "rev-parse", "HEAD", cwd=self.root).stdout.strip()
        ledger = self.root / ".scratch/workspace-convergence-20260830/sources.json"
        ledger.write_text(json.dumps({"schema_version": 2, "baseline_commit": self.baseline, "sources": []}))
        run("git", "add", str(ledger), cwd=self.root)
        run("git", "commit", "-m", "freeze ledger", cwd=self.root)

    def environment(self) -> dict[str, str]:
        return {**os.environ, "CONVERGENCE_BRANCH": "master"}

    def fake_tools(self, mvn_body: str = "exit 0") -> tuple[Path, Path]:
        fake_bin = Path(self.temporary.name) / ("bin-" + str(len(list(Path(self.temporary.name).glob("bin-*")))))
        fake_bin.mkdir()
        mvn = fake_bin / "mvn"
        mvn.write_text("#!/bin/sh\n" + mvn_body + "\n")
        mvn.chmod(mvn.stat().st_mode | stat.S_IXUSR)
        npm_log = Path(self.temporary.name) / (fake_bin.name + "-npm.log")
        npm = fake_bin / "npm"
        npm.write_text(
            "#!/bin/sh\n"
            "printf '%s\\n' \"$*\" >> \"$NPM_INVOCATION_LOG\"\n"
            "if [ \"${1:-}\" = ci ]; then mkdir -p node_modules; fi\n"
            "exit 0\n"
        )
        npm.chmod(npm.stat().st_mode | stat.S_IXUSR)
        return fake_bin, npm_log

    def test_preflight_accepts_clean_frozen_identity(self) -> None:
        completed = run(
            "bash",
            "scripts/verify-convergence.sh",
            "--preflight-only",
            cwd=self.root,
            check=False,
            env=self.environment(),
        )

        self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
        self.assertIn("CONVERGENCE_PREFLIGHT_READY", completed.stdout)

    def test_full_gate_rejects_head_committed_while_tests_are_running(self) -> None:
        fake_bin, npm_log = self.fake_tools(
            "git -C .. commit --allow-empty -m concurrent-head-change >/dev/null"
        )
        environment = self.environment()
        environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
        environment["NPM_INVOCATION_LOG"] = str(npm_log)

        completed = run(
            "bash",
            "scripts/verify-convergence.sh",
            cwd=self.root,
            check=False,
            env=environment,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("目标 Git 身份发生变化", completed.stderr)

    def test_full_gate_installs_lockfile_dependencies_in_a_fresh_checkout(self) -> None:
        shutil.rmtree(self.root / "frontend/node_modules")
        fake_bin, npm_log = self.fake_tools()
        environment = self.environment()
        environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
        environment["NPM_INVOCATION_LOG"] = str(npm_log)

        completed = run(
            "bash",
            "scripts/verify-convergence.sh",
            cwd=self.root,
            check=False,
            env=environment,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
        invocations = npm_log.read_text().splitlines()
        self.assertEqual(invocations[0], "ci")
        self.assertEqual(invocations[1:], [
            "run typecheck",
            "run test:unit",
            "run test:component",
            "run build",
        ])

    def test_full_gate_detects_tracked_file_changed_then_restored_during_tests(self) -> None:
        marker = Path(self.temporary.name) / "aba-once"
        migration = "src/main/resources/db/migration/V1__base.sql"
        fake_bin, npm_log = self.fake_tools(
            "if [ ! -e \"$ABA_ONCE_MARKER\" ]; then\n"
            "  : > \"$ABA_ONCE_MARKER\"\n"
            f"  printf 'SELECT 2;\\n' > {migration}\n"
            "  sleep 2\n"
            f"  git show HEAD:backend/{migration} > {migration}\n"
            "fi\n"
            "exit 0"
        )
        environment = self.environment()
        environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
        environment["NPM_INVOCATION_LOG"] = str(npm_log)
        environment["ABA_ONCE_MARKER"] = str(marker)

        completed = run(
            "bash",
            "scripts/verify-convergence.sh",
            cwd=self.root,
            check=False,
            env=environment,
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("验收期间检测到目标工作树变化", completed.stderr)


if __name__ == "__main__":
    unittest.main()
