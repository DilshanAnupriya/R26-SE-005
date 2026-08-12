import { backendError, readJsonBody } from "@/server/auth-routes";
import { callBackend } from "@/server/backend";
import { NextResponse } from "next/server";

/**
 * Consumes the single-use reset token. No session is issued: the user signs in
 * afterwards with the new password, which proves the reset actually worked and
 * leaves one obvious place where a session begins.
 */
export async function POST(request: Request) {
  const result = await callBackend("/auth/password/reset", {
    method: "POST",
    body: await readJsonBody(request),
  });
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : backendError(result.status, result.error);
}
