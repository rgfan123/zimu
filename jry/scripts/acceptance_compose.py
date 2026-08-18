#!/usr/bin/env python3
"""Exec Docker Compose with credentials loaded only into that required child."""

from __future__ import annotations

import os
import sys
from pathlib import Path

from acceptance_credentials import load_credentials


def main(arguments: list[str] | None = None) -> int:
    values = sys.argv[1:] if arguments is None else arguments
    if len(values) < 4:
        print(
            "usage: acceptance_compose.py CREDENTIALS PROJECT COMPOSE_FILE COMPOSE_ARGS...",
            file=sys.stderr,
        )
        return 2
    credentials_path, project_name, compose_file, *compose_arguments = values
    environment = os.environ.copy()
    environment.update(load_credentials(Path(credentials_path)))
    os.execvpe(
        "docker",
        ["docker", "compose", "-p", project_name, "-f", compose_file, *compose_arguments],
        environment,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
