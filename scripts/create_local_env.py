#!/usr/bin/env python3

from __future__ import annotations

import pathlib
import secrets
import fcntl
import os
import stat
import sys


PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE_PATH = PROJECT_ROOT / "infra" / ".env.example"
TARGET_PATH = PROJECT_ROOT / "infra" / ".env"
SECRET_KEYS = {
    "AUTHWEAVE_POSTGRES_ADMIN_PASSWORD",
    "AUTHWEAVE_CORE_DB_PASSWORD",
    "AUTHWEAVE_WEB_DB_PASSWORD",
    "AUTHWEAVE_CORE_SERVICE_TOKEN",
}


def generated_environment() -> str:
    lines: list[str] = []
    for line in TEMPLATE_PATH.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key in SECRET_KEYS:
            value = secrets.token_urlsafe(32)
        lines.append(f"{key}{separator}{value}")
    return "\n".join(lines) + "\n"


def main() -> None:
    if TARGET_PATH.exists():
        print("infra/.env already exists; leaving it unchanged.")
        return

    TARGET_PATH.write_text(generated_environment(), encoding="utf-8")
    TARGET_PATH.chmod(0o600)
    print("Created ignored infra/.env with generated local passwords.")


def add_core_service_token() -> None:
    if TARGET_PATH.is_symlink():
        raise ValueError("Local infra/.env must not be a symlink.")
    flags = os.O_RDWR | os.O_NOFOLLOW
    with os.fdopen(os.open(TARGET_PATH, flags), "r+", encoding="utf-8") as environment:
        fcntl.flock(environment, fcntl.LOCK_EX)
        metadata = os.fstat(environment.fileno())
        if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ValueError("Local infra/.env must be a regular file with mode 600.")
        contents = environment.read()
        if any(line.startswith("AUTHWEAVE_CORE_SERVICE_TOKEN=") for line in contents.splitlines()):
            print("Local Core service token already exists; leaving it unchanged.")
            return
        environment.seek(0, os.SEEK_END)
        if contents and not contents.endswith("\n"):
            environment.write("\n")
        environment.write("AUTHWEAVE_CORE_SERVICE_TOKEN=" + secrets.token_urlsafe(32) + "\n")
        environment.flush()
        os.fsync(environment.fileno())
    print("Added a local Core service token to ignored infra/.env; no token printed.")


if __name__ == "__main__":
    if sys.argv[1:] == ["--add-core-service-token"]:
        try:
            add_core_service_token()
        except (OSError, ValueError) as failure:
            print(f"Could not add Core service token ({type(failure).__name__}).", file=sys.stderr)
            sys.exit(1)
    elif not sys.argv[1:]:
        main()
    else:
        print("Unsupported setup option.", file=sys.stderr)
        sys.exit(2)
