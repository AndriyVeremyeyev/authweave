import type { NextRequest } from "next/server.js";
import { authConfiguration } from "../auth/config.ts";
import { prepareCatalogBootstrapReview, recordCatalogBootstrapReview, readCatalogBootstrapReview } from "../auth/core-client.ts";
import { sessionCookieName } from "../auth/session-policy.ts";
import { touchSession } from "../auth/store.ts";
import type { BootstrapDependencies } from "./bootstrap-http.ts";

export const bootstrapDependencies: BootstrapDependencies = {
  configuration: authConfiguration,
  session: async (request, config) => {
    const cookies = (request as NextRequest).cookies.getAll(sessionCookieName(config.secureCookies));
    return cookies.length === 1 ? touchSession(cookies[0].value) : null;
  },
  prepare: prepareCatalogBootstrapReview,
  record: recordCatalogBootstrapReview,
  read: readCatalogBootstrapReview,
};
