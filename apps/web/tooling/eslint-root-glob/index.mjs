import { globSync as directories } from "tinyglobby";
import { isAbsolute } from "node:path";

// Only @next/eslint-plugin-next's getRootDirs call is supported, not fast-glob's full API.
export function globSync(pattern, options) {
  if (typeof pattern !== "string" || !options || options.onlyDirectories !== true ||
      Object.keys(options).length !== 1) {
    throw new TypeError("Unsupported Next.js ESLint directory-glob call; review the adapter before upgrading.");
  }
  const matches = directories(pattern, { onlyDirectories: true, expandDirectories: false, absolute: isAbsolute(pattern) });
  // tinyglobby marks every directory with '/'; fast-glob preserves it only in trailing-slash patterns.
  return pattern.endsWith("/") ? matches : matches.map(path => path.length > 1 ? path.replace(/\/$/, "") : path);
}
