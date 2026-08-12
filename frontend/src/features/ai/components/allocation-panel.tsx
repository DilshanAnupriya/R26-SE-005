"use client";

import { AlertCircle, UserPlus, Users } from "lucide-react";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { AllocationCandidate, AllocationRanking } from "@/lib/api/ai-schemas";
import { useRankCandidates } from "../hooks";
import { cn } from "@/lib/utils";

function initials(name?: string | null) {
  if (!name) return "?";
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

/**
 * Model 3's assignment assistant.
 *
 * <p>Suggestions only — pressing a candidate calls the caller's own assignment action.
 * Nothing here assigns anything by itself, because a tool that hands out work
 * automatically is one that gets switched off the first time it hands the wrong work to
 * the wrong person.</p>
 *
 * <p>Workload is shown as its own signal beside the fit score, never blended into it.
 * The best person for a task is often the person already drowning, and a single number
 * would quietly recommend burning them out.</p>
 */
export function AllocationPanel({
  entityType,
  entityId,
  currentAssigneeId,
  onAssign,
  assigning,
}: {
  entityType: "TASK" | "STORY";
  entityId: string;
  currentAssigneeId?: string | null;
  onAssign?: (userId: string, predictionId?: string | null) => void;
  assigning?: boolean;
}) {
  const rank = useRankCandidates();
  const ranking: AllocationRanking | undefined = rank.data;

  return (
    <section className="rounded-lg border p-3" aria-labelledby={`alloc-${entityId}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id={`alloc-${entityId}`} className="flex items-center gap-1.5 text-sm font-medium">
            <Users className="size-4 text-primary" aria-hidden />
            Who should take this
          </h3>
          <p className="text-[11px] text-muted-foreground">
            Ranked on skills, delivery consistency and current load. Nothing is assigned
            until you choose.
          </p>
        </div>
        <Button
          size="sm"
          variant="secondary"
          disabled={rank.isPending}
          onClick={() => rank.mutate({ entityType, entityId, limit: 5 })}
        >
          {rank.isPending ? "Ranking…" : ranking ? "Re-rank" : "Suggest"}
        </Button>
      </div>

      {rank.isPending ? <Skeleton className="mt-3 h-24 w-full" /> : null}

      {ranking && ranking.requiredSkills.length > 0 ? (
        <div className="mt-3 flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-muted-foreground">Matched against:</span>
          {ranking.requiredSkills.map((skill) => (
            <Badge key={skill} variant="outline" className="text-[11px]">
              {skill}
            </Badge>
          ))}
        </div>
      ) : null}

      {ranking ? (
        <ol className="mt-3 space-y-2">
          {ranking.candidates.map((candidate) => (
            <CandidateRow
              key={candidate.userId}
              candidate={candidate}
              current={candidate.userId === currentAssigneeId}
              disabled={assigning}
              onAssign={() => onAssign?.(candidate.userId, ranking.predictionId)}
            />
          ))}
        </ol>
      ) : null}
    </section>
  );
}

function CandidateRow({
  candidate,
  current,
  disabled,
  onAssign,
}: {
  candidate: AllocationCandidate;
  current: boolean;
  disabled?: boolean;
  onAssign: () => void;
}) {
  const fit = Math.round(candidate.fitScore * 100);
  const load = candidate.currentLoadPct == null ? null : Math.round(candidate.currentLoadPct);

  return (
    <li
      className={cn(
        "flex items-start gap-3 rounded-md border p-2",
        candidate.overloadRisk && "border-amber-500/40",
      )}
    >
      <span className="text-xs font-medium tabular-nums text-muted-foreground">
        {candidate.rankPosition}
      </span>

      <Avatar className="size-7 shrink-0">
        <AvatarImage src={candidate.avatarUrl ?? undefined} alt="" />
        <AvatarFallback className="text-[11px]">{initials(candidate.fullName)}</AvatarFallback>
      </Avatar>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="truncate text-sm font-medium">{candidate.fullName ?? "Unknown"}</span>
          <Badge variant="secondary" className="tabular-nums">
            {fit}% fit
          </Badge>
          {current ? <Badge variant="outline">Current</Badge> : null}
          {candidate.overloadRisk ? (
            <Badge className="gap-1 border-0 bg-amber-500/10 text-amber-700 dark:text-amber-400">
              <AlertCircle className="size-3" aria-hidden />
              Already at {load}%
            </Badge>
          ) : load != null ? (
            <span className="text-[11px] text-muted-foreground tabular-nums">{load}% loaded</span>
          ) : null}
        </div>

        {candidate.explanation ? (
          <p className="mt-0.5 text-xs text-muted-foreground">{candidate.explanation}</p>
        ) : null}
      </div>

      {!current ? (
        <Button size="sm" variant="ghost" disabled={disabled} onClick={onAssign}>
          <UserPlus className="size-3.5" aria-hidden />
          <span className="sr-only">Assign to {candidate.fullName ?? "this person"}</span>
          Assign
        </Button>
      ) : null}
    </li>
  );
}
