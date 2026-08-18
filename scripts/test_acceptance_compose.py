import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import acceptance_compose
from acceptance_credentials import ENVIRONMENT_FIELDS, prepare_credentials


class AcceptanceComposeTest(unittest.TestCase):
    def test_only_the_compose_process_receives_credentials_and_no_secret_enters_argv(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            credentials = Path(directory) / "acceptance.credentials"
            prepare_credentials(credentials)
            secrets = credentials.read_text().splitlines()

            with mock.patch.dict(os.environ, {"PATH": "/usr/bin"}, clear=True):
                with mock.patch("acceptance_compose.os.execvpe") as execute:
                    acceptance_compose.main([
                        str(credentials),
                        "zimu-test",
                        "/repo/docker-compose.yml",
                        "config",
                        "--quiet",
                    ])

            executable, arguments, environment = execute.call_args.args
            self.assertEqual(executable, "docker")
            self.assertEqual(arguments, [
                "docker", "compose", "-p", "zimu-test", "-f", "/repo/docker-compose.yml",
                "config", "--quiet",
            ])
            self.assertTrue(all(secret not in arguments for secret in secrets))
            self.assertEqual(
                {name: environment[name] for name in ENVIRONMENT_FIELDS},
                dict(zip(ENVIRONMENT_FIELDS, secrets, strict=True)),
            )


if __name__ == "__main__":
    unittest.main()
