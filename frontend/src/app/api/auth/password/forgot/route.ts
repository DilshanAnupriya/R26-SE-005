import { backendError, readJsonBody } from "@/server/auth-routes";
import { callBackend } from "@/server/backend";
import { NextResponse } from "next/server";

/**
 * Always 204, whatever the backend says about whether the address exists — the
 * backend deliberately does not distinguish, and neither may this route, or the
 * response time and status become an account-enumeration oracle.
 */
export async function POST(request: Request) {
  const result = await callBackend("/auth/password/forgot", {
    method: "POST",
    body: await readJsonBody(request),
  });
  // Only a genuine outage is worth surfacing; a 404 for an unknown email is not.
  if (!result.ok && result.status >= 500) {
    return backendError(result.status, result.error);
  }
  return new NextResponse(null, { status: 204 });
}
