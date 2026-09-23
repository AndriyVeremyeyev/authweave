#!/usr/bin/env python3
"""Register and verify the isolated local AuthWeave OIDC client and synthetic users."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
from urllib import error, request

import local_identity as identity

PROJECT_NAME = "AuthWeave Local"
APPLICATION_NAME = "AuthWeave Local Web"
CURATOR_ROLE_KEY = "catalog_curator"
CURATOR_ROLE_NAME = "Catalog Curator"
REDIRECT_URI = "http://localhost:3000/api/auth/callback"
LOGOUT_URI = "http://localhost:3000/"
WEB_ENV = identity.ROOT / "apps" / "web" / ".env.local"
USER_ENV = identity.IDENTITY / "synthetic-users.env.local"
USERS = {
    "alice@authweave.localhost": ("Alice", "Example", "AUTHWEAVE_SYNTHETIC_ALICE_PASSWORD"),
    "bob@authweave.localhost": ("Bob", "Example", "AUTHWEAVE_SYNTHETIC_BOB_PASSWORD"),
}


def api(opener, path: str, token: str, payload: dict) -> dict:
    identity.require(path in {
        "/zitadel.project.v2.ProjectService/ListProjects",
        "/zitadel.project.v2.ProjectService/CreateProject",
        "/zitadel.project.v2.ProjectService/ListProjectRoles",
        "/zitadel.project.v2.ProjectService/AddProjectRole",
        "/zitadel.application.v2.ApplicationService/ListApplications",
        "/zitadel.application.v2.ApplicationService/CreateApplication",
        "/v2/users", "/v2/users/new",
    }, "Unexpected local identity operation.")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json", "Content-Type": "application/json"}
    if path.startswith("/zitadel."):
        headers["Connect-Protocol-Version"] = "1"
    call = request.Request(identity.ISSUER + path, data=json.dumps(payload, separators=(",", ":")).encode(),
                           headers=headers, method="POST")
    try:
        with opener.open(call, timeout=15) as response:
            raw = response.read(1_048_577)
            identity.require(200 <= response.status < 300 and len(raw) <= 1_048_576,
                             "Local identity API returned an invalid response.")
            identity.require(response.headers.get_content_type() == "application/json",
                             "Local identity API did not return JSON.")
            document = json.loads(raw)
            identity.require(isinstance(document, dict), "Local identity API response must be an object.")
            return document
    except error.HTTPError as failure:
        failure.read(1_048_577)
        raise RuntimeError(f"Local identity API rejected {path} (HTTP {failure.code}).") from None


def unique_named(items: list[dict], name: str) -> dict | None:
    matches = [item for item in items if item.get("name") == name]
    identity.require(len(matches) <= 1, "Duplicate local identity resource; inspect it manually.")
    return matches[0] if matches else None


def private_file(path: Path, lines: list[str], *, create: bool) -> None:
    identity.require(not path.is_symlink(), "Local identity configuration must not be a symlink.")
    contents = "\n".join(lines) + "\n"
    if path.exists():
        identity.require(path.is_file() and stat.S_IMODE(path.stat().st_mode) == 0o600,
                         "Existing local identity configuration must have mode 600.")
        identity.require(path.read_text(encoding="utf-8") == contents,
                         "Existing local identity configuration differs; left unchanged.")
        return
    identity.require(create, "Local identity configuration is missing; run make auth-register.")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(contents)


def user_passwords(*, create: bool) -> dict[str, str]:
    identity.require(not USER_ENV.is_symlink(), "Synthetic credential file must not be a symlink.")
    if not USER_ENV.exists():
        identity.require(create, "Synthetic credential file is missing; run make auth-register.")
        lines = [f"{key}=Aa1!{secrets.token_urlsafe(32)}" for _, _, key in USERS.values()]
        private_file(USER_ENV, lines, create=True)
    identity.require(USER_ENV.is_file() and stat.S_IMODE(USER_ENV.stat().st_mode) == 0o600,
                     "Synthetic credential file must have mode 600.")
    values = dict(line.split("=", 1) for line in USER_ENV.read_text(encoding="utf-8").splitlines())
    identity.require(set(values) == {entry[2] for entry in USERS.values()}
                     and all(re.fullmatch(r"[A-Za-z0-9_!\-]{24,128}", value) for value in values.values()),
                     "Synthetic credential file is invalid; left unchanged.")
    return values


def web_configuration(client_id: str, *, create: bool) -> None:
    expected = [f"AUTHWEAVE_OIDC_ISSUER={identity.ISSUER}",
                f"AUTHWEAVE_OIDC_CLIENT_ID={client_id}"]
    if not WEB_ENV.exists() or WEB_ENV.is_symlink():
        private_file(WEB_ENV, expected, create=create)
        return
    identity.require(WEB_ENV.is_file() and stat.S_IMODE(WEB_ENV.stat().st_mode) == 0o600,
                     "Existing local web configuration must have mode 600.")
    lines = WEB_ENV.read_text(encoding="utf-8").splitlines()
    for entry in expected:
        key = entry.split("=", 1)[0]
        identity.require(sum(line.startswith(f"{key}=") for line in lines) == 1 and entry in lines,
                         "Existing local web OIDC configuration differs; left unchanged.")


def admin_action(values: dict[str, str], action) -> None:
    opener = request.build_opener(request.ProxyHandler({}), identity.NoRedirect())
    pat = identity.load_login_client_pat(values)
    session_id = None
    session_token = None
    failure = None
    try:
        created = identity.session_request(opener, "POST", "/v2/sessions", pat,
                                           {"checks": {"user": {"loginName": "admin@authweave.localhost"}}})
        session_id, session_token = created.get("sessionId"), created.get("sessionToken")
        identity.require(isinstance(session_id, str) and re.fullmatch(r"[0-9]{1,40}", session_id) is not None,
                         "Local admin session ID is invalid.")
        identity.require(isinstance(session_token, str) and re.fullmatch(r"[A-Za-z0-9._~-]{20,4096}", session_token) is not None,
                         "Local admin session token is invalid.")
        updated = identity.session_request(opener, "PATCH", f"/v2/sessions/{session_id}", pat,
                                           {"sessionToken": session_token, "checks": {"password": {
                                               "password": values["AUTHWEAVE_ZITADEL_ADMIN_PASSWORD"]}}})
        updated_token = updated.get("sessionToken")
        identity.require(isinstance(updated_token, str) and re.fullmatch(r"[A-Za-z0-9._~-]{20,4096}", updated_token) is not None,
                         "Updated local admin session token is invalid.")
        session_token = updated_token
        state = identity.session_request(opener, "GET", f"/v2/sessions/{session_id}?sessionToken={session_token}", pat)
        factors = state.get("session", {}).get("factors", {})
        identity.require(factors.get("user", {}).get("loginName") == "admin@authweave.localhost"
                         and bool(factors.get("password", {}).get("verifiedAt")),
                         "Local administrator authentication was not verified.")
        action(opener, session_token, pat)
    except Exception as caught:
        failure = caught
    if session_id is not None:
        try:
            identity.session_request(opener, "DELETE", f"/v2/sessions/{session_id}", pat,
                                     {"sessionToken": session_token} if session_token else {})
        except Exception:
            raise RuntimeError("Local admin session cleanup was not confirmed.") from None
    if failure is not None:
        raise failure


def get_project(opener, token: str, *, create: bool) -> tuple[str, str]:
    listing = api(opener, "/zitadel.project.v2.ProjectService/ListProjects", token, {"pagination": {"limit": 100}})
    projects = listing.get("projects", [])
    identity.require(isinstance(projects, list) and len(projects) < 100, "Local project list is incomplete.")
    default = unique_named(projects, "ZITADEL")
    identity.require(default is not None, "Local ZITADEL default project is missing.")
    org_id = default.get("organizationId")
    identity.require(isinstance(org_id, str) and re.fullmatch(r"[0-9]{1,40}", org_id) is not None,
                     "Local organization ID is invalid.")
    project = unique_named(projects, PROJECT_NAME)
    if project is None:
        identity.require(create, "AuthWeave local project is missing; run make auth-register.")
        created = api(opener, "/zitadel.project.v2.ProjectService/CreateProject", token,
                      {"organizationId": org_id, "name": PROJECT_NAME,
                       "authorizationRequired": False, "projectAccessRequired": False})
        project_id = created.get("projectId")
    else:
        identity.require(project.get("organizationId") == org_id
                         and project.get("authorizationRequired", False) is False,
                         "Existing local project has unexpected settings.")
        project_id = project.get("projectId")
    identity.require(isinstance(project_id, str) and re.fullmatch(r"[0-9]{1,40}", project_id) is not None,
                     "Local project ID is invalid.")
    return org_id, project_id


def get_curator_role(opener, token: str, project_id: str, *, create: bool) -> None:
    def listed_role() -> dict | None:
        listing = api(opener, "/zitadel.project.v2.ProjectService/ListProjectRoles", token,
                      {"projectId": project_id, "pagination": {"limit": 100}})
        roles = listing.get("projectRoles", [])
        identity.require(isinstance(roles, list) and len(roles) < 100,
                         "Local project role list is incomplete.")
        matches = [role for role in roles if isinstance(role, dict) and role.get("key") == CURATOR_ROLE_KEY]
        identity.require(len(matches) <= 1, "Duplicate local curator role; inspect it manually.")
        return matches[0] if matches else None

    role = listed_role()
    if role is None:
        identity.require(create, "Local curator role is missing; run make auth-register.")
        api(opener, "/zitadel.project.v2.ProjectService/AddProjectRole", token,
            {"projectId": project_id, "roleKey": CURATOR_ROLE_KEY, "displayName": CURATOR_ROLE_NAME})
        role = listed_role()
    identity.require(role is not None and role.get("projectId") == project_id
                     and role.get("displayName") == CURATOR_ROLE_NAME and role.get("group") in (None, ""),
                     "Existing local curator role differs from the approved configuration.")


def verify_application(app: dict, project_id: str) -> str:
    config = app.get("oidcConfiguration", {})
    expected = {
        "redirectUris": [REDIRECT_URI], "postLogoutRedirectUris": [LOGOUT_URI],
        "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
        "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE"],
        # ZITADEL v4.17.3 accepts applicationType at creation but omits it from ListApplications.
        "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
        "developmentMode": True,
    }
    identity.require(app.get("projectId") == project_id and all(config.get(key) == value for key, value in expected.items()),
                     "Existing OIDC application differs from the approved local configuration.")
    client_id = config.get("clientId")
    identity.require(isinstance(client_id, str) and re.fullmatch(r"[A-Za-z0-9@._~-]{3,256}", client_id) is not None,
                     "Local OIDC client ID is invalid.")
    return client_id


def get_application(opener, token: str, project_id: str, *, create: bool) -> str:
    listing = api(opener, "/zitadel.application.v2.ApplicationService/ListApplications", token,
                  {"pagination": {"limit": 100}})
    apps = listing.get("applications", [])
    identity.require(isinstance(apps, list) and len(apps) < 100, "Local application list is incomplete.")
    app = unique_named([item for item in apps if item.get("projectId") == project_id], APPLICATION_NAME)
    if app is None:
        identity.require(create, "AuthWeave OIDC application is missing; run make auth-register.")
        created = api(opener, "/zitadel.application.v2.ApplicationService/CreateApplication", token, {
            "projectId": project_id,
            "name": APPLICATION_NAME,
            "oidcConfiguration": {
                "redirectUris": [REDIRECT_URI], "postLogoutRedirectUris": [LOGOUT_URI],
                "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
                "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE"],
                "applicationType": "OIDC_APP_TYPE_WEB",
                "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
                "developmentMode": True,
            },
        })
        identity.require("clientSecret" not in created.get("oidcConfiguration", {}),
                         "Unexpected OIDC client secret; stop and inspect the local application.")
        listing = api(opener, "/zitadel.application.v2.ApplicationService/ListApplications", token,
                      {"pagination": {"limit": 100}})
        app = unique_named([item for item in listing.get("applications", []) if item.get("projectId") == project_id],
                           APPLICATION_NAME)
    identity.require(app is not None, "Local OIDC application was not found after registration.")
    return verify_application(app, project_id)


def get_users(opener, token: str, org_id: str, passwords: dict[str, str], *, create: bool) -> None:
    for login, (given, family, key) in USERS.items():
        listing = api(opener, "/v2/users", token, {"query": {"limit": 100}})
        users = listing.get("result", [])
        identity.require(isinstance(users, list) and len(users) < 100, "Local user list is incomplete.")
        matches = [user for user in users if user.get("username") == login]
        identity.require(len(matches) <= 1, "Duplicate synthetic user; inspect it manually.")
        if not matches:
            identity.require(create, "Synthetic user is missing; run make auth-register.")
            api(opener, "/v2/users/new", token, {
                "organizationId": org_id, "username": login,
                "human": {"profile": {"givenName": given, "familyName": family},
                          "email": {"email": login, "isVerified": True},
                          "password": {"password": passwords[key], "changeRequired": False}},
            })
            continue
        user = matches[0]
        identity.require(user.get("details", {}).get("resourceOwner") == org_id
                         and user.get("human", {}).get("email", {}).get("email") == login,
                         "Existing synthetic user has unexpected identity details.")


def register() -> None:
    values = identity.read_environment(identity.IDENTITY)
    passwords = user_passwords(create=True)
    def action(opener, token, _pat):
        org_id, project_id = get_project(opener, token, create=True)
        get_curator_role(opener, token, project_id, create=True)
        client_id = get_application(opener, token, project_id, create=True)
        get_users(opener, token, org_id, passwords, create=True)
        web_configuration(client_id, create=True)
    admin_action(values, action)
    verify_user_passwords(values, passwords)
    print("Local OIDC project/application, curator role and two synthetic users registered; no curator grant issued; password factors verified; configuration files remain private.")


def check() -> None:
    values = identity.read_environment(identity.IDENTITY)
    passwords = user_passwords(create=False)
    def action(opener, token, _pat):
        org_id, project_id = get_project(opener, token, create=False)
        get_curator_role(opener, token, project_id, create=False)
        client_id = get_application(opener, token, project_id, create=False)
        get_users(opener, token, org_id, passwords, create=False)
        web_configuration(client_id, create=False)
    admin_action(values, action)
    verify_user_passwords(values, passwords)
    print("Local OIDC project/application, curator role, two synthetic password factors and private web configuration verified; role grants are not checked.")


def verify_user_passwords(values: dict[str, str], passwords: dict[str, str]) -> None:
    pat = identity.load_login_client_pat(values)
    for login, (_, _, key) in USERS.items():
        identity.verify_password(passwords[key], pat, login_name=login)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("register", "check"))
    action = parser.parse_args().action
    try:
        {"register": register, "check": check}[action]()
        return 0
    except Exception as failure:
        # Never print API responses, credentials, token values or exception chains.
        print(f"Local identity {action} failed ({type(failure).__name__}). Inspect the local setup without sharing secrets.",
              file=sys.stderr)
        if isinstance(failure, RuntimeError) and str(failure).startswith("Local identity API rejected"):
            print(str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
