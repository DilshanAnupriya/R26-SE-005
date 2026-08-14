"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { 
  Building2, 
  CheckCircle2, 
  AlertCircle, 
  Loader2, 
  ArrowRight, 
  Lock, 
  User, 
  Mail, 
  Sparkles,
  ShieldCheck
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
      setVerifyError("No invitation token provided. Please check your invitation email link.");
      setLoadingDetails(false);
      return;
    }

    organizationApi.verifyInvitation(token)
      .then((details) => {
        setInvitationDetails(details);
        setLoadingDetails(false);
      })
      .catch((err) => {
        setVerifyError(err instanceof Error ? err.message : "Invalid or expired invitation link.");
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

      // 2. Automatically log the user in
      const session = await authApi.login({
        email: invitationDetails.email,
        password,
      });

      adopt(session);
      setSuccess(true);

      // 3. If developer/member role, route to technical profile onboarding
      setTimeout(() => {
        const orgSlug = session.user.organizations?.[0]?.slug;
        router.push(orgSlug ? `/onboarding/developer?org=${orgSlug}` : `/onboarding/developer`);
      }, 1500);
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : "Failed to accept invitation.");
      setSubmitting(false);
    }
  };

  if (loadingDetails) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[400px] space-y-4">
        <Loader2 className="w-8 h-8 text-primary animate-spin" />
        <p className="text-sm text-slate-400">Verifying invitation details...</p>
      </div>
    );
  }

  if (verifyError || !invitationDetails) {
    return (
      <div className="p-8 text-center space-y-6 max-w-md mx-auto bg-slate-900/60 border border-slate-800 rounded-3xl">
        <div className="w-14 h-14 rounded-2xl bg-destructive/10 border border-destructive/20 text-destructive flex items-center justify-center mx-auto">
          <AlertCircle className="w-7 h-7" />
        </div>
        <div>
          <h2 className="text-xl font-bold text-white mb-2">Invitation Unavailable</h2>
          <p className="text-sm text-slate-400">{verifyError}</p>
        </div>
        <Button asChild className="w-full">
          <Link href="/login">Return to Sign In</Link>
        </Button>
      </div>
    );
  }

  return (
    <div className="w-full max-w-lg mx-auto">
      <motion.div
        initial={{ opacity: 0, y: 15 }}
        animate={{ opacity: 1, y: 0 }}
        className="rounded-3xl border border-slate-800 bg-slate-950/70 backdrop-blur-xl p-8 shadow-2xl space-y-6"
      >
        {/* Header Branding */}
        <div className="text-center space-y-3">
          <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-primary/10 border border-primary/20 text-primary text-xs font-semibold">
            <Sparkles className="w-3.5 h-3.5" />
            <span>Organization Invitation</span>
          </div>

          <h1 className="text-2xl font-bold text-white">
            Join <span className="text-gradient">{invitationDetails.organizationName}</span>
          </h1>

          <p className="text-sm text-slate-400">
            {invitationDetails.inviterName} has invited you to join the team as a{" "}
            <span className="font-semibold text-slate-200">{invitationDetails.roleName}</span>.
          </p>
        </div>

        {/* Organization Card Banner */}
        <div className="p-4 rounded-2xl bg-slate-900/50 border border-slate-800/80 flex items-center space-x-4">
          <div className="w-11 h-11 rounded-xl bg-primary/15 border border-primary/25 flex items-center justify-center text-primary">
            <Building2 className="w-5 h-5" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-white truncate">{invitationDetails.organizationName}</p>
            <p className="text-xs text-slate-400 truncate">{invitationDetails.email}</p>
          </div>
          <div className="px-2.5 py-1 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 text-xs font-medium">
            {invitationDetails.roleName}
          </div>
        </div>

        <AnimatePresence mode="wait">
          {success ? (
            <motion.div
              key="success"
              initial={{ opacity: 0, scale: 0.95 }}
              animate={{ opacity: 1, scale: 1 }}
              className="py-8 text-center space-y-4"
            >
              <div className="w-16 h-16 rounded-full bg-emerald-500/20 text-emerald-500 flex items-center justify-center mx-auto">
                <CheckCircle2 className="w-8 h-8" />
              </div>
              <h3 className="text-xl font-bold text-white">Welcome to {invitationDetails.organizationName}!</h3>
              <p className="text-sm text-slate-400">Setting up your workspace and developer profile...</p>
            </motion.div>
          ) : (
            <motion.form key="form" onSubmit={handleSubmit} className="space-y-4">
              {submitError && (
                <div className="p-3.5 rounded-xl bg-destructive/10 border border-destructive/20 text-destructive text-sm flex items-center space-x-2.5">
                  <AlertCircle className="w-4 h-4 flex-shrink-0" />
                  <span>{submitError}</span>
                </div>
              )}

              {/* Email (Readonly) */}
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-400">Email Address</Label>
                <div className="relative">
                  <Mail className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                  <Input
                    type="email"
                    disabled
                    value={invitationDetails.email}
                    className="pl-10 bg-slate-900/40 text-slate-400 border-slate-800/80 cursor-not-allowed"
                  />
                </div>
              </div>

              {/* Full Name */}
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Full Name</Label>
                <div className="relative">
                  <User className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                  <Input
                    type="text"
                    required
                    placeholder="e.g. Alex Morgan"
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    className="pl-10 bg-slate-900/80 text-white border-slate-800 focus:border-primary"
                  />
                </div>
              </div>

              {/* Password */}
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Set Password</Label>
                <div className="relative">
                  <Lock className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                  <Input
                    type="password"
                    required
                    placeholder="Minimum 8 characters"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="pl-10 bg-slate-900/80 text-white border-slate-800 focus:border-primary"
                  />
                </div>
              </div>

              {/* Confirm Password */}
              <div className="space-y-1.5">
                <Label className="text-xs text-slate-300">Confirm Password</Label>
                <div className="relative">
                  <Lock className="w-4 h-4 text-slate-500 absolute left-3.5 top-3" />
                  <Input
                    type="password"
                    required
                    placeholder="Re-enter password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="pl-10 bg-slate-900/80 text-white border-slate-800 focus:border-primary"
                  />
                </div>
              </div>

              <Button
                type="submit"
                disabled={submitting}
                className="w-full py-5 font-semibold text-base shadow-lg shadow-primary/20 flex items-center justify-center space-x-2 mt-2"
              >
                {submitting ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>Joining {invitationDetails.organizationName}...</span>
                  </>
                ) : (
                  <>
                    <span>Accept Invitation & Join</span>
                    <ArrowRight className="w-4 h-4" />
                  </>
                )}
              </Button>

              <div className="flex items-center justify-center space-x-2 text-xs text-slate-500 pt-2">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-500" />
                <span>Your account is isolated to {invitationDetails.organizationName}</span>
              </div>
            </motion.form>
          )}
        </AnimatePresence>
      </motion.div>
    </div>
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
