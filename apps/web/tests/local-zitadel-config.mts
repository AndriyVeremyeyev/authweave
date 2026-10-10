import assert from "node:assert/strict";
import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { authConfiguration } from "../src/lib/auth/config.ts";

export const localIssuer = "http://localhost:8081";
const webKeys = ["AUTHWEAVE_OIDC_ISSUER", "AUTHWEAVE_OIDC_CLIENT_ID", "AUTHWEAVE_OIDC_PROJECT_ID", "AUTHWEAVE_OIDC_ORG_ID"];
const passwordKeys = ["AUTHWEAVE_SYNTHETIC_ALICE_PASSWORD", "AUTHWEAVE_SYNTHETIC_BOB_PASSWORD"];

export function localZitadelCuratorLogin(value: string | undefined): string | null {
  if (value === undefined || value === "") return null;
  assert.ok(["alice@authweave.localhost", "bob@authweave.localhost"].includes(value),
    "Choose only an explicit existing synthetic curator; no grant is created by this test");
  return value;
}

function values(text: string, keys: string[]) {
  assert.ok(Buffer.byteLength(text) <= 16_384, "Private local configuration is too large");
  const all = new Map<string, string>();
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("="), key = line.slice(0, separator);
    assert.ok(separator > 0 && /^[A-Z][A-Z0-9_]*$/.test(key) && !all.has(key), "Invalid or duplicate local configuration key");
    all.set(key, line.slice(separator + 1));
  }
  assert.ok(keys.every(key => all.has(key)), "Existing local registration is incomplete; run make auth-registration-check");
  return Object.fromEntries(keys.map(key => [key, all.get(key)!]));
}

export function localZitadelConfiguration(webText: string, passwordText: string) {
  const web = values(webText, webKeys), passwords = values(passwordText, passwordKeys);
  assert.equal(web.AUTHWEAVE_OIDC_ISSUER, localIssuer, "Only the existing loopback ZITADEL lab is permitted");
  const configuration = authConfiguration(web);
  assert.ok(configuration.curatorScope, "Existing project and organization scope must be configured");
  assert.ok(Object.values(passwords).every(value => /^[A-Za-z0-9_!\-]{24,128}$/.test(value)), "Invalid synthetic password format");
  assert.notEqual(passwords[passwordKeys[0]], passwords[passwordKeys[1]], "Synthetic accounts must have distinct passwords");
  return { web, users: [
    { login: "alice@authweave.localhost", name: "Alice Example", password: passwords[passwordKeys[0]] },
    { login: "bob@authweave.localhost", name: "Bob Example", password: passwords[passwordKeys[1]] },
  ] };
}

async function privateText(file: string) {
  // Read through a non-symlink descriptor; never execute/source local shell content.
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await handle.stat();
    assert.ok(info.isFile() && (info.mode & 0o777) === 0o600 && info.size <= 16_384,
      "Local credential/configuration files must be regular mode-600 files");
    return await handle.readFile("utf8");
  } finally { await handle.close(); }
}

export async function readLocalZitadelConfiguration(webDirectory: string) {
  return localZitadelConfiguration(await privateText(path.join(webDirectory, ".env.local")),
    await privateText(path.resolve(webDirectory, "../../infra/zitadel/synthetic-users.env.local")));
}
