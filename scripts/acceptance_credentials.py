#!/usr/bin/env python3
"""Create the reusable, repository-external credential bundle for acceptance."""

from __future__ import annotations

import os
import secrets
import stat
import sys
import tempfile
from collections.abc import Mapping
from pathlib import Path

MINIMUM_PASSWORD_LENGTH = 16
ENVIRONMENT_FIELDS = (
    "METABASE_ADMIN_EMAIL",
    "METABASE_ADMIN_PASSWORD",
    "APP_ADMIN_USER",
    "APP_ADMIN_PASSWORD",
    "POSTGRES_USER",
    "POSTGRES_PASSWORD",
    "APP_INTERNAL_SERVICE_NAME",
    "APP_INTERNAL_SERVICE_TOKEN",
)


def prepare_credentials(path: Path) -> None:
    try:
        existing = path.lstat()
    except FileNotFoundError:
        existing = None
    if existing is not None:
        values = _read_existing(path, existing)
        if len(values) not in (4, 6, 8):
            raise ValueError(
                f"acceptance credential file must contain four legacy, six prior, or eight current fields: {path}"
            )
        _validate_credentials(values, path)
        if len(values) < len(ENVIRONMENT_FIELDS):
            upgraded = values
            if len(upgraded) == 4:
                database_password = _random_password(set(upgraded[index] for index in (1, 3)))
                upgraded = (*upgraded, "acceptance-db", database_password)
            internal_token = _random_password(
                set(upgraded[index] for index in range(1, len(upgraded), 2))
            )
            upgraded = (*upgraded, "acceptance-order-assistant", internal_token)
            _validate_credentials(upgraded, path)
            _replace_private(path, upgraded)
        return
    metabase_password = _random_password(set())
    application_password = _random_password({metabase_password})
    database_password = _random_password({metabase_password, application_password})
    internal_token = _random_password({metabase_password, application_password, database_password})
    values = (
        "acceptance@localhost.invalid",
        metabase_password,
        "acceptance-admin",
        application_password,
        "acceptance-db",
        database_password,
        "acceptance-order-assistant",
        internal_token,
    )
    _validate_credentials(values, path)
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write("\n".join(values) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def validate_environment(environment: Mapping[str, str]) -> None:
    values = tuple(environment.get(name, "") for name in ENVIRONMENT_FIELDS)
    _validate_credentials(values, "acceptance credential environment")


def write_environment(path: Path, environment: Mapping[str, str]) -> None:
    values = tuple(environment.get(name, "") for name in ENVIRONMENT_FIELDS)
    _validate_credentials(values, "acceptance credential environment")
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write("\n".join(values) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def load_credentials(path: Path) -> dict[str, str]:
    try:
        existing = path.lstat()
    except FileNotFoundError as error:
        raise PermissionError(f"acceptance credential file does not exist: {path}") from error
    values = _read_existing(path, existing)
    if len(values) != len(ENVIRONMENT_FIELDS):
        raise ValueError(f"acceptance credential file must contain eight current fields: {path}")
    _validate_credentials(values, path)
    return dict(zip(ENVIRONMENT_FIELDS, values, strict=True))


def _validate_credentials(values: tuple[str, ...], source: Path | str) -> None:
    if any("\n" in value or "\r" in value for value in values):
        raise ValueError(f"acceptance credential fields must be single-line values: {source}")
    identity_indices = range(0, len(values), 2)
    password_indices = range(1, len(values), 2)
    if any(not values[index].strip() for index in identity_indices):
        raise ValueError(f"acceptance credential identities must be non-empty: {source}")
    secrets = tuple(values[index] for index in password_indices)
    if any(len(secret) < MINIMUM_PASSWORD_LENGTH or not secret.strip() for secret in secrets):
        raise ValueError(
            f"acceptance secrets must contain at least {MINIMUM_PASSWORD_LENGTH} characters: {source}"
        )
    if len(set(secrets)) != len(secrets):
        raise ValueError(f"acceptance secrets must be distinct: {source}")


def _random_password(excluded: set[str]) -> str:
    while True:
        candidate = secrets.token_urlsafe(32)
        if candidate not in excluded:
            return candidate


def _read_existing(path: Path, before_open: os.stat_result) -> tuple[str, ...]:
    if stat.S_ISLNK(before_open.st_mode) or not stat.S_ISREG(before_open.st_mode):
        raise PermissionError(f"acceptance credential path must be a regular file: {path}")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (before_open.st_dev, before_open.st_ino):
            raise PermissionError(f"acceptance credential file changed while opening: {path}")
        if opened.st_uid != os.getuid():
            raise PermissionError(f"acceptance credential file must be owned by the current user: {path}")
        if stat.S_IMODE(opened.st_mode) != 0o600:
            raise PermissionError(f"acceptance credential file mode must be 0600: {path}")
        return tuple(stream.read().splitlines())


def _replace_private(path: Path, values: tuple[str, ...]) -> None:
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write("\n".join(values) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        temporary.unlink(missing_ok=True)
        raise


def main() -> int:
    if sys.argv[1:] == ["--validate-environment"]:
        try:
            validate_environment(os.environ)
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) == 3 and sys.argv[1] == "--write-environment":
        try:
            write_environment(Path(sys.argv[2]), os.environ)
        except ValueError as error:
            print(error, file=sys.stderr)
            return 1
        return 0
    if len(sys.argv) != 2:
        print(
            "usage: acceptance_credentials.py PATH | --validate-environment | --write-environment PATH",
            file=sys.stderr,
        )
        return 2
    prepare_credentials(Path(sys.argv[1]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
