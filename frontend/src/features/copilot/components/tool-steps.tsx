import { CircleAlert, CircleCheck, Wrench } from "lucide-react";
import type { ToolStep } from "@/lib/api/copilot-schemas";

/**
 * Which tools an answer rests on.
 *
 * <p>Collapsed by default — the answer is the point — but always present, so a reader
 * can tell an answer grounded in a risk score from one that read nothing at all.</p>
 */
export function ToolSteps({ steps }: { steps: ToolStep[] }) {
  if (steps.length === 0) return null;
  const failed = steps.filter((step) => !step.ok).length;

  return (
    <details className="group text-[11px] text-muted-foreground">
      <summary className="flex cursor-pointer list-none items-center gap-1 select-none hover:text-foreground">
        <Wrench className="size-3" aria-hidden />
        Used {steps.length} {steps.length === 1 ? "tool" : "tools"}
        {failed > 0 ? `, ${failed} failed` : ""}
      </summary>
      <ul className="mt-1 space-y-0.5 pl-4">
        {steps.map((step, index) => (
          <li key={`${step.tool}-${index}`} className="flex items-start gap-1.5">
            {step.ok ? (
              <CircleCheck className="mt-px size-3 shrink-0 text-emerald-600" aria-label="succeeded" />
            ) : (
              <CircleAlert className="mt-px size-3 shrink-0 text-destructive" aria-label="failed" />
            )}
            <span>{step.summary ?? step.tool}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}
