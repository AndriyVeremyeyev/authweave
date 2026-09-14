#!/usr/bin/env python3
"""Explicit local IdP lifecycle. No cloud calls, credential output or destructive reset."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import tomllib
from urllib import error, request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
IDENTITY = ROOT / "infra" / "zitadel"
ISSUER = "http://localhost:8081"
KEYS = (
    "AUTHWEAVE_ZITADEL_MASTERKEY",
    "AUTHWEAVE_ZITADEL_DB_PASSWORD",
    "AUTHWEAVE_ZITADEL_ADMIN_PASSWORD",
    "AUTHWEAVE_ZITADEL_LOGIN_PAT_EXPIRES",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def create_environment(directory: Path, now: datetime) -> bool:
    target = directory / ".env"
    # Refuse symlinks (including dangling ones) and never replace existing credentials.
    if target.is_symlink():
        raise ValueError("Identity .env must not be a symlink.")
    if target.exists():
        return False
    values = (
        secrets.token_hex(16),
        secrets.token_hex(32),
        "Aa1!" + secrets.token_urlsafe(32),
        (now + timedelta(days=90)).strftime("%Y-%m-%dT%H:%M:%SZ"),
    )
    lines = ["# Generated local-only secrets. Never publish or regenerate for an existing identity database."]
    lines.extend(f"{key}={value}" for key, value in zip(KEYS, values, strict=True))
    # Exclusive creation is race-safe and restrictive permissions apply before the first byte.
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")
    return True


def read_environment(directory: Path) -> dict[str, str]:
    target = directory / ".env"
    require(not target.is_symlink(), "Identity .env must not be a symlink.")
    require(target.is_file(), "Run make setup-auth first.")
    require(stat.S_IMODE(target.stat().st_mode) == 0o600, "Identity .env must have mode 600; do not share it.")
    result: dict[str, str] = {}
    for line in target.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(bool(separator) and key in KEYS and key not in result, "Invalid or duplicate identity environment key.")
        result[key] = value
    require(set(result) == set(KEYS), "Incomplete identity .env; do not overwrite existing credentials.")
    require(re.fullmatch(r"[0-9a-f]{32}", result[KEYS[0]]) is not None, "Invalid local master key format.")
    require(re.fullmatch(r"[0-9a-f]{64}", result[KEYS[1]]) is not None, "Invalid local database password format.")
    password = result[KEYS[2]]
    require(re.fullmatch(r"[A-Za-z0-9_!\-]{24,128}", password) is not None
            and all(re.search(pattern, password) for pattern in ("[A-Z]", "[a-z]", "[0-9]", "[!_-]")),
            "Invalid local administrator password format.")
    try:
        datetime.strptime(result[KEYS[3]], "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise ValueError("Invalid login client expiry format.") from None
    return result


def compose(arguments: list[str], values: dict[str, str], *, capture: bool = False) -> subprocess.CompletedProcess:
    # Explicit files/project and our validated values prevent ambient Compose settings from
    # loading the application stack or replacing its credentials. Never print rendered config.
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("COMPOSE_", "AUTHWEAVE_ZITADEL_"))}
    environment.update(values)
    return subprocess.run(
        ["docker", "compose", "--project-name", "authweave-identity", "--env-file", os.devnull,
         "--file", str(IDENTITY / "compose.yaml"), *arguments],
        env=environment, check=True, capture_output=capture, text=True,
    )


def validate_config(config: dict, routes: dict) -> None:
    require(config["name"] == "authweave-identity", "Identity project must remain isolated.")
    services = config["services"]
    require(set(services) == {"proxy", "zitadel-api", "zitadel-login", "postgres"}, "Unexpected identity service.")
    for name, service in services.items():
        require(re.search(r":[^/@]+@sha256:[0-9a-f]{64}$", service["image"]) is not None, "Pin every image tag and digest.")
        require(not service.get("network_mode") and not service.get("privileged"), "No host networking or privileged services.")
        require(not service.get("restart") or service["restart"] == "no", "Identity startup must be explicit.")
        require("healthcheck" in service, "Identity services need health checks.")
        if name != "proxy":
            require(not service.get("ports"), "Only the identity proxy may publish a port.")
        for volume in service.get("volumes", []):
            require(volume["type"] == "volume" or (
                name == "proxy" and volume["type"] == "bind" and volume.get("read_only")
                and volume["source"] == str(IDENTITY / "routes.toml")
                and volume["target"] == "/etc/traefik/routes.toml"), "Unexpected host mount in identity stack.")
    ports = services["proxy"].get("ports", [])
    require(len(ports) == 1 and ports[0].get("host_ip") == "127.0.0.1"
            and str(ports[0]["published"]) == "8081" and ports[0]["target"] == 8081,
            "Identity must publish only loopback port 8081.")
    require(set(config.get("volumes", {})) == {"bootstrap", "identity-data"}, "Unexpected identity volume.")
    for name, volume in config["volumes"].items():
        require(not volume.get("external") and volume["name"] == f"authweave-identity_{name}", "Identity volume must not be shared.")
    require(set(config.get("networks", {})) == {"default"}
            and config["networks"]["default"]["name"] == "authweave-identity_default"
            and not config["networks"]["default"].get("external"), "Identity network must not be shared.")
    api = services["zitadel-api"]["environment"]
    require((api["ZITADEL_EXTERNALDOMAIN"], str(api["ZITADEL_EXTERNALPORT"]), api["ZITADEL_EXTERNALSECURE"])
            == ("localhost", "8081", "false"), "Unexpected local issuer.")
    dsn = urlsplit(api["ZITADEL_DATABASE_POSTGRES_DSN"])
    require(dsn.hostname == "postgres" and dsn.port == 5432 and dsn.username == "zitadel"
            and dsn.path == "/zitadel" and dsn.password == services["postgres"]["environment"]["POSTGRES_PASSWORD"],
            "Identity database binding is inconsistent.")
    login_volumes = services["zitadel-login"]["volumes"]
    require(len(login_volumes) == 1 and login_volumes[0]["type"] == "volume"
            and login_volumes[0]["source"] == "bootstrap" and login_volumes[0]["target"] == "/zitadel/bootstrap"
            and login_volumes[0].get("read_only"), "Login must mount only read-only bootstrap data.")
    proxy_flags = services["proxy"]["command"]
    require("--providers.file.filename=/etc/traefik/routes.toml" in proxy_flags
            and "--api.dashboard=false" in proxy_flags and "--accesslog=false" in proxy_flags
            and not any("providers.docker" in flag or "api.insecure" in flag for flag in proxy_flags),
            "Proxy must use static routes without discovery, dashboard or access logs.")
    http = routes["http"]
    require(set(http["routers"]) == {"login", "console-api", "api"}, "Unexpected identity proxy route.")
    require(all("Host(`localhost`)" in route["rule"] and route["entryPoints"] == ["web"]
                for route in http["routers"].values()), "Proxy routes must restrict the local hostname.")
    require(http["services"] == {
        "api": {"loadBalancer": {"servers": [{"url": "h2c://zitadel-api:8080"}]}},
        "login": {"loadBalancer": {"servers": [{"url": "http://zitadel-login:3000"}]}},
    }, "Proxy may route only to the isolated IdP, not the AuthWeave API.")


def config_check() -> None:
    # Disposable synthetic values only: never load the owner's .env for tests.
    values = dict(zip(KEYS, ("0" * 32, "1" * 64, "Aa1!" + "2" * 32, "2099-01-01T00:00:00Z"), strict=True))
    parsed = json.loads(compose(["config", "--format", "json"], values, capture=True).stdout)
    validate_config(parsed, tomllib.loads((IDENTITY / "routes.toml").read_text(encoding="utf-8")))
    print("Identity configuration checks passed. No images pulled, containers started or local secrets read.")


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_discovery(document: dict) -> None:
    require(document.get("issuer") == ISSUER, "Unexpected OIDC issuer.")
    for key in ("authorization_endpoint", "token_endpoint", "jwks_uri", "end_session_endpoint"):
        url = urlsplit(document.get(key, ""))
        require(url.scheme == "http" and url.netloc == "localhost:8081" and not url.fragment
                and url.path.startswith("/"), "OIDC endpoints must use the expected loopback issuer.")
    require("S256" in document.get("code_challenge_methods_supported", []), "OIDC provider must support PKCE S256.")
    require("code" in document.get("response_types_supported", []), "OIDC provider must support Authorization Code.")


def check_discovery() -> None:
    # No cookies, proxy environment, redirects or credentials; never follow discovery URLs.
    opener = request.build_opener(request.ProxyHandler({}), NoRedirect())
    with opener.open(f"{ISSUER}/.well-known/openid-configuration", timeout=10) as response:
        payload = response.read(1_048_577)
        require(len(payload) <= 1_048_576, "OIDC discovery response is too large.")
        validate_discovery(json.loads(payload))
    with opener.open(f"{ISSUER}/ui/v2/login/healthy", timeout=10) as response:
        require(response.status == 200, "Login UI is not healthy.")
    print(f"OIDC discovery and Login UI reachable at {ISSUER}. This does not verify application login or curator authorization.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("setup", "config-check", "up", "status", "down", "check"))
    action = parser.parse_args().action
    try:
        if action == "setup":
            changed = create_environment(IDENTITY, datetime.now(timezone.utc))
            print("Created ignored infra/zitadel/.env (mode 600); no secrets printed." if changed
                  else "Identity .env already exists; left unchanged.")
        elif action == "config-check":
            config_check()
        elif action == "check":
            check_discovery()
        else:
            values = read_environment(IDENTITY)
            if action == "up":
                expiry = datetime.strptime(values[KEYS[3]], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
                require(expiry > datetime.now(timezone.utc), "Login client expiry has passed. Arrange PAT rotation; do not regenerate .env or delete volumes.")
            arguments = {"up": ["up", "--detach", "--wait", "--wait-timeout", "240"], "status": ["ps"], "down": ["down"]}[action]
            compose(arguments, values)
        return 0
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError, error.URLError) as failure:
        # Subprocess diagnostics/config and HTTP errors may contain sensitive values or URLs.
        print(f"Identity {action} failed ({type(failure).__name__}). Check local configuration and the README; no success is claimed.", file=sys.stderr)
        if isinstance(failure, ValueError) and not isinstance(failure, json.JSONDecodeError):
            print(str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
