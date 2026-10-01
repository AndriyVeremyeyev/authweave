import type { NextRequest } from "next/server.js";
import { bootstrapMutation } from "../../../lib/catalog/bootstrap-http.ts";
import { bootstrapDependencies } from "../../../lib/catalog/bootstrap-server.ts";

export const runtime = "nodejs";
export function POST(request: NextRequest): Promise<Response> {
  return bootstrapMutation(request, "record", bootstrapDependencies);
}
