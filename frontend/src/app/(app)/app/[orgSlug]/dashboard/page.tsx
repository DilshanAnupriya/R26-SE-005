"use client";

import { Sparkles, TrendingUp, AlertTriangle, CircleDollarSign } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { useSession } from "@/components/session-provider";

/**
 * The dashboard skeleton — the Phase 1b exit criterion.
 *
 * <p>Deliberately not wired to data. The delivery endpoints exist, but this phase
 * builds the shell; filling these panels is the first feature slice of the next one.
 * The cards are laid out for what will occupy them so the transition is a data
 * change, not a redesign, and the placeholders say so plainly rather than showing
 * invented numbers that look like real ones.</p>
 */
export default function DashboardPage() {
  const { user } = useSession();
  const firstName = user?.fullName?.split(/\s+/)[0] ?? "there";

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Welcome back, {firstName}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {user?.activeOrganizationName
            ? `Here is what is happening in ${user.activeOrganizationName}.`
            : "Here is what is happening across your projects."}
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <MetricCard icon={TrendingUp} label="Velocity" hint="Last 6 sprints" />
        <MetricCard icon={Sparkles} label="AI estimates accepted" hint="This sprint" />
        <MetricCard icon={AlertTriangle} label="Sprint risk" hint="Over-budget probability" />
        <MetricCard icon={CircleDollarSign} label="Cost forecast" hint="Planning only" />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <PanelCard title="Active sprint" className="lg:col-span-2" lines={5} />
        <PanelCard title="My work" lines={4} />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <PanelCard title="Burndown" lines={4} />
        <PanelCard title="Recent activity" lines={4} />
      </div>
    </div>
  );
}

function MetricCard({
  icon: Icon,
  label,
  hint,
}: {
  icon: typeof TrendingUp;
  label: string;
  hint: string;
}) {
  return (
    <div className="rounded-lg border bg-card p-4">
      <div className="flex items-center gap-2 text-muted-foreground">
        <Icon className="size-3.5" aria-hidden />
        <span className="text-xs font-medium">{label}</span>
      </div>
      <Skeleton className="mt-3 h-7 w-20" />
      <p className="mt-2 text-2xs text-muted-foreground">{hint}</p>
    </div>
  );
}

function PanelCard({
  title,
  lines,
  className,
}: {
  title: string;
  lines: number;
  className?: string;
}) {
  return (
    <section className={`rounded-lg border bg-card p-4 ${className ?? ""}`}>
      <h2 className="text-sm font-medium">{title}</h2>
      <div className="mt-3 space-y-2">
        {Array.from({ length: lines }, (_, index) => (
          <Skeleton key={index} className="h-8 w-full" />
        ))}
      </div>
      <p className="mt-3 text-2xs text-muted-foreground">
        Connected in the next phase.
      </p>
    </section>
  );
}
