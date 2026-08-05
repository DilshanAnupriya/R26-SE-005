import { backendError } from "@/server/auth-routes";
import { callBackend } from "@/server/backend";
import { NextResponse } from "next/server";

export async function POST() {
  const result = await callBackend("/auth/email/resend", { method: "POST" });
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : backendError(result.status, result.error);
}
