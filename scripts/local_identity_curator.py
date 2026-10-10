#!/usr/bin/env python3
"""Explicit curator access for one existing synthetic user in the loopback-only lab."""

from __future__ import annotations

import argparse
import re
import sys

import local_identity as identity
import local_identity_registration as registration

LIST_AUTHORIZATIONS = "/zitadel.authorization.v2.AuthorizationService/ListAuthorizations"
CREATE_AUTHORIZATION = "/zitadel.authorization.v2.AuthorizationService/CreateAuthorization"


def numeric_id(value: object) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9]{1,40}", value) is not None


def get_user(opener, token: str, org_id: str, login: str) -> str:
    identity.require(login in registration.USERS, "Choose an existing local Alice or Bob user.")
    listing = registration.api(opener, "/v2/users", token, {"query": {"limit": 100}})
    users = listing.get("result", [])
    identity.require(isinstance(users, list) and len(users) < 100
                     and all(isinstance(user, dict) for user in users), "Local user list is invalid or incomplete.")
    matches = [user for user in users if user.get("username") == login]
    identity.require(len(matches) == 1, "Exactly one existing local user is required; no user will be created.")
    user = matches[0]
    human, details = user.get("human"), user.get("details")
    identity.require(isinstance(human, dict) and isinstance(human.get("email"), dict)
                     and isinstance(details, dict)
                     and details.get("resourceOwner") == org_id
                     and human["email"].get("email") == login
                     and user.get("state") == "USER_STATE_ACTIVE" and numeric_id(user.get("userId")),
                     "Existing local user has unexpected identity details or state.")
    return user["userId"]


def count(value: object) -> int:
    identity.require((type(value) is int and value >= 0)
                     or (isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]{0,19}", value) is not None),
                     "Local authorization pagination is invalid.")
    return int(value)


def read_grant(opener, token: str, org_id: str, project_id: str, user_id: str) -> str | None:
    identity.require(all(numeric_id(value) for value in (org_id, project_id, user_id)), "Local grant scope is invalid.")
    response = registration.api(opener, LIST_AUTHORIZATIONS, token, {
        "pagination": {"limit": 100},
        "filters": [{"inUserIds": {"ids": [user_id]}}, {"projectId": {"id": project_id}},
                    {"organizationId": {"id": org_id}}],
    })
    pagination = response.get("pagination")
    identity.require(isinstance(pagination, dict), "Local authorization pagination is missing.")
    # Protobuf JSON may omit zero counts and empty repeated fields, but not this positive limit.
    limit, total = count(pagination.get("appliedLimit")), count(pagination.get("totalResult", 0))
    assignments = response.get("authorizations", [])
    identity.require(isinstance(assignments, list) and 0 < limit <= 100
                     and len(assignments) == total and total < limit and total <= 1,
                     "Local authorization list is ambiguous or incomplete; inspect it manually.")
    if not assignments:
        return None
    assignment = assignments[0]
    identity.require(isinstance(assignment, dict), "Local authorization is invalid.")
    project, organization, user = (assignment.get(key) for key in ("project", "organization", "user"))
    roles = assignment.get("roles")
    identity.require(isinstance(project, dict) and isinstance(organization, dict) and isinstance(user, dict)
                     and project.get("id") == project_id and project.get("organizationId") == org_id
                     and project.get("name") == registration.PROJECT_NAME
                     and organization.get("id") == org_id
                     and user.get("id") == user_id and user.get("organizationId") == org_id
                     and assignment.get("state") == "STATE_ACTIVE" and numeric_id(assignment.get("id"))
                     and isinstance(roles, list) and len(roles) == 1 and isinstance(roles[0], dict)
                     and roles[0].get("key") == registration.CURATOR_ROLE_KEY,
                     "Existing authorization differs from the exact local curator scope; left unchanged.")
    return assignment["id"]


def ensure_grant(opener, token: str, org_id: str, project_id: str, user_id: str, *, grant: bool) -> str:
    existing = read_grant(opener, token, org_id, project_id, user_id)
    if existing is not None:
        return "ALREADY_GRANTED"
    if not grant:
        return "NOT_GRANTED"
    created = registration.api(opener, CREATE_AUTHORIZATION, token, {
        "userId": user_id, "projectId": project_id, "organizationId": org_id,
        "roleKeys": [registration.CURATOR_ROLE_KEY],
    })
    identity.require(numeric_id(created.get("id")), "Local grant creation was not verified.")
    observed = read_grant(opener, token, org_id, project_id, user_id)
    identity.require(observed == created["id"], "Local grant creation was not verified; run the read-only check.")
    return "GRANTED"


def run(action: str, login: str) -> None:
    identity.require(action in {"check", "grant"} and login in registration.USERS, "Invalid local curator action.")
    identity.require(identity.ISSUER == "http://localhost:8081", "Curator commands require the exact loopback issuer.")
    values = identity.read_environment(identity.IDENTITY)
    identity.check_discovery()
    result = None
    def operation(opener, token, _pat):
        nonlocal result
        org_id, project_id = registration.get_project(opener, token, create=False)
        registration.get_curator_role(opener, token, project_id, create=False)
        client_id = registration.get_application(opener, token, project_id, create=False)
        registration.web_configuration(client_id, project_id, org_id, create=False)
        user_id = get_user(opener, token, org_id, login)
        result = ensure_grant(opener, token, org_id, project_id, user_id, grant=action == "grant")
    registration.admin_action(values, operation)
    print(f"Local curator {login}: {result}; AuthWeave Local project, catalog_curator role only.")
    print("No source verdict, catalog review, publication or evaluation was recorded. Sign in again for a fresh role claim.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "grant"))
    parser.add_argument("--user", required=True, choices=tuple(registration.USERS))
    parser.add_argument("--confirm-local-curator-grant", action="store_true")
    args = parser.parse_args(argv)
    if args.confirm_local_curator_grant != (args.action == "grant"):
        parser.error("Only grant requires --confirm-local-curator-grant; check never accepts it.")
    try:
        run(args.action, args.user)
        return 0
    except Exception as failure:
        # Do not print response bodies, local IDs, credentials, tokens or exception chains.
        print(f"Local curator {args.action} failed ({type(failure).__name__}); no success is claimed.", file=sys.stderr)
        if args.action == "grant":
            print("A failed grant may have committed. Run the read-only check before any explicit retry; no write is retried automatically.",
                  file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
