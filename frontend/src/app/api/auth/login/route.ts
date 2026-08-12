import { exchangeForSession, readJsonBody } from "@/server/auth-routes";

/** Signs in and puts the refresh token in the httpOnly cookie. */
export async function POST(request: Request) {
  return exchangeForSession("/auth/login", await readJsonBody(request));
}
