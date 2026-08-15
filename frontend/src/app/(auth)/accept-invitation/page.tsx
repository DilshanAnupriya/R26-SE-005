"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { 
  Building2, 
  CheckCircle2, 
  AlertCircle, 
  Loader2, 
  ArrowRight, 
  Lock, 
  User, 
  Mail,
  ShieldCheck
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { AuthCard } from "@/features/auth/components/auth-card";
import { useSession } from "@/components/session-provider";
import { authApi } from "@/features/auth/hooks";
import { organizationApi, type InvitationDetailsResponse } from "@/features/organizations/api";

function AcceptInvitationContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const token = searchParams.get("token") || "";
  const { adopt } = useSession();

  const [loadingDetails, setLoadingDetails] = useState(true);
  const [invitationDetails, setInvitationDetails] = useState<InvitationDetailsResponse | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);

  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!token) {
      setVerifyError("No invitation token provided. Please check your invitation link.");
      setLoadingDetails(false);
      return;
    }

    organizationApi.verifyInvitation(token)
      .then((details) => {
        setInvitationDetails(details);
        setLoadingDetails(false);
      })
      .catch((err) => {
        setVerifyError(err instanceof Error ? err.message : "This invitation has expired or is invalid.");
        setLoadingDetails(false);
      });
  }, [token]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!invitationDetails) return;

    if (!fullName.trim()) {
      setSubmitError("Please enter your full name.");
      return;
    }
    if (password.length < 8) {
      setSubmitError("Password must be at least 8 characters.");
      return;
    }
    if (password !== confirmPassword) {
      setSubmitError("Passwords do not match.");
      return;
    }

    setSubmitting(true);
    setSubmitError(null);

    try {
      // 1. Accept the invitation
      await organizationApi.acceptInvitation({
        token,
        fullName: fullName.trim(),
        password,
      });

      // 2. Automatically sign the user in
      const session = await authApi.login({
        email: invitationDetails.email,
        password,
      });

      adopt(session);
      setSuccess(true);

      // 3. Forward to developer onboarding
      setTimeout(() => {
        const orgSlug = session.user.organizations?.[0]?.slug;
        router.push(orgSlug ? `/onboarding/developer?org=${orgSlug}` : `/onboarding/developer`);
      }, 1000);
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : "Failed to accept invitation.");
      setSubmitting(false);
    }
  };

  if (loadingDetails) {
    return (
      <AuthCard title="Verifying Invitation" description="Please wait while we validate your invite...">
        <div className="flex flex-col items-center justify-center py-8 space-y-3">
          <Loader2 className="w-6 h-6 text-primary animate-spin" />
          <p className="text-xs text-muted-foreground">Checking invitation credentials...</p>
        </div>
      </AuthCard>
    );
  }

  if (verifyError || !invitationDetails) {
    return (
      <AuthCard
        title="Invitation Unavailable"
        description={verifyError || "This invitation link is not valid or has expired."}
        footer={
          <Link href="/login" className="font-medium text-foreground underline-offset-4 hover:underline text-xs">
            Return to Sign in
          </Link>
        }
      >
        <div className="rounded-md border border-destructive/20 bg-destructive/10 p-4 text-xs text-destructive flex items-center space-x-2.5">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          <span>Please contact your administrator to request a new invitation.</span>
        </div>
      </AuthCard>
    );
  }

  if (success) {
    return (
      <AuthCard
        title={`Welcome to ${invitationDetails.organizationName}`}
        description="Your account is ready. Redirecting to developer onboarding..."
      >
        <div className="flex flex-col items-center justify-center py-6 space-y-3 text-center">
          <div className="w-12 h-12 rounded-full bg-emerald-500/10 text-emerald-500 flex items-center justify-center">
            <CheckCircle2 className="w-6 h-6" />
          </div>
          <p className="text-xs text-muted-foreground">Setting up your profile and workspace access...</p>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard
      title={`Join ${invitationDetails.organizationName}`}
      description={
        <span>
          Invited by <strong className="text-foreground">{invitationDetails.inviterName}</strong> as a{" "}
          <strong className="text-foreground">{invitationDetails.roleName}</strong>.
        </span>
      }
      footer={
        <span className="text-muted-foreground text-xs">
          Already have an account?{" "}
          <Link href="/login" className="font-medium text-foreground underline-offset-4 hover:underline">
            Sign in
          </Link>
        </span>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        {submitError && (
          <div className="rounded-md border border-destructive/20 bg-destructive/10 p-3 text-xs text-destructive flex items-center space-x-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span>{submitError}</span>
          </div>
        )}

        {/* Organization summary badge */}
        <div className="rounded-md border bg-muted/40 p-3 text-xs space-y-1">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <Building2 className="w-4 h-4 text-muted-foreground" />
              <span className="font-semibold text-foreground">{invitationDetails.organizationName}</span>
            </div>
            <Badge variant="secondary" className="text-[10px] font-mono">
              {invitationDetails.roleName}
            </Badge>
          </div>
          <p className="text-muted-foreground truncate">{invitationDetails.email}</p>
        </div>

        {/* Full Name */}
        <div className="space-y-1.5">
          <Label htmlFor="fullName" className="text-xs">Full Name</Label>
          <Input
            id="fullName"
            type="text"
            required
            placeholder="e.g. Alex Morgan"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
          />
        </div>

        {/* Password */}
        <div className="space-y-1.5">
          <Label htmlFor="password" className="text-xs">Set Password</Label>
          <Input
            id="password"
            type="password"
            required
            placeholder="At least 8 characters"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>

        {/* Confirm Password */}
        <div className="space-y-1.5">
          <Label htmlFor="confirmPassword" className="text-xs">Confirm Password</Label>
          <Input
            id="confirmPassword"
            type="password"
            required
            placeholder="Re-enter password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
        </div>

        <Button
          type="submit"
          disabled={submitting}
          className="w-full font-medium"
        >
          {submitting ? (
            <span className="flex items-center space-x-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              <span>Joining Organization...</span>
            </span>
          ) : (
            <span className="flex items-center space-x-1.5">
              <span>Accept & Continue</span>
              <ArrowRight className="w-4 h-4" />
            </span>
          )}
        </Button>
      </form>
    </AuthCard>
  );
}

export default function AcceptInvitationPage() {
  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-background">
      <Suspense fallback={<Loader2 className="w-8 h-8 text-primary animate-spin" />}>
        <AcceptInvitationContent />
      </Suspense>
    </div>
  );
}
