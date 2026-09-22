// Opaque values and absolute/idle lifetimes shared by the BFF session boundary.

import { createHash, randomBytes } from "node:crypto";

export const LOGIN_TRANSACTION_SECONDS = 10 * 60;
export const IDLE_SESSION_SECONDS = 30 * 60;
export const ABSOLUTE_SESSION_SECONDS = 8 * 60 * 60;

export function randomOpaqueValue(): string {
  return randomBytes(32).toString("base64url");
}

export function validOpaqueValue(value: unknown): value is string {
  return typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value);
}

export function opaqueHash(value: string): Buffer {
  if (!validOpaqueValue(value)) throw new Error("Invalid opaque value");
  return createHash("sha256").update(value, "utf8").digest();
}

export function sessionExpiry(createdAt: Date, lastSeenAt: Date): { idle: Date; absolute: Date } {
  const absolute = new Date(createdAt.getTime() + ABSOLUTE_SESSION_SECONDS * 1000);
  const idle = new Date(Math.min(lastSeenAt.getTime() + IDLE_SESSION_SECONDS * 1000, absolute.getTime()));
  return { idle, absolute };
}

export function sessionCookieName(secure: boolean): string {
  return secure ? "__Host-authweave-session" : "authweave-session-local";
}

export function loginCookieName(secure: boolean): string {
  return secure ? "__Host-authweave-login" : "authweave-login-local";
}

export function cookieOptions(secure: boolean, maxAge: number) {
  return { httpOnly: true, secure, sameSite: "lax" as const, path: "/", maxAge };
}
