"""Shared fail-closed I/O and Git helpers for the convergence gate."""

from __future__ import annotations

import hashlib
import os
import stat
import subprocess
from dataclasses import dataclass
from pathlib import Path


DEFAULT_TIMEOUT_SECONDS = 8.0
DEFAULT_MAX_COORDINATION_BYTES = 1024 * 1024
DEFAULT_MAX_LEDGER_BYTES = 4 * 1024 * 1024
DEFAULT_MAX_PATCH_BYTES = 128 * 1024 * 1024


class ObservationError(RuntimeError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class RecordError(RuntimeError):
    def __init__(self, verdict: str, reason: str) -> None:
        super().__init__(reason)
        self.verdict = verdict
        self.reason = reason


@dataclass(frozen=True)
class SafeFile:
    content: bytes
    signature: str
    path: str


def env_limit(name: str, default: int, invalid_reason: str) -> int:
    try:
        value = int(os.environ.get(name, default))
    except ValueError as error:
        raise ObservationError(invalid_reason) from error
    if value < 0:
        raise ObservationError(invalid_reason)
    return value


def timeout_seconds() -> float:
    try:
        value = float(os.environ.get(
            "WORKSPACE_CONVERGENCE_GIT_TIMEOUT_SECONDS",
            DEFAULT_TIMEOUT_SECONDS,
        ))
    except ValueError as error:
        raise ObservationError("invalid_scan_timeout") from error
    if value <= 0:
        raise ObservationError("invalid_scan_timeout")
    return value


def _git_command(worktree: Path, arguments: tuple[str, ...]) -> list[str]:
    return [
        os.environ.get("WORKSPACE_CONVERGENCE_GIT_BIN", "git"),
        "-C",
        os.fsdecode(worktree),
        "-c",
        "core.fsmonitor=false",
        *arguments,
    ]


def git(
    worktree: Path,
    *arguments: str,
    allow_failure: bool = False,
    binary: bool = False,
    content: bytes | None = None,
) -> subprocess.CompletedProcess:
    environment = os.environ.copy()
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    try:
        completed = subprocess.run(
            _git_command(worktree, arguments),
            input=content,
            text=False if binary or content is not None else True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds(),
            env=environment,
        )
    except subprocess.TimeoutExpired as error:
        raise ObservationError("scan_timeout") from error
    except OSError as error:
        raise ObservationError("git_observation_failed") from error
    if completed.returncode != 0 and not allow_failure:
        raise ObservationError("git_observation_failed")
    return completed


def digest_frames(frames: list[bytes]) -> str:
    digest = hashlib.sha256()
    for frame in frames:
        digest.update(len(frame).to_bytes(8, byteorder="big"))
        digest.update(frame)
    return digest.hexdigest()


def _canonical_safe_path(path: Path) -> Path:
    lexical = os.path.abspath(os.fspath(path))
    canonical = os.path.realpath(lexical)
    platform_lexical = lexical
    if lexical == "/var" or lexical.startswith("/var/"):
        platform_lexical = "/private" + lexical
    if canonical != platform_lexical:
        raise ObservationError("unsafe_path_symlink")
    return Path(canonical)


def _open_regular_nofollow(path: Path) -> tuple[int, os.stat_result, str]:
    canonical = _canonical_safe_path(path)
    parts = canonical.parts
    descriptor = os.open(parts[0], os.O_RDONLY | os.O_DIRECTORY)
    try:
        for component in parts[1:-1]:
            child = os.open(
                component,
                os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_NONBLOCK", 0),
                dir_fd=descriptor,
            )
            opened = os.fstat(child)
            if not stat.S_ISDIR(opened.st_mode):
                os.close(child)
                raise ObservationError("unsafe_path_component")
            os.close(descriptor)
            descriptor = child
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
        file_descriptor = os.open(parts[-1], flags, dir_fd=descriptor)
    except OSError as error:
        raise ObservationError("unsafe_file_unreadable") from error
    finally:
        os.close(descriptor)
    opened = os.fstat(file_descriptor)
    if not stat.S_ISREG(opened.st_mode):
        os.close(file_descriptor)
        raise ObservationError("unsafe_file_not_regular")
    return file_descriptor, opened, str(canonical)


def safe_read(path: Path, maximum: int, too_large_reason: str) -> SafeFile:
    descriptor, opened, canonical = _open_regular_nofollow(path)
    try:
        if opened.st_size > maximum:
            raise ObservationError(too_large_reason)
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, min(1024 * 1024, maximum + 1 - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > maximum:
                raise ObservationError(too_large_reason)
        current = os.fstat(descriptor)
        identity = ("st_mode", "st_ino", "st_size", "st_mtime_ns")
        if any(getattr(opened, key) != getattr(current, key) for key in identity):
            raise ObservationError("safe_file_changed_during_read")
        content = b"".join(chunks)
        signature = digest_frames([
            canonical.encode(),
            str(opened.st_dev).encode(),
            str(opened.st_ino).encode(),
            str(opened.st_size).encode(),
            str(opened.st_mtime_ns).encode(),
            hashlib.sha256(content).digest(),
        ])
        return SafeFile(content, signature, canonical)
    finally:
        os.close(descriptor)


def inspect_path(path: Path) -> tuple[str, Path]:
    canonical = _canonical_safe_path(path)
    try:
        observed = os.lstat(canonical)
    except OSError as error:
        raise ObservationError("unsafe_path_unreadable") from error
    if stat.S_ISREG(observed.st_mode):
        return "file", canonical
    if stat.S_ISDIR(observed.st_mode):
        return "directory", canonical
    raise ObservationError("unsafe_file_not_regular")
