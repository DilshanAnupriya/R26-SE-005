"use client";

import { useState } from "react";
import { Loader2, Plus, X } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useCreateGoal } from "../hooks";
import type { GoalLevel, MetricType } from "../api";

interface DraftKeyResult {
  key: number;
  title: string;
  metricType: MetricType;
  unit: string;
  startValue: string;
  targetValue: string;
}

let nextKey = 0;
function blankKeyResult(): DraftKeyResult {
  return {
    key: nextKey++,
    title: "",
    metricType: "NUMBER",
    unit: "",
    startValue: "0",
    targetValue: "",
  };
}

/** The quarter the given date falls in — the period most teams are about to type. */
function currentPeriod(): string {
  const now = new Date();
  return `Q${Math.floor(now.getMonth() / 3) + 1} ${now.getFullYear()}`;
}

function quarterBounds(): { start: string; end: string } {
  const now = new Date();
  const quarter = Math.floor(now.getMonth() / 3);
  const start = new Date(now.getFullYear(), quarter * 3, 1);
  const end = new Date(now.getFullYear(), quarter * 3 + 3, 0);
  return { start: iso(start), end: iso(end) };
}

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

/**
 * Creating an objective and its key results in one pass.
 *
 * <p>Key results are part of this form rather than a second step. An objective saved
 * alone has no progress and no status, and the tools that let you do it end up full
 * of empty aspirations nobody can review.</p>
 */
export function CreateGoalDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const bounds = quarterBounds();
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [period, setPeriod] = useState(currentPeriod());
  const [level, setLevel] = useState<GoalLevel>("TEAM");
  const [startDate, setStartDate] = useState(bounds.start);
  const [targetDate, setTargetDate] = useState(bounds.end);
  const [keyResults, setKeyResults] = useState<DraftKeyResult[]>([blankKeyResult()]);

  const create = useCreateGoal();

  const usable = keyResults.filter(
    (row) => row.title.trim() !== "" && Number.isFinite(Number(row.targetValue)) && row.targetValue.trim() !== "",
  );
  const valid = title.trim() !== "" && usable.length > 0;

  function reset() {
    const fresh = quarterBounds();
    setTitle("");
    setDescription("");
    setPeriod(currentPeriod());
    setLevel("TEAM");
    setStartDate(fresh.start);
    setTargetDate(fresh.end);
    setKeyResults([blankKeyResult()]);
  }

  function update(key: number, patch: Partial<DraftKeyResult>) {
    setKeyResults((rows) => rows.map((row) => (row.key === key ? { ...row, ...patch } : row)));
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;

    create.mutate(
      {
        title: title.trim(),
        description: description.trim() || undefined,
        period: period.trim() || undefined,
        level,
        startDate: startDate || undefined,
        targetDate: targetDate || undefined,
        keyResults: usable.map((row) => ({
          title: row.title.trim(),
          metricType: row.metricType,
          unit: row.unit.trim() || undefined,
          startValue: Number(row.startValue) || 0,
          targetValue: Number(row.targetValue),
        })),
      },
      {
        onSuccess: () => {
          reset();
          onOpenChange(false);
        },
      },
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[88vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>New goal</DialogTitle>
          <DialogDescription>
            State the objective as an outcome, then give it the numbers that would prove it.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="goal-title">Objective</Label>
            <Input
              id="goal-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="Make onboarding something new customers finish"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="goal-description">Why it matters</Label>
            <Textarea
              id="goal-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={2}
              placeholder="Optional. The context someone reading this in three months will not have."
            />
          </div>

          <div className="grid gap-3 sm:grid-cols-4">
            <div className="space-y-1.5">
              <Label htmlFor="goal-period">Cycle</Label>
              <Input
                id="goal-period"
                value={period}
                onChange={(event) => setPeriod(event.target.value)}
                placeholder="Q1 2026"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="goal-level">Level</Label>
              <Select value={level} onValueChange={(next) => setLevel(next as GoalLevel)}>
                <SelectTrigger id="goal-level" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ORGANIZATION">Organisation</SelectItem>
                  <SelectItem value="WORKSPACE">Workspace</SelectItem>
                  <SelectItem value="TEAM">Team</SelectItem>
                  <SelectItem value="INDIVIDUAL">Individual</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="goal-start">Starts</Label>
              <Input
                id="goal-start"
                type="date"
                value={startDate}
                onChange={(event) => setStartDate(event.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="goal-target">Due</Label>
              <Input
                id="goal-target"
                type="date"
                value={targetDate}
                onChange={(event) => setTargetDate(event.target.value)}
              />
            </div>
          </div>
          {/* The dates are not decoration: status is paced against them, so an
              objective with no end date can only ever be judged on movement. */}
          <p className="-mt-1 text-2xs text-ink-3">
            Progress is judged against elapsed time between these dates.
          </p>

          <div className="space-y-2 rounded-lg border border-line bg-surface-2 p-3">
            <div className="flex items-center justify-between">
              <Label>Key results</Label>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => setKeyResults((rows) => [...rows, blankKeyResult()])}
              >
                <Plus className="size-3.5" aria-hidden />
                Add
              </Button>
            </div>

            {keyResults.map((row, index) => (
              <div key={row.key} className="grid grid-cols-[1fr_auto] gap-2">
                <div className="space-y-2">
                  <Input
                    value={row.title}
                    onChange={(event) => update(row.key, { title: event.target.value })}
                    placeholder={
                      index === 0 ? "Activation rate within 7 days" : "Another measurable result"
                    }
                    aria-label={`Key result ${index + 1} title`}
                  />
                  <div className="grid grid-cols-4 gap-2">
                    <Select
                      value={row.metricType}
                      onValueChange={(next) => update(row.key, { metricType: next as MetricType })}
                    >
                      <SelectTrigger
                        className="w-full"
                        aria-label={`Key result ${index + 1} metric type`}
                      >
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="NUMBER">Number</SelectItem>
                        <SelectItem value="PERCENT">Percent</SelectItem>
                        <SelectItem value="CURRENCY">Currency</SelectItem>
                        <SelectItem value="BOOLEAN">Done / not</SelectItem>
                      </SelectContent>
                    </Select>
                    <Input
                      value={row.unit}
                      onChange={(event) => update(row.key, { unit: event.target.value })}
                      placeholder="Unit"
                      aria-label={`Key result ${index + 1} unit`}
                    />
                    <Input
                      inputMode="decimal"
                      value={row.startValue}
                      onChange={(event) => update(row.key, { startValue: event.target.value })}
                      placeholder="From"
                      aria-label={`Key result ${index + 1} start value`}
                      className="font-mono"
                    />
                    <Input
                      inputMode="decimal"
                      value={row.targetValue}
                      onChange={(event) => update(row.key, { targetValue: event.target.value })}
                      placeholder="To"
                      aria-label={`Key result ${index + 1} target value`}
                      className="font-mono"
                    />
                  </div>
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className="self-start text-ink-3"
                  disabled={keyResults.length === 1}
                  onClick={() =>
                    setKeyResults((rows) => rows.filter((entry) => entry.key !== row.key))
                  }
                  aria-label={`Remove key result ${index + 1}`}
                >
                  <X className="size-4" aria-hidden />
                </Button>
              </div>
            ))}

            <p className="text-2xs text-ink-3">
              A target below the starting value counts down — defects, latency, churn.
            </p>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid || create.isPending}>
              {create.isPending ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  Creating…
                </>
              ) : (
                "Create goal"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
