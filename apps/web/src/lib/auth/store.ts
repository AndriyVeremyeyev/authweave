// The only runtime database identity here is authweave_web_runtime.
import { Pool } from "pg";

import type { CuratorScope } from "./curator.ts";

import {
  ABSOLUTE_SESSION_SECONDS, IDLE_SESSION_SECONDS, LOGIN_TRANSACTION_SECONDS,
  opaqueHash, randomOpaqueValue, validOpaqueValue,
} from "./session-policy.ts";

export type LoginTransaction = { codeVerifier: string; nonce: string };
export type BrowserSession = {
  workspaceId: string;
  subject: string;
  issuer: string;
  email: string | null;
  displayName: string | null;
  authenticatedAt: Date;
  // Login-time evidence only; never authorize a write from this field alone.
  curatorScope?: CuratorScope | null;
};

let connectionPool: Pool | undefined;

export function authDatabase(): Pool {
  if (connectionPool) return connectionPool;
  const password = process.env.AUTHWEAVE_WEB_DB_PASSWORD;
  const database = process.env.AUTHWEAVE_POSTGRES_DB;
  const port = Number(process.env.AUTHWEAVE_POSTGRES_PORT ?? "5432");
  if (!password || !database || !/^[a-z][a-z0-9_]{0,62}$/.test(database) ||
      !Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Web authentication database is not configured");
  }
  connectionPool = new Pool({
    host: "127.0.0.1", port, database, user: "authweave_web_runtime", password,
    max: 5, connectionTimeoutMillis: 3_000, idleTimeoutMillis: 30_000,
  });
  connectionPool.on("error", () => {
    // Do not log driver errors: they can include connection strings or credentials.
    console.error("Web authentication database connection was interrupted");
  });
  return connectionPool;
}

export async function beginLogin(state: string, binding: string, codeVerifier: string, nonce: string,
                                 pool: Pool = authDatabase()): Promise<void> {
  await pool.query("DELETE FROM web.oidc_login_transactions WHERE expires_at <= CURRENT_TIMESTAMP");
  await pool.query(
    `INSERT INTO web.oidc_login_transactions
       (state_hash, browser_binding_hash, code_verifier, nonce, expires_at)
     VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP + ($5 * INTERVAL '1 second'))`,
    [opaqueHash(state), opaqueHash(binding), codeVerifier, nonce, LOGIN_TRANSACTION_SECONDS],
  );
}

export async function consumeLogin(state: string, binding: string,
                                   pool: Pool = authDatabase()): Promise<LoginTransaction | null> {
  const result = await pool.query<{ code_verifier: string; nonce: string }>(
    `DELETE FROM web.oidc_login_transactions
     WHERE state_hash = $1 AND browser_binding_hash = $2 AND expires_at > CURRENT_TIMESTAMP
     RETURNING code_verifier, nonce`,
    [opaqueHash(state), opaqueHash(binding)],
  );
  const row = result.rows[0];
  return row ? { codeVerifier: row.code_verifier, nonce: row.nonce } : null;
}

export async function createSession(identity: BrowserSession, existingId: string | undefined,
                                    pool: Pool = authDatabase()): Promise<string> {
  const id = randomOpaqueValue();
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    if (validOpaqueValue(existingId)) {
      await client.query("DELETE FROM web.sessions WHERE session_hash = $1", [opaqueHash(existingId)]);
    }
    await client.query(
      `INSERT INTO web.sessions
         (session_hash, workspace_id, issuer, subject, email, display_name, authenticated_at,
          curator_project_id, curator_org_id, idle_expires_at, absolute_expires_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7,
               $8, $9, CURRENT_TIMESTAMP + ($10 * INTERVAL '1 second'),
               CURRENT_TIMESTAMP + ($11 * INTERVAL '1 second'))`,
      [opaqueHash(id), identity.workspaceId, identity.issuer, identity.subject, identity.email, identity.displayName,
        identity.authenticatedAt, identity.curatorScope?.projectId ?? null,
        identity.curatorScope?.organizationId ?? null, IDLE_SESSION_SECONDS, ABSOLUTE_SESSION_SECONDS],
    );
    await client.query("COMMIT");
    return id;
  } catch {
    await client.query("ROLLBACK");
    throw new Error("Could not establish web session");
  } finally {
    client.release();
  }
}

export async function touchSession(id: string | undefined,
                                   pool?: Pool): Promise<BrowserSession | null> {
  if (!validOpaqueValue(id)) return null;
  const result = await (pool ?? authDatabase()).query<{
    workspace_id: string; issuer: string; subject: string; email: string | null; display_name: string | null;
    authenticated_at: Date; curator_project_id: string | null; curator_org_id: string | null;
  }>(
    `UPDATE web.sessions
     SET last_seen_at = CURRENT_TIMESTAMP,
         idle_expires_at = LEAST(CURRENT_TIMESTAMP + ($2 * INTERVAL '1 second'), absolute_expires_at)
     WHERE session_hash = $1
       AND workspace_id IS NOT NULL
       AND idle_expires_at > CURRENT_TIMESTAMP AND absolute_expires_at > CURRENT_TIMESTAMP
     RETURNING workspace_id, issuer, subject, email, display_name, authenticated_at,
               curator_project_id, curator_org_id`,
    [opaqueHash(id), IDLE_SESSION_SECONDS],
  );
  const row = result.rows[0];
  return row ? {
    workspaceId: row.workspace_id, issuer: row.issuer, subject: row.subject, email: row.email,
    displayName: row.display_name, authenticatedAt: row.authenticated_at,
    curatorScope: row.curator_project_id && row.curator_org_id ? {
      projectId: row.curator_project_id, organizationId: row.curator_org_id,
    } : null,
  } : null;
}

export async function revokeSession(id: string | undefined, pool?: Pool): Promise<void> {
  if (!validOpaqueValue(id)) return;
  await (pool ?? authDatabase()).query("DELETE FROM web.sessions WHERE session_hash = $1", [opaqueHash(id)]);
}
