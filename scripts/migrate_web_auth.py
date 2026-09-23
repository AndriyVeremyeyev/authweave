#!/usr/bin/env python3
"""Apply the isolated web-owned authentication migration to the local PostgreSQL lab."""

from __future__ import annotations

import os
from pathlib import Path
import re
import stat
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
ENV = ROOT / "infra" / ".env"
COMPOSE = ROOT / "infra" / "compose.yaml"
MIGRATIONS = (
    ROOT / "apps" / "web" / "db" / "migrations" / "001_auth_sessions.sql",
    ROOT / "apps" / "web" / "db" / "migrations" / "002_session_workspace.sql",
    ROOT / "apps" / "web" / "db" / "migrations" / "003_session_curator_scope.sql",
    ROOT / "apps" / "web" / "db" / "migrations" / "004_reauthentication_transactions.sql",
)


def config() -> tuple[str, str]:
    if ENV.is_symlink() or not ENV.is_file() or stat.S_IMODE(ENV.stat().st_mode) != 0o600:
        raise ValueError("Local infra/.env must exist with mode 600 and must not be a symlink.")
    values: dict[str, str] = {}
    for line in ENV.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in values:
            raise ValueError("Local infra/.env contains an invalid or duplicate key.")
        values[key] = value
    user = values.get("AUTHWEAVE_POSTGRES_ADMIN_USER", "")
    database = values.get("AUTHWEAVE_POSTGRES_DB", "")
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", user) or not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", database):
        raise ValueError("Local PostgreSQL administrator or database name is invalid.")
    return user, database


def migrate() -> None:
    user, database = config()
    # Compose reads the ignored file; sanitized process variables cannot override it.
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("COMPOSE_", "AUTHWEAVE_"))}
    command = ["docker", "compose", "--project-name", "authweave", "--file", str(COMPOSE),
               "--env-file", str(ENV), "exec", "-T", "postgres", "psql", "--no-psqlrc",
               "--set=ON_ERROR_STOP=1", "--username", user, "--dbname", database]
    for sql_file in MIGRATIONS:
        subprocess.run(command, input=sql_file.read_text(encoding="utf-8"), text=True,
                       env=environment, check=True, capture_output=True)
    print("Web authentication migration applied or already current; no credentials printed.")


if __name__ == "__main__":
    try:
        migrate()
    except (ValueError, OSError, subprocess.CalledProcessError) as failure:
        print(f"Web authentication migration failed ({type(failure).__name__}); no success is claimed.",
              file=sys.stderr)
        sys.exit(1)
