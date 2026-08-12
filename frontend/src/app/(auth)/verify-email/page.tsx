import { Suspense } from "react";
import type { Metadata } from "next";
import { AuthCard } from "@/features/auth/components/auth-card";
import { VerifyEmailView } from "@/features/auth/components/verify-email-view";
import { Skeleton } from "@/components/ui/skeleton";

export const metadata: Metadata = { title: "Verify your email · SprintIQ" };

export default function VerifyEmailPage() {
  return (
    <AuthCard title="Email verification">
      <Suspense fallback={<Skeleton className="h-24 w-full" />}>
        <VerifyEmailView />
      </Suspense>
    </AuthCard>
  );
}
