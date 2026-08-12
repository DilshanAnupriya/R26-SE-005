import { Suspense } from "react";
import type { Metadata } from "next";
import { AuthCard } from "@/features/auth/components/auth-card";
import { ResetPasswordForm } from "@/features/auth/components/reset-password-form";
import { Skeleton } from "@/components/ui/skeleton";

export const metadata: Metadata = { title: "Choose a new password · SprintIQ" };

export default function ResetPasswordPage() {
  return (
    <AuthCard title="Choose a new password" description="Make it one you have not used before.">
      {/* Reads ?token= from the email link via useSearchParams. */}
      <Suspense fallback={<Skeleton className="h-48 w-full" />}>
        <ResetPasswordForm />
      </Suspense>
    </AuthCard>
  );
}
