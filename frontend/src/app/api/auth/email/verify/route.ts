import { backendError, readJsonBody } from "@/server/auth-routes";
import { callBackend } from "@/server/backend";
import { NextResponse } from "next/server";

export async function POST(request: Request) {
  const result = await callBackend("/auth/email/verify", {
    method: "POST",
    body: await readJsonBody(request),
  });
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : backendError(result.status, result.error);
}
