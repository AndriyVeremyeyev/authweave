"""Synthetic local grant tests: no IdP calls, owner verdicts or real grants."""

from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_identity_curator as curator


class CuratorTests(unittest.TestCase):
    ORG = "123"
    PROJECT = "456"
    USER = "789"
    LOGIN = "alice@authweave.localhost"

    def user(self):
        return {"userId": self.USER, "username": self.LOGIN, "state": "USER_STATE_ACTIVE",
                "details": {"resourceOwner": self.ORG},
                "human": {"email": {"email": self.LOGIN}}}

    def assignment(self):
        return {"id": "1001", "state": "STATE_ACTIVE",
                "project": {"id": self.PROJECT, "name": "AuthWeave Local", "organizationId": self.ORG},
                "organization": {"id": self.ORG},
                "user": {"id": self.USER, "organizationId": self.ORG},
                "roles": [{"key": "catalog_curator"}]}

    def listing(self, *assignments):
        return {"pagination": {"totalResult": str(len(assignments)), "appliedLimit": "100"},
                "authorizations": list(assignments)}

    def test_existing_user_has_exact_identity_and_active_state(self):
        with patch.object(curator.registration, "api", return_value={"result": [self.user()]}):
            self.assertEqual(curator.get_user(None, "synthetic-token", self.ORG, self.LOGIN), self.USER)

    def test_no_default_admin_cloud_or_arbitrary_user_target(self):
        for login in ("", "admin@authweave.localhost", "owner@example.com", "Alice@authweave.localhost"):
            with self.subTest(login=login), patch.object(curator.registration, "api") as api:
                with self.assertRaises(ValueError):
                    curator.get_user(None, "synthetic-token", self.ORG, login)
                api.assert_not_called()

    def test_missing_duplicate_truncated_and_invalid_users_cannot_be_granted(self):
        invalid = []
        for mutation in (
                lambda u: u.update(userId="invalid"),
                lambda u: u.update(state="USER_STATE_INACTIVE"),
                lambda u: u["details"].update(resourceOwner="999"),
                lambda u: u["human"]["email"].update(email="bob@authweave.localhost"),
                lambda u: u.update(human=None)):
            value = self.user(); mutation(value); invalid.append({"result": [value]})
        invalid.extend(({"result": []}, {"result": [self.user(), self.user()]},
                        {"result": [self.user()] * 100}, {"result": [None]}, {"result": None}))
        for response in invalid:
            with self.subTest(response=response), patch.object(curator.registration, "api", return_value=response):
                with self.assertRaises(ValueError):
                    curator.get_user(None, "synthetic-token", self.ORG, self.LOGIN)

    def test_assignment_query_is_scoped_and_does_not_filter_away_inactive_or_extra_roles(self):
        with patch.object(curator.registration, "api", return_value=self.listing()) as api:
            self.assertIsNone(curator.read_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER))
        self.assertEqual(api.call_args.args[1], curator.LIST_AUTHORIZATIONS)
        self.assertEqual(api.call_args.args[3], {"pagination": {"limit": 100}, "filters": [
            {"inUserIds": {"ids": [self.USER]}}, {"projectId": {"id": self.PROJECT}},
            {"organizationId": {"id": self.ORG}},
        ]})

    def test_exact_active_single_role_assignment_is_recognized(self):
        with patch.object(curator.registration, "api", return_value=self.listing(self.assignment())):
            self.assertEqual(curator.read_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER), "1001")

    def test_omitted_empty_protobuf_list_and_zero_count_require_valid_pagination(self):
        with patch.object(curator.registration, "api", return_value={"pagination": {"appliedLimit": "100"}}):
            self.assertIsNone(curator.read_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER))

    def test_missing_ambiguous_or_partial_listing_never_means_absent(self):
        invalid = ({}, {"authorizations": []}, self.listing(self.assignment(), self.assignment()),
                   {**self.listing(), "pagination": {"totalResult": "1", "appliedLimit": "100"}},
                   {**self.listing(), "pagination": {"totalResult": "100", "appliedLimit": "100"}},
                   {**self.listing(), "authorizations": None},
                   {**self.listing(), "pagination": {"totalResult": False, "appliedLimit": "100"}},
                   {**self.listing(), "pagination": {"totalResult": 0, "appliedLimit": 0}},
                   {**self.listing(), "pagination": {"totalResult": "0", "appliedLimit": "101"}},
                   {**self.listing(), "pagination": {"totalResult": 0.0, "appliedLimit": "100"}})
        for response in invalid:
            with self.subTest(response=response), patch.object(curator.registration, "api", return_value=response):
                with self.assertRaises(ValueError):
                    curator.read_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER)

    def test_drifted_inactive_foreign_or_extra_roles_are_never_overwritten(self):
        for mutation in (
                lambda a: a.update(id="invalid"),
                lambda a: a.update(state="STATE_INACTIVE"),
                lambda a: a["project"].update(id="999"),
                lambda a: a["project"].update(organizationId="999"),
                lambda a: a["project"].update(name="Another project"),
                lambda a: a["organization"].update(id="999"),
                lambda a: a["user"].update(id="999"),
                lambda a: a["user"].update(organizationId="999"),
                lambda a: a.update(roles=[{"key": "admin"}]),
                lambda a: a["roles"].append({"key": "admin"}),
                lambda a: a.update(roles=[{"key": "catalog_curator"}, {"key": "catalog_curator"}]),
                lambda a: a.update(roles=None)):
            value = self.assignment(); mutation(value)
            with self.subTest(value=value), patch.object(curator.registration, "api", return_value=self.listing(value)):
                with self.assertRaises(ValueError):
                    curator.read_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER)

    def test_check_is_read_only_for_absent_and_existing_assignment(self):
        for existing, expected in ((None, "NOT_GRANTED"), ("1001", "ALREADY_GRANTED")):
            with self.subTest(existing=existing), patch.object(curator, "read_grant", return_value=existing), \
                    patch.object(curator.registration, "api") as api:
                self.assertEqual(curator.ensure_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER,
                                                      grant=False), expected)
                api.assert_not_called()

    def test_explicit_grant_creates_only_exact_role_then_verifies_exact_assignment(self):
        with patch.object(curator, "read_grant", side_effect=[None, "1001"]), \
                patch.object(curator.registration, "api", return_value={"id": "1001"}) as api:
            self.assertEqual(curator.ensure_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER,
                                                  grant=True), "GRANTED")
        self.assertEqual(api.call_count, 1)
        self.assertEqual(api.call_args.args[1:], (curator.CREATE_AUTHORIZATION, "synthetic-token", {
            "userId": self.USER, "projectId": self.PROJECT, "organizationId": self.ORG,
            "roleKeys": ["catalog_curator"],
        }))

    def test_repeat_does_not_write_update_or_delete_an_existing_assignment(self):
        with patch.object(curator, "read_grant", return_value="1001"), patch.object(curator.registration, "api") as api:
            self.assertEqual(curator.ensure_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER,
                                                  grant=True), "ALREADY_GRANTED")
            api.assert_not_called()

    def test_unverified_creation_and_lost_reply_do_not_claim_success_or_retry_a_write(self):
        for response, observed in (({}, "1001"), ({"id": "invalid"}, "1001"),
                                   ({"id": "1001"}, None), ({"id": "1001"}, "1002")):
            with self.subTest(response=response, observed=observed), \
                    patch.object(curator, "read_grant", side_effect=[None, observed]), \
                    patch.object(curator.registration, "api", return_value=response) as api:
                with self.assertRaises(ValueError):
                    curator.ensure_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER, grant=True)
                self.assertEqual(api.call_count, 1)
        with patch.object(curator, "read_grant", return_value=None), \
                patch.object(curator.registration, "api", side_effect=TimeoutError("synthetic lost reply")) as api:
            with self.assertRaises(TimeoutError):
                curator.ensure_grant(None, "synthetic-token", self.ORG, self.PROJECT, self.USER, grant=True)
            self.assertEqual(api.call_count, 1)

    def test_cli_requires_target_and_distinct_grant_confirmation_before_any_action(self):
        for args in (("grant",), ("grant", "--user", self.LOGIN),
                     ("check", "--user", "admin@authweave.localhost"),
                     ("check", "--user", self.LOGIN, "--confirm-local-curator-grant")):
            with self.subTest(args=args), patch.object(curator, "run") as run, redirect_stderr(StringIO()):
                with self.assertRaises(SystemExit):
                    curator.main(list(args))
                run.assert_not_called()

    def test_cli_check_does_not_grant_and_errors_do_not_print_credentials_or_exception_text(self):
        with patch.object(curator, "run") as run, redirect_stdout(StringIO()):
            self.assertEqual(curator.main(["check", "--user", self.LOGIN]), 0)
        run.assert_called_once_with("check", self.LOGIN)
        output = StringIO()
        with patch.object(curator, "run", side_effect=RuntimeError("sensitive-response-do-not-print")), \
                redirect_stderr(output):
            self.assertEqual(curator.main(["grant", "--user", self.LOGIN, "--confirm-local-curator-grant"]), 1)
        self.assertNotIn("sensitive-response-do-not-print", output.getvalue())
        self.assertIn("read-only check", output.getvalue())

    def test_registered_scope_is_reused_without_creating_resources_or_configuration(self):
        def admin_action(_values, action):
            action(None, "synthetic-token", "synthetic-pat")
        with patch.object(curator.identity, "read_environment", return_value={}), \
                patch.object(curator.identity, "check_discovery"), \
                patch.object(curator.registration, "admin_action", side_effect=admin_action), \
                patch.object(curator.registration, "get_project", return_value=(self.ORG, self.PROJECT)) as project, \
                patch.object(curator.registration, "get_curator_role") as role, \
                patch.object(curator.registration, "get_application", return_value="local-client") as application, \
                patch.object(curator.registration, "web_configuration") as configuration, \
                patch.object(curator, "get_user", return_value=self.USER), \
                patch.object(curator, "ensure_grant", return_value="NOT_GRANTED") as grant, \
                redirect_stdout(StringIO()) as output:
            curator.run("check", self.LOGIN)
        project.assert_called_once_with(None, "synthetic-token", create=False)
        role.assert_called_once_with(None, "synthetic-token", self.PROJECT, create=False)
        application.assert_called_once_with(None, "synthetic-token", self.PROJECT, create=False)
        configuration.assert_called_once_with("local-client", self.PROJECT, self.ORG, create=False)
        self.assertFalse(grant.call_args.kwargs["grant"])
        self.assertIn("No source verdict", output.getvalue())

    def test_non_loopback_issuer_is_rejected_before_loading_credentials(self):
        with patch.object(curator.identity, "ISSUER", "https://cloud.example.invalid"), \
                patch.object(curator.identity, "read_environment") as read:
            with self.assertRaises(ValueError):
                curator.run("check", self.LOGIN)
            read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
