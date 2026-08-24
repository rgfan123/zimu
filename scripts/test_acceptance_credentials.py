import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from acceptance_credentials import prepare_credentials


class AcceptanceCredentialsTest(unittest.TestCase):
    def test_environment_validation_reuses_the_same_strength_and_distinctness_rules(self) -> None:
        script = Path(__file__).with_name("acceptance_credentials.py")
        valid = {
            "METABASE_ADMIN_EMAIL": "acceptance@localhost.invalid",
            "METABASE_ADMIN_PASSWORD": "metabase-password-1234567890",
            "APP_ADMIN_USER": "acceptance-admin",
            "APP_ADMIN_PASSWORD": "application-password-12345678",
            "POSTGRES_USER": "acceptance-db",
            "POSTGRES_PASSWORD": "postgres-password-1234567890",
            "APP_INTERNAL_SERVICE_NAME": "acceptance-order-assistant",
            "APP_INTERNAL_SERVICE_TOKEN": "internal-service-token-1234567890",
        }

        accepted = subprocess.run(
            [sys.executable, script, "--validate-environment"],
            env={**os.environ, **valid},
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(accepted.returncode, 0, accepted.stderr)

        for name, overrides in {
            "weak": {"APP_ADMIN_PASSWORD": "too-short"},
            "repeated": {"POSTGRES_PASSWORD": valid["APP_ADMIN_PASSWORD"]},
            "multiline": {"APP_ADMIN_PASSWORD": valid["APP_ADMIN_PASSWORD"] + "\nforged-field"},
        }.items():
            with self.subTest(name):
                rejected = subprocess.run(
                    [sys.executable, script, "--validate-environment"],
                    env={**os.environ, **valid, **overrides},
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(rejected.returncode, 0)
                self.assertNotIn(valid["APP_ADMIN_PASSWORD"], rejected.stderr)

    def test_environment_bundle_is_written_privately_without_secret_arguments(self) -> None:
        script = Path(__file__).with_name("acceptance_credentials.py")
        values = {
            "METABASE_ADMIN_EMAIL": "acceptance@localhost.invalid",
            "METABASE_ADMIN_PASSWORD": "metabase-password-1234567890",
            "APP_ADMIN_USER": "acceptance-admin",
            "APP_ADMIN_PASSWORD": "application-password-12345678",
            "POSTGRES_USER": "acceptance-db",
            "POSTGRES_PASSWORD": "postgres-password-1234567890",
            "APP_INTERNAL_SERVICE_NAME": "acceptance-order-assistant",
            "APP_INTERNAL_SERVICE_TOKEN": "internal-service-token-1234567890",
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "explicit.credentials"
            completed = subprocess.run(
                [sys.executable, script, "--write-environment", path],
                env={**os.environ, **values},
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.read_text().splitlines(), list(values.values()))
            self.assertTrue(all(secret not in completed.args for secret in values.values()))

    def test_creates_private_reusable_credentials_with_random_database_password(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "acceptance.credentials"

            prepare_credentials(path)

            first = path.read_bytes()
            lines = first.decode().splitlines()
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(len(lines), 8)
            self.assertEqual(lines[0], "acceptance@localhost.invalid")
            self.assertEqual(lines[2], "acceptance-admin")
            self.assertEqual(lines[4], "acceptance-db")
            self.assertEqual(lines[6], "acceptance-order-assistant")
            self.assertGreaterEqual(len(lines[1]), 32)
            self.assertGreaterEqual(len(lines[3]), 32)
            self.assertGreaterEqual(len(lines[5]), 32)
            self.assertGreaterEqual(len(lines[7]), 32)
            self.assertNotEqual(lines[1], lines[3])
            self.assertNotEqual(lines[3], lines[5])
            self.assertNotIn(lines[7], {lines[1], lines[3], lines[5]})

            prepare_credentials(path)

            self.assertEqual(path.read_bytes(), first)

    def test_creation_enforces_private_mode_even_under_a_restrictive_process_umask(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "restrictive-umask.credentials"
            previous_umask = os.umask(0o277)
            try:
                prepare_credentials(path)
            finally:
                os.umask(previous_umask)

            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_rejects_preexisting_credentials_that_are_not_private_regular_and_owned(self) -> None:
        valid = "\n".join(
            (
                "acceptance@localhost.invalid",
                "m" * 32,
                "acceptance-admin",
                "a" * 32,
                "acceptance-db",
                "d" * 32,
            )
        ) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            target = root / "target.credentials"
            target.write_text(valid)
            target.chmod(0o600)
            symlink = root / "symlink.credentials"
            symlink.symlink_to(target)
            with self.subTest("symlink"):
                with self.assertRaises(PermissionError):
                    prepare_credentials(symlink)

            public = root / "public.credentials"
            public.write_text(valid)
            public.chmod(0o644)
            with self.subTest("mode"):
                with self.assertRaises(PermissionError):
                    prepare_credentials(public)

            foreign = root / "foreign.credentials"
            foreign.write_text(valid)
            foreign.chmod(0o600)
            with self.subTest("owner"):
                with mock.patch("acceptance_credentials.os.getuid", return_value=os.getuid() + 1):
                    with self.assertRaises(PermissionError):
                        prepare_credentials(foreign)

    def test_upgrades_a_private_legacy_bundle_without_rotating_existing_admin_credentials(self) -> None:
        legacy = (
            "acceptance@localhost.invalid",
            "legacy-metabase-password-1234567890",
            "acceptance-admin",
            "legacy-application-password-12345678",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.credentials"
            path.write_text("\n".join(legacy) + "\n")
            path.chmod(0o600)

            prepare_credentials(path)

            upgraded = path.read_text().splitlines()
            self.assertEqual(tuple(upgraded[:4]), legacy)
            self.assertEqual(upgraded[4], "acceptance-db")
            self.assertGreaterEqual(len(upgraded[5]), 32)
            self.assertEqual(upgraded[6], "acceptance-order-assistant")
            self.assertGreaterEqual(len(upgraded[7]), 32)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_upgrades_a_private_six_field_bundle_with_an_independent_internal_identity(self) -> None:
        current = (
            "acceptance@localhost.invalid",
            "metabase-password-1234567890",
            "acceptance-admin",
            "application-password-12345678",
            "acceptance-db",
            "postgres-password-1234567890",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "six-field.credentials"
            path.write_text("\n".join(current) + "\n")
            path.chmod(0o600)

            prepare_credentials(path)

            upgraded = path.read_text().splitlines()
            self.assertEqual(tuple(upgraded[:6]), current)
            self.assertEqual(upgraded[6], "acceptance-order-assistant")
            self.assertGreaterEqual(len(upgraded[7]), 32)
            self.assertNotIn(upgraded[7], {current[1], current[3], current[5]})

    def test_rejects_private_bundles_with_missing_weak_or_repeated_credentials(self) -> None:
        valid = (
            "acceptance@localhost.invalid",
            "metabase-password-1234567890",
            "acceptance-admin",
            "application-password-12345678",
            "acceptance-db",
            "postgres-password-1234567890",
            "acceptance-order-assistant",
            "internal-service-token-1234567890",
        )
        invalid_bundles = {
            "missing-email": ("", *valid[1:]),
            "missing-app-user": (*valid[:2], "", *valid[3:]),
            "missing-db-user": (*valid[:4], "", *valid[5:]),
            "missing-internal-service": (*valid[:6], "", valid[7]),
            "weak-metabase-password": (valid[0], "too-short", *valid[2:]),
            "weak-app-password": (*valid[:3], "too-short", *valid[4:]),
            "weak-db-password": (*valid[:5], "too-short", *valid[6:]),
            "weak-internal-token": (*valid[:7], "too-short"),
            "repeated-passwords": (valid[0], valid[1], valid[2], valid[1], *valid[4:]),
            "reused-internal-token": (*valid[:7], valid[3]),
            "weak-legacy": (valid[0], "too-short", valid[2], valid[3]),
            "repeated-legacy": (valid[0], valid[1], valid[2], valid[1]),
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, values in invalid_bundles.items():
                with self.subTest(name):
                    path = root / f"{name}.credentials"
                    original = "\n".join(values) + "\n"
                    path.write_text(original)
                    path.chmod(0o600)

                    with self.assertRaises(ValueError):
                        prepare_credentials(path)

                    self.assertEqual(path.read_text(), original)


if __name__ == "__main__":
    unittest.main()
