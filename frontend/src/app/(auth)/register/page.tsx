import type { Metadata } from "next";
import Link from "next/link";
import { AuthCard } from "@/features/auth/components/auth-card";
import { RegisterForm } from "@/features/auth/components/register-form";

export const metadata: Metadata = { title: "Create your workspace · SprintIQ" };

export default function RegisterPage() {
  return (
    <AuthCard
      title="Create your workspace"
      description="Free to start. No card required."
      footer={
        <span className="text-muted-foreground">
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-foreground underline-offset-4 hover:underline">
            Sign in
          </Link>
        </span>
      }
    >
      <RegisterForm />
    </AuthCard>
  );
}
