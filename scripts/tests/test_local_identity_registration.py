"""Local-only registration tests use synthetic fixtures and never contact ZITADEL."""

from copy import deepcopy
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_identity_registration as registration


class PrivateConfigurationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="authweave-registration-test-")
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "synthetic.env.local"

    def test_missing_file_is_not_created_by_read_only_check(self):
        with self.assertRaisesRegex(ValueError, "missing"):
            registration.private_file(self.path, ["EXAMPLE=local"], create=False)
        self.assertFalse(self.path.exists())
        with patch.object(registration, "USER_ENV", self.path):
            with self.assertRaisesRegex(ValueError, "missing"):
                registration.user_passwords(create=False)
        self.assertFalse(self.path.exists())

    def test_private_file_creation_is_exclusive_and_repeated_check_preserves_bytes(self):
        registration.private_file(self.path, ["EXAMPLE=local"], create=True)
        original = self.path.read_bytes()
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        registration.private_file(self.path, ["EXAMPLE=local"], create=False)
        self.assertEqual(self.path.read_bytes(), original)
        with self.assertRaisesRegex(ValueError, "differs"):
            registration.private_file(self.path, ["EXAMPLE=other"], create=True)
        self.assertEqual(self.path.read_bytes(), original)

    def test_reject_symlink_and_world_readable_file(self):
        target = self.path.parent / "other"
        target.write_text("preserve")
        self.path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "symlink"):
            registration.private_file(self.path, ["EXAMPLE=local"], create=True)
        self.path.unlink()
        self.path.write_text("EXAMPLE=local\n")
        self.path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "mode 600"):
            registration.private_file(self.path, ["EXAMPLE=local"], create=False)

    def test_synthetic_passwords_are_generated_once_and_never_printed(self):
        with patch.object(registration, "USER_ENV", self.path):
            initial = registration.user_passwords(create=True)
            original = self.path.read_bytes()
            self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
            self.assertEqual(initial, registration.user_passwords(create=False))
            self.assertEqual(original, self.path.read_bytes())
            self.assertEqual(len(initial), 2)
            self.assertTrue(all(value.startswith("Aa1!") and len(value) >= 24 for value in initial.values()))

    def test_web_oidc_settings_allow_unrelated_future_settings_without_rewriting(self):
        with patch.object(registration, "WEB_ENV", self.path):
            registration.web_configuration("local-client", create=True)
            original = self.path.read_text()
            self.path.write_text(original + "UNRELATED_FUTURE_SETTING=local\n")
            preserved = self.path.read_bytes()
            registration.web_configuration("local-client", create=False)
            self.assertEqual(self.path.read_bytes(), preserved)
            with self.assertRaisesRegex(ValueError, "differs"):
                registration.web_configuration("other-client", create=False)
            self.assertEqual(self.path.read_bytes(), preserved)


class RegistrationContractTests(unittest.TestCase):
    PROJECT_ID = "123456789012345678"
    CLIENT_ID = "123456789012345678@authweave"

    def app(self):
        return {
            "name": registration.APPLICATION_NAME,
            "projectId": self.PROJECT_ID,
            "oidcConfiguration": {
                "redirectUris": [registration.REDIRECT_URI],
                "postLogoutRedirectUris": [registration.LOGOUT_URI],
                "responseTypes": ["OIDC_RESPONSE_TYPE_CODE"],
                "grantTypes": ["OIDC_GRANT_TYPE_AUTHORIZATION_CODE"],
                "authMethodType": "OIDC_AUTH_METHOD_TYPE_NONE",
                "developmentMode": True,
                "clientId": self.CLIENT_ID,
            },
        }

    def test_expected_public_client_and_exact_redirects(self):
        self.assertEqual(registration.verify_application(self.app(), self.PROJECT_ID), self.CLIENT_ID)

    def test_reject_implicit_flow_secret_based_auth_and_redirect_drift(self):
        for key, value in (
            ("redirectUris", ["https://remote.example/callback"]),
            ("responseTypes", ["OIDC_RESPONSE_TYPE_ID_TOKEN"]),
            ("grantTypes", ["OIDC_GRANT_TYPE_IMPLICIT"]),
            ("authMethodType", "OIDC_AUTH_METHOD_TYPE_BASIC"),
            ("developmentMode", False),
        ):
            with self.subTest(key=key):
                app = deepcopy(self.app())
                app["oidcConfiguration"][key] = value
                with self.assertRaisesRegex(ValueError, "differs"):
                    registration.verify_application(app, self.PROJECT_ID)

    def test_duplicate_resource_is_not_silently_accepted(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            registration.unique_named([{"name": "same"}, {"name": "same"}], "same")

    def test_read_only_check_refuses_missing_application_without_creating_one(self):
        with patch.object(registration, "api", return_value={"applications": []}) as api:
            with self.assertRaisesRegex(ValueError, "missing"):
                registration.get_application(None, "synthetic-token", self.PROJECT_ID, create=False)
        self.assertEqual(api.call_count, 1)
        self.assertEqual(api.call_args.args[1], "/zitadel.application.v2.ApplicationService/ListApplications")

    def test_admin_session_is_deleted_after_success_or_action_failure(self):
        for should_fail in (False, True):
            with self.subTest(should_fail=should_fail):
                calls = []
                def session_call(_opener, method, path, authorization, payload=None):
                    calls.append((method, path, authorization, payload))
                    if method == "POST":
                        return {"sessionId": self.PROJECT_ID, "sessionToken": "a" * 32}
                    if method == "PATCH":
                        return {"sessionToken": "b" * 32}
                    if method == "GET":
                        return {"session": {"factors": {"user": {
                            "loginName": "admin@authweave.localhost"},
                            "password": {"verifiedAt": "2026-09-22T12:00:00Z"}}}}
                    if method == "DELETE":
                        return {}
                    self.fail(method)
                def action(_opener, token, pat):
                    self.assertEqual(token, "b" * 32)
                    self.assertEqual(pat, "p" * 32)
                    if should_fail:
                        raise RuntimeError("synthetic action failure")
                with patch.object(registration.identity, "load_login_client_pat", return_value="p" * 32), \
                        patch.object(registration.identity, "session_request", side_effect=session_call):
                    if should_fail:
                        with self.assertRaisesRegex(RuntimeError, "synthetic action failure"):
                            registration.admin_action({"AUTHWEAVE_ZITADEL_ADMIN_PASSWORD": "Aa1!" + "q" * 32}, action)
                    else:
                        registration.admin_action({"AUTHWEAVE_ZITADEL_ADMIN_PASSWORD": "Aa1!" + "q" * 32}, action)
                self.assertEqual([method for method, *_ in calls], ["POST", "PATCH", "GET", "DELETE"])
                self.assertEqual(calls[-1][3], {"sessionToken": "b" * 32})


if __name__ == "__main__":
    unittest.main()
