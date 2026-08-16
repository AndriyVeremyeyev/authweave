#!/usr/bin/env python3

from __future__ import annotations

import pathlib
import secrets


PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE_PATH = PROJECT_ROOT / "infra" / ".env.example"
TARGET_PATH = PROJECT_ROOT / "infra" / ".env"
SECRET_KEYS = {
    "AUTHWEAVE_POSTGRES_ADMIN_PASSWORD",
    "AUTHWEAVE_CORE_DB_PASSWORD",
    "AUTHWEAVE_WEB_DB_PASSWORD",
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


if __name__ == "__main__":
    main()
