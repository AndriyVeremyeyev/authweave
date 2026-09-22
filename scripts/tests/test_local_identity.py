from copy import deepcopy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import tomllib
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("local_identity", Path(__file__).resolve().parents[1] / "local_identity.py")
identity = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(identity)
NOW = datetime(2026, 9, 13, 12, 0, tzinfo=timezone.utc)


class EnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="authweave-identity-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_create_private_complete_environment_with_bounded_pat_expiry(self):
        self.assertTrue(identity.create_environment(self.directory, NOW))
        values = identity.read_environment(self.directory)
        self.assertEqual(set(values), set(identity.KEYS))
        self.assertEqual(values[identity.KEYS[3]], (NOW + timedelta(days=90)).strftime("%Y-%m-%dT%H:%M:%SZ"))
        self.assertEqual(stat.S_IMODE((self.directory / ".env").stat().st_mode), 0o600)

    def test_repeat_preserves_credentials_bytes_and_expiry(self):
        identity.create_environment(self.directory, NOW)
        before = (self.directory / ".env").read_bytes()
        self.assertFalse(identity.create_environment(self.directory, NOW + timedelta(days=100)))
        self.assertEqual((self.directory / ".env").read_bytes(), before)

    def test_independent_environments_have_independent_secrets(self):
        identity.create_environment(self.directory, NOW)
        other = self.directory / "other"
        other.mkdir()
        identity.create_environment(other, NOW)
        left, right = identity.read_environment(self.directory), identity.read_environment(other)
        self.assertTrue(all(left[key] != right[key] for key in identity.KEYS[:3]))

    def test_reject_existing_and_dangling_symlink_without_modification(self):
        for exists in (False, True):
            with self.subTest(exists=exists):
                other = self.directory / "other"
                if exists:
                    other.write_text("preserve")
                target = self.directory / ".env"
                target.symlink_to(other)
                try:
                    with self.assertRaises(ValueError):
                        identity.create_environment(self.directory, NOW)
                    with self.assertRaises(ValueError):
                        identity.read_environment(self.directory)
                    if exists:
                        self.assertEqual(other.read_text(), "preserve")
                    else:
                        self.assertFalse(other.exists())
                finally:
                    target.unlink()

    def test_exclusive_create_rejects_race_without_truncation(self):
        original_open = os.open
        def concurrent_writer(path, flags, mode):
            Path(path).write_text("other writer")
            return original_open(path, flags, mode)
        with patch.object(identity.os, "open", side_effect=concurrent_writer):
            with self.assertRaises(FileExistsError):
                identity.create_environment(self.directory, NOW)
        self.assertEqual((self.directory / ".env").read_text(), "other writer")

    def test_reject_missing_or_world_readable_environment(self):
        with self.assertRaises(ValueError):
            identity.read_environment(self.directory)
        identity.create_environment(self.directory, NOW)
        (self.directory / ".env").chmod(0o644)
        with self.assertRaises(ValueError):
            identity.read_environment(self.directory)

    def test_reject_placeholders_duplicates_unknown_keys_and_shell_syntax(self):
        identity.create_environment(self.directory, NOW)
        target = self.directory / ".env"
        baseline = target.read_text()
        for invalid in (
            baseline.replace(identity.KEYS[0], "UNKNOWN_KEY"),
            baseline + f"{identity.KEYS[0]}=duplicate\n",
            baseline.replace("Aa1!", "$(echo private-value)"),
            baseline.replace("2026-12-12T12:00:00Z", "not-a-date"),
            f"{identity.KEYS[0]}=generate-with-make-setup-auth\n",
        ):
            with self.subTest(invalid=invalid[:30]):
                target.write_text(invalid)
                with self.assertRaises(ValueError) as failure:
                    identity.read_environment(self.directory)
                self.assertNotIn("private-value", str(failure.exception))

    def test_compose_cannot_inherit_other_project_or_identity_values(self):
        values = {key: "test-only" for key in identity.KEYS}
        with patch.dict(os.environ, {"COMPOSE_FILE": "another-project.yaml", "COMPOSE_PROJECT_NAME": "other",
                                     identity.KEYS[0]: "ambient-secret"}), patch.object(identity.subprocess, "run") as run:
            identity.compose(["down"], values)
        args, kwargs = run.call_args
        self.assertEqual(args[0][-1], "down")
        self.assertNotIn("--volumes", args[0])
        self.assertIn("authweave-identity", args[0])
        self.assertNotIn("COMPOSE_FILE", kwargs["env"])
        self.assertEqual(kwargs["env"][identity.KEYS[0]], "test-only")


class ConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Compose parses public files with synthetic values only; no Docker daemon is used.
        values = dict(zip(identity.KEYS, ("0" * 32, "1" * 64, "Aa1!" + "2" * 32, "2099-01-01T00:00:00Z"), strict=True))
        cls.config = json.loads(identity.compose(["config", "--format", "json"], values, capture=True).stdout)
        cls.routes = tomllib.loads((identity.IDENTITY / "routes.toml").read_text())

    def test_current_compose_has_safe_isolated_boundary(self):
        identity.validate_config(self.config, self.routes)

    def test_reject_exposure_shared_storage_privilege_and_unpinned_images(self):
        mutations = (
            lambda c: c["services"]["proxy"]["ports"][0].update(host_ip="0.0.0.0"),
            lambda c: c["services"]["postgres"].update(ports=[{"target": 5432, "published": "5432"}]),
            lambda c: c["services"]["zitadel-api"].update(network_mode="host"),
            lambda c: c["services"]["proxy"].update(privileged=True),
            lambda c: c["services"]["proxy"].update(image="traefik:latest"),
            lambda c: c["services"]["proxy"].update(restart="always"),
            lambda c: c["services"]["proxy"]["volumes"][0].update(source="/var/run/docker.sock"),
            lambda c: c["volumes"]["identity-data"].update(name="authweave_postgres-data"),
            lambda c: c["volumes"]["bootstrap"].update(external=True),
            lambda c: c["networks"]["default"].update(name="authweave_default"),
            lambda c: c["services"]["zitadel-api"]["environment"].update(ZITADEL_EXTERNALDOMAIN="public.example"),
            lambda c: c["services"]["zitadel-api"]["environment"].update(ZITADEL_DATABASE_POSTGRES_DSN="postgresql://zitadel:wrong@postgres:5432/zitadel"),
            lambda c: c["services"]["zitadel-login"]["volumes"][0].update(read_only=False),
            lambda c: c["services"]["proxy"]["command"].append("--providers.docker=true"),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                config = deepcopy(self.config)
                mutate(config)
                with self.assertRaises(ValueError):
                    identity.validate_config(config, self.routes)

    def test_proxy_never_routes_to_application_or_drops_host_filter(self):
        for destination in ("http://host.docker.internal:8080", "http://core-api:8080"):
            routes = deepcopy(self.routes)
            routes["http"]["services"]["api"]["loadBalancer"]["servers"][0]["url"] = destination
            with self.assertRaises(ValueError):
                identity.validate_config(self.config, routes)
        routes = deepcopy(self.routes)
        routes["http"]["routers"]["api"]["rule"] = "PathPrefix(`/`)"
        with self.assertRaises(ValueError):
            identity.validate_config(self.config, routes)


class DiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.document = {
            "issuer": identity.ISSUER,
            "authorization_endpoint": f"{identity.ISSUER}/oauth/v2/authorize",
            "token_endpoint": f"{identity.ISSUER}/oauth/v2/token",
            "jwks_uri": f"{identity.ISSUER}/oauth/v2/keys",
            "end_session_endpoint": f"{identity.ISSUER}/oidc/v1/end_session",
            "code_challenge_methods_supported": ["S256"], "response_types_supported": ["code"],
        }

    def test_expected_standard_metadata(self):
        identity.validate_discovery(self.document)

    def test_reject_wrong_issuer_endpoints_missing_code_and_missing_pkce(self):
        for key, value in (
            ("issuer", "http://127.0.0.1:8081"), ("issuer", "https://localhost:8081"),
            ("token_endpoint", "http://remote.example/token"), ("jwks_uri", "http://localhost:8082/keys"),
            ("authorization_endpoint", "http://user:secret@localhost:8081/authorize"),
            ("end_session_endpoint", "http://localhost:8081/logout#unexpected"),
            ("code_challenge_methods_supported", ["plain"]), ("response_types_supported", ["id_token"]),
        ):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    identity.validate_discovery({**self.document, key: value})
        with self.assertRaises(ValueError):
            identity.validate_discovery({})

    def test_discovery_does_not_follow_redirects(self):
        self.assertIsNone(identity.NoRedirect().redirect_request(None, None, 302, "", {}, "http://remote.example"))


class PasswordCheckTests(unittest.TestCase):
    PASSWORD = "Aa1!" + "p" * 32
    PAT = "pat_" + "a" * 32
    FIRST_TOKEN = "b" * 16 + "." + "b" * 16
    SECOND_TOKEN = "c" * 16 + "." + "c" * 16
    SESSION_ID = "391919241646833667"

    def successful_call(self, calls):
        def call(_opener, method, path, authorization, payload=None):
            calls.append((method, path, authorization, payload))
            if method == "POST":
                return {"sessionId": self.SESSION_ID, "sessionToken": self.FIRST_TOKEN}
            if method == "PATCH":
                return {"sessionToken": self.SECOND_TOKEN}
            if method == "GET":
                return {"session": {"factors": {"user": {"loginName": "admin@authweave.localhost"},
                                                   "password": {"verifiedAt": "2026-09-22T12:00:00Z"}}}}
            if method == "DELETE":
                return {}
            self.fail(method)
        return call

    def test_password_factor_is_verified_and_temporary_session_deleted(self):
        calls = []
        identity.verify_password(self.PASSWORD, self.PAT, self.successful_call(calls))
        self.assertEqual([call[0] for call in calls], ["POST", "PATCH", "GET", "DELETE"])
        self.assertEqual(calls[1][3]["checks"]["password"]["password"], self.PASSWORD)
        self.assertNotIn(self.PASSWORD, calls[1][1])
        self.assertEqual(calls[-1][3], {"sessionToken": self.SECOND_TOKEN})

    def test_synthetic_user_login_is_checked_by_exact_login_name(self):
        calls = []
        login = "alice@authweave.localhost"
        success = self.successful_call(calls)
        def call(*arguments):
            if arguments[1] == "GET":
                calls.append(arguments[1:])
                return {"session": {"factors": {"user": {"loginName": login},
                                                  "password": {"verifiedAt": "2026-09-22T12:00:00Z"}}}}
            return success(*arguments)
        identity.verify_password(self.PASSWORD, self.PAT, call, login_name=login)
        self.assertEqual(calls[0][3]["checks"]["user"]["loginName"], login)
        self.assertEqual(calls[-1][0], "DELETE")

    def test_failed_password_check_still_deletes_created_session(self):
        calls = []
        success = self.successful_call(calls)
        def call(*arguments):
            if arguments[1] == "PATCH":
                calls.append(arguments[1:])
                raise RuntimeError("rejected")
            return success(*arguments)
        with self.assertRaisesRegex(RuntimeError, "rejected"):
            identity.verify_password(self.PASSWORD, self.PAT, call)
        self.assertEqual(calls[-1][0], "DELETE")
        self.assertEqual(calls[-1][3], {"sessionToken": self.FIRST_TOKEN})

    def test_missing_verified_factor_is_failure_and_session_is_deleted(self):
        calls = []
        success = self.successful_call(calls)
        def call(*arguments):
            if arguments[1] == "GET":
                calls.append(arguments[1:])
                return {"session": {"factors": {"user": {"loginName": "admin@authweave.localhost"}}}}
            return success(*arguments)
        with self.assertRaisesRegex(ValueError, "password factor was not verified"):
            identity.verify_password(self.PASSWORD, self.PAT, call)
        self.assertEqual(calls[-1][0], "DELETE")

    def test_invalid_updated_token_cleans_up_with_last_valid_token(self):
        calls = []
        success = self.successful_call(calls)
        def call(*arguments):
            if arguments[1] == "PATCH":
                calls.append(arguments[1:])
                return {"sessionToken": "invalid token"}
            return success(*arguments)
        with self.assertRaisesRegex(ValueError, "invalid updated token"):
            identity.verify_password(self.PASSWORD, self.PAT, call)
        self.assertEqual(calls[-1][0], "DELETE")
        self.assertEqual(calls[-1][3], {"sessionToken": self.FIRST_TOKEN})

    def test_cleanup_failure_never_reports_success(self):
        calls = []
        success = self.successful_call(calls)
        def call(*arguments):
            if arguments[1] == "DELETE":
                calls.append(arguments[1:])
                raise RuntimeError("private-token-must-not-leak")
            return success(*arguments)
        with self.assertRaisesRegex(RuntimeError, "cleanup was not confirmed") as failure:
            identity.verify_password(self.PASSWORD, self.PAT, call)
        self.assertNotIn("private-token", str(failure.exception))

    def test_login_client_pat_is_read_without_shell_or_output(self):
        completed = type("Result", (), {"stdout": self.PAT + "\n"})()
        with patch.object(identity, "compose", return_value=completed) as compose:
            self.assertEqual(identity.load_login_client_pat({"ignored": "values"}), self.PAT)
        self.assertEqual(compose.call_args.args[0],
                         ["exec", "-T", "zitadel-login", "cat", "/zitadel/bootstrap/login-client.pat"])
        self.assertTrue(compose.call_args.kwargs["capture"])


if __name__ == "__main__":
    unittest.main()
