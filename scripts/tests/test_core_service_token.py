"""Offline tests for the explicit local BFF-to-Core credential setup."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "create_local_env", Path(__file__).resolve().parents[1] / "create_local_env.py"
)
environment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(environment)


class CoreServiceTokenTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="authweave-core-token-test-")
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / ".env"
        self.path.write_text("AUTHWEAVE_POSTGRES_DB=authweave\n", encoding="utf-8")
        self.path.chmod(0o600)

    def test_adds_token_once_without_replacing_existing_values(self):
        with patch.object(environment, "TARGET_PATH", self.path), \
                patch.object(environment.secrets, "token_urlsafe", return_value="synthetic-service-token-12345678901234567890"):
            environment.add_core_service_token()
            first = self.path.read_text(encoding="utf-8")
            environment.add_core_service_token()
        self.assertEqual(first, self.path.read_text(encoding="utf-8"))
        self.assertIn("AUTHWEAVE_POSTGRES_DB=authweave\n", first)
        self.assertEqual(first.count("AUTHWEAVE_CORE_SERVICE_TOKEN="), 1)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_rejects_permissive_file_and_symlink(self):
        self.path.chmod(0o644)
        with patch.object(environment, "TARGET_PATH", self.path):
            with self.assertRaisesRegex(ValueError, "mode 600"):
                environment.add_core_service_token()
        self.path.unlink()
        self.path.symlink_to(environment.TEMPLATE_PATH)
        with patch.object(environment, "TARGET_PATH", self.path):
            with self.assertRaisesRegex(ValueError, "symlink"):
                environment.add_core_service_token()

    def test_new_environment_generates_separate_service_token(self):
        generated = environment.generated_environment()
        self.assertIn("AUTHWEAVE_CORE_SERVICE_TOKEN=", generated)
        self.assertNotIn("choose-a-separate-local-only-service-token", generated)


if __name__ == "__main__":
    unittest.main()
