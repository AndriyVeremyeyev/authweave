import assert from "node:assert/strict";
import { test } from "node:test";
import { chmod, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { localZitadelConfiguration, localZitadelCuratorLogin, readLocalZitadelConfiguration } from "./local-zitadel-config.mts";

const web = "AUTHWEAVE_OIDC_ISSUER=http://localhost:8081\nAUTHWEAVE_OIDC_CLIENT_ID=synthetic-local-client\nAUTHWEAVE_OIDC_PROJECT_ID=123\nAUTHWEAVE_OIDC_ORG_ID=456\n";
const passwords = "AUTHWEAVE_SYNTHETIC_ALICE_PASSWORD=Aa1!synthetic-alice-password\nAUTHWEAVE_SYNTHETIC_BOB_PASSWORD=Aa1!synthetic-bob-password\n";

test("local checkpoint has no default curator and accepts only an explicit existing synthetic login", () => {
  assert.equal(localZitadelCuratorLogin(undefined), null);
  assert.equal(localZitadelCuratorLogin(""), null);
  for (const login of ["alice@authweave.localhost", "bob@authweave.localhost"])
    assert.equal(localZitadelCuratorLogin(login), login);
});
for (const login of ["admin@authweave.localhost", "owner@example.com", "Alice@authweave.localhost", " alice@authweave.localhost"])
  test(`local checkpoint rejects unexpected curator target ${login}`, () => {
    assert.throws(() => localZitadelCuratorLogin(login));
  });

test("local browser config keeps real registration scope and only fixed synthetic users", () => {
  const configuration = localZitadelConfiguration(web + "AUTHWEAVE_UNRELATED_SECRET=do-not-copy\n", passwords);
  assert.equal(configuration.web.AUTHWEAVE_OIDC_PROJECT_ID, "123");
  assert.equal(configuration.web.AUTHWEAVE_OIDC_ORG_ID, "456");
  assert.equal(Object.keys(configuration.web).length, 4);
  assert.deepEqual(configuration.users.map(user => user.login), ["alice@authweave.localhost", "bob@authweave.localhost"]);
});

for (const issuer of ["https://identity.example.test", "http://127.0.0.1:8081", "http://localhost:8081/", "http://localhost:8082"])
  test(`real local checkpoint rejects non-exact issuer ${issuer}`, () => {
    assert.throws(() => localZitadelConfiguration(web.replace("http://localhost:8081", issuer), passwords));
  });

for (const [name, invalid] of [
  ["missing organization", web.replace("AUTHWEAVE_OIDC_ORG_ID=456\n", "")],
  ["malformed project", web.replace("PROJECT_ID=123", "PROJECT_ID=not-an-id")],
  ["duplicate registration", web + "AUTHWEAVE_OIDC_CLIENT_ID=duplicate\n"],
  ["shell expansion", web + "$(echo unsafe)\n"],
  ["oversize configuration", web + "#".repeat(16_384)],
]) test(`real local checkpoint rejects ${name}`, () => {
  assert.throws(() => localZitadelConfiguration(invalid, passwords));
});

for (const [name, invalid] of [
  ["missing password", passwords.split("\n")[0]],
  ["duplicate password key", passwords + passwords.split("\n")[0]],
  ["short password", passwords.replace("Aa1!synthetic-alice-password", "short")],
  ["identical passwords", passwords.replace("Aa1!synthetic-bob-password", "Aa1!synthetic-alice-password")],
  ["quoted shell value", passwords.replace("Aa1!synthetic-alice-password", '"Aa1!synthetic-alice-password"')],
]) test(`real local checkpoint rejects ${name}`, () => {
  assert.throws(() => localZitadelConfiguration(web, invalid));
});

test("private local files are read-only, mode-600 and non-symlink", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "authweave-zitadel-config-test-"));
  const directory = path.join(root, "apps/web"), credentials = path.join(root, "infra/zitadel/synthetic-users.env.local");
  const configuration = path.join(directory, ".env.local");
  try {
    await mkdir(directory, { recursive: true }); await mkdir(path.dirname(credentials), { recursive: true });
    await writeFile(configuration, web, { mode: 0o600 }); await writeFile(credentials, passwords, { mode: 0o600 });
    assert.equal((await readLocalZitadelConfiguration(directory)).users.length, 2);
    assert.equal(await readFile(configuration, "utf8"), web); assert.equal(await readFile(credentials, "utf8"), passwords);
    await chmod(credentials, 0o644); await assert.rejects(readLocalZitadelConfiguration(directory));
    await chmod(credentials, 0o600); await chmod(configuration, 0o644);
    await assert.rejects(readLocalZitadelConfiguration(directory)); await chmod(configuration, 0o600);
    const target = path.join(root, "credentials"); await writeFile(target, passwords, { mode: 0o600 });
    await rm(credentials); await symlink(target, credentials); await assert.rejects(readLocalZitadelConfiguration(directory));
    assert.equal(await readFile(target, "utf8"), passwords);
  } finally { await rm(root, { recursive: true, force: true }); }
});
