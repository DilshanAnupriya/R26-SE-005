"use client";

import { Activity, CircleHelp, ClipboardList, LineChart, Sparkles } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

type ForecastStage = "initial" | "mid-sprint";

type ForecastMethodDialogProps = {
  stage: ForecastStage;
};

const INITIAL_INPUTS = [
  "Planned task effort, story points, and task count",
  "Team size, developer rates, availability, and planned absences",
  "Historical velocity and sprint sequence",
  "Current assignments, skill coverage, and team seniority",
];

const MID_SPRINT_INPUTS = [
  "All planning and team information used by the initial forecast",
  "Completed story points, remaining work, and elapsed sprint time",
  "Effort and labour cost already logged",
  "Overtime, live availability, and current workload pressure",
];

function InputList({ items }: { items: string[] }) {
  return (
    <ul className="grid gap-x-6 gap-y-2 text-sm text-muted-foreground sm:grid-cols-2">
      {items.map((item) => (
        <li key={item} className="flex gap-2">
          <span className="mt-2 size-1.5 shrink-0 rounded-full bg-primary" aria-hidden />
          <span>{item}</span>
        </li>
      ))}
    </ul>
  );
}

function InitialForecastGuide() {
  return (
    <div className="space-y-5 pt-4">
      <section className="space-y-2" aria-labelledby="initial-purpose">
        <div className="flex items-center gap-2">
          <LineChart className="size-4 text-primary" aria-hidden />
          <h3 id="initial-purpose" className="font-semibold">What it does</h3>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">
          Creates a planning-time estimate of the sprint&apos;s final labour cost before live
          execution data is available.
        </p>
      </section>

      <section className="space-y-3 border-t pt-4" aria-labelledby="initial-inputs">
        <div className="flex items-center gap-2">
          <ClipboardList className="size-4 text-primary" aria-hidden />
          <h3 id="initial-inputs" className="font-semibold">Information considered</h3>
        </div>
        <InputList items={INITIAL_INPUTS} />
      </section>

      <section className="space-y-2 border-t pt-4" aria-labelledby="initial-model">
        <div className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" aria-hidden />
          <h3 id="initial-model" className="font-semibold">Model and result</h3>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">
          SprintIQ uses a validated planning baseline for the live initial forecast because it
          performed better than the tested initial ML candidate. It converts the planned effort
          into a full-sprint labour estimate using the project team&apos;s blended rate, then presents
          the forecast with a likely range and its main cost driver.
        </p>
      </section>
    </div>
  );
}

function MidSprintForecastGuide() {
  return (
    <div className="space-y-5 pt-4">
      <section className="space-y-2" aria-labelledby="mid-purpose">
        <div className="flex items-center gap-2">
          <Activity className="size-4 text-primary" aria-hidden />
          <h3 id="mid-purpose" className="font-semibold">What it does</h3>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">
          Updates the expected final labour cost while the sprint is running by combining work
          already recorded with an estimate of the effort still required.
        </p>
      </section>

      <section className="space-y-3 border-t pt-4" aria-labelledby="mid-inputs">
        <div className="flex items-center gap-2">
          <ClipboardList className="size-4 text-primary" aria-hidden />
          <h3 id="mid-inputs" className="font-semibold">Information considered</h3>
        </div>
        <InputList items={MID_SPRINT_INPUTS} />
      </section>

      <section className="space-y-2 border-t pt-4" aria-labelledby="mid-model">
        <div className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" aria-hidden />
          <h3 id="mid-model" className="font-semibold">Model and result</h3>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">
          A Gradient Boosting model predicts the remaining effort from live sprint and human-aware
          signals. SprintIQ combines that remaining-work estimate with labour cost already incurred
          to produce the expected final cost, likely range, and cost-driver explanation. If the
          sprint has too little live progress, the initial forecast is used until enough evidence is
          available.
        </p>
      </section>
    </div>
  );
}

export function ForecastMethodDialog({ stage }: ForecastMethodDialogProps) {
  const label = stage === "initial" ? "Initial forecast" : "Mid-sprint forecast";

  return (
    <Dialog>
      <Tooltip>
        <TooltipTrigger asChild>
          <DialogTrigger asChild>
            <button
              type="button"
              className="inline-flex size-5 shrink-0 items-center justify-center text-muted-foreground/70 transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label={`Learn how the ${label.toLowerCase()} works`}
            >
              <CircleHelp className="size-3.5" aria-hidden />
            </button>
          </DialogTrigger>
        </TooltipTrigger>
        <TooltipContent>How the {label.toLowerCase()} works</TooltipContent>
      </Tooltip>

      <DialogContent className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>How sprint cost forecasting works</DialogTitle>
          <DialogDescription>
            Forecast inputs are collected automatically from SprintIQ planning, team, capacity,
            time-entry, and sprint-history data.
          </DialogDescription>
        </DialogHeader>

        <Tabs defaultValue={stage}>
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="initial">
              <LineChart className="size-4" aria-hidden />
              Initial
            </TabsTrigger>
            <TabsTrigger value="mid-sprint">
              <Activity className="size-4" aria-hidden />
              Mid-sprint
            </TabsTrigger>
          </TabsList>
          <TabsContent value="initial">
            <InitialForecastGuide />
          </TabsContent>
          <TabsContent value="mid-sprint">
            <MidSprintForecastGuide />
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
