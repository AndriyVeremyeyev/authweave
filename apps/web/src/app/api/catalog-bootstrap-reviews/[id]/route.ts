import type { NextRequest } from "next/server.js";
import { bootstrapRead } from "../../../../lib/catalog/bootstrap-http.ts";
import { bootstrapDependencies } from "../../../../lib/catalog/bootstrap-server.ts";

export const runtime = "nodejs";
export async function GET(request: NextRequest, context: RouteContext<"/api/catalog-bootstrap-reviews/[id]">): Promise<Response> {
  return bootstrapRead(request, (await context.params).id, bootstrapDependencies);
}
