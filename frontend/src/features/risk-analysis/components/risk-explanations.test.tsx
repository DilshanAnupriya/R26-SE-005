import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RiskExplanations } from "./risk-explanations";
import type { RiskAdjustment, RiskAssessment, SprintRisk } from "@/lib/api/ai-schemas";

/**
 * The logged requirement changes, shown beside the score they moved.
 *
 * <p>Every other finding on this panel is a board reading the models re-read for
 * themselves. A logged change is not in any feature vector, so pressing <em>Assess</em>
 * cannot fold it away — which left one entry a team could see and could not answer, and
 * left it only in the ledger card at the foot of the page where all three risks are
 * mixed together.</p>
 *
 * <p>What the tick claims is narrow, and these pin the narrowness: the plan was adjusted
 * around the change, not the change undone. The credit is therefore always partial, and
 * a change that made the sprint easier is not offered one at all.</p>
 */

const SPRINT = "11111111-1111-1111-1111-111111111111";

function assessment(overrides: Partial<RiskAssessment> = {}): RiskAssessment {
  return {
    id: "22222222-2222-2222-2222-222222222222",
    sprintId: SPRINT,
    riskType: "REQUIREMENT_CHANGE",
    riskScore: 0.4,
    atRisk: false,
    severity: "MEDIUM",
    confidence: 0.6,
    drivers: [],
    mitigations: [],
    explanation: "Requirements are moving.",
    predictionId: null,
    acknowledgedBy: null,
    acknowledgedAt: null,
    createdAt: "2026-08-30T09:00:00Z",
    adjustmentDelta: 4,
    adjustedScore: 0.44,
    adjustedSeverity: "MEDIUM",
    ...overrides,
  } as RiskAssessment;
}

let seq = 0;
function adjustment(overrides: Partial<RiskAdjustment> = {}): RiskAdjustment {
  seq += 1;
  return {
    id: `3333333${seq}-3333-3333-3333-333333333333`,
    sprintId: SPRINT,
    riskType: "REQUIREMENT_CHANGE",
    delta: 4,
    reason: "Priority raised mid-sprint: planning disruption",
    createdAt: "2026-08-30T10:00:00Z",
    sourceKey: "REQ_CHANGE:aaaaaaaa-0000-4000-8000-000000000001",
    code: "REQ_CHANGE",
    resolvable: false,
    survivesScoring: true,
    ...overrides,
  } as RiskAdjustment;
}

const RISK = {
  sprintId: SPRINT,
  overBudget: null,
  requirementChange: assessment(),
  communicationCollaboration: null,
  highestSeverity: "MEDIUM",
  overall: null,
} as unknown as SprintRisk;

type Absorb = (input: { adjustmentId: string; absorbed: boolean }) => void;

function open(adjustments: RiskAdjustment[], onAbsorb?: Absorb) {
  render(
    <RiskExplanations
      risk={RISK}
      adjustments={adjustments}
      steps={[]}
      onAbsorb={onAbsorb}
    />,
  );
  // The panel opens on the loudest risk, which is the only scored one here.
  return screen.getByRole("tabpanel");
}

describe("Why each risk is on this sprint · logged changes", () => {
  it("shows a logged priority change, with its reason, in the requirement-change tab", () => {
    const panel = open([adjustment()]);

    expect(
      within(panel).getByText("Logged changes the model cannot see"),
    ).toBeInTheDocument();
    expect(
      within(panel).getByText("Priority raised mid-sprint: planning disruption"),
    ).toBeInTheDocument();
    // The charge, on the ledger's own 0-100 scale.
    expect(within(panel).getByText("+4 pts")).toBeInTheDocument();
  });

  it("offers a tick priced at five-eighths of the charge, never all of it", () => {
    const panel = open([adjustment()]);

    expect(within(panel).getByRole("checkbox")).not.toBeChecked();
    // 4 × 0.625 = 2.5, rounded. The remaining point and a half stays on the score,
    // because the requirement did move whether or not the team coped.
    expect(within(panel).getByText(/worth 3 of its 4 points/i)).toBeInTheDocument();
  });

  it("names the entry and nothing else when ticked", async () => {
    const user = userEvent.setup();
    const onAbsorb = vi.fn();
    const entry = adjustment();
    const panel = open([entry], onAbsorb);

    await user.click(within(panel).getByRole("checkbox"));

    // The points are the server's to decide — a client that could name its own delta
    // could set the sprint's risk to anything and have the ledger record it as evidence.
    expect(onAbsorb).toHaveBeenCalledWith({ adjustmentId: entry.id, absorbed: true });
  });

  it("reads as claimed once the credit is on the ledger", () => {
    const charge = adjustment();
    const credit = adjustment({
      delta: -2.5,
      reason: "Re-planned for: Priority raised mid-sprint: planning disruption",
      sourceKey: `ABSORBED:${charge.sourceKey}`,
      code: "ABSORBED",
    });
    const panel = open([charge, credit]);

    expect(within(panel).getByRole("checkbox")).toBeChecked();
    expect(within(panel).getByText("+4, −3 applied")).toBeInTheDocument();
    expect(within(panel).getByText(/The rest stands/i)).toBeInTheDocument();
  });

  it("unticks back to the full charge", async () => {
    const user = userEvent.setup();
    const onAbsorb = vi.fn();
    const charge = adjustment();
    const credit = adjustment({
      delta: -2.5,
      reason: "Re-planned for: Priority raised mid-sprint: planning disruption",
      sourceKey: `ABSORBED:${charge.sourceKey}`,
    });
    const panel = open([charge, credit], onAbsorb);

    await user.click(within(panel).getByRole("checkbox"));

    expect(onAbsorb).toHaveBeenCalledWith({ adjustmentId: charge.id, absorbed: false });
  });

  it("states a change that lowered the risk without offering a tick", () => {
    const panel = open([
      adjustment({
        delta: -5,
        reason: "Scope removed: less to deliver than was committed",
      }),
    ]);

    expect(
      within(panel).getByText("Scope removed: less to deliver than was committed"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("−5 pts")).toBeInTheDocument();
    // Nothing to plan around in a sprint getting easier, and a checkbox beside one
    // would invite somebody to try.
    expect(within(panel).queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("leaves board readings out — those clear themselves when the board changes", () => {
    const panel = open([
      adjustment({
        delta: 8,
        reason: "Task blocked: PAY-14",
        sourceKey: "TASK_OVERDUE:aaaaaaaa-0000-4000-8000-000000000002",
        code: "TASK_OVERDUE",
        survivesScoring: false,
      }),
    ]);

    expect(within(panel).queryByText("Task blocked: PAY-14")).not.toBeInTheDocument();
    expect(
      within(panel).queryByText("Logged changes the model cannot see"),
    ).not.toBeInTheDocument();
  });

  it("shows an acceptance-criteria change the same way, with its own tick", () => {
    // Criteria are edited through the same dialog and land in the same register, so the
    // row an added criterion produces has to reach this panel like any other logged
    // change — reason, charge, and something the team can answer it with.
    const panel = open([
      adjustment({
        delta: 5,
        reason: "Acceptance criteria expanded: rework and misalignment risk",
      }),
    ]);

    expect(
      within(panel).getByText("Acceptance criteria expanded: rework and misalignment risk"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("+5 pts")).toBeInTheDocument();
    expect(within(panel).getByRole("checkbox")).not.toBeChecked();
    // 5 x 0.625 = 3.125, rounded down to 3 by the server's whole-point rule.
    expect(within(panel).getByText(/worth 3 of its 5 points/i)).toBeInTheDocument();
  });

  it("says nothing at all when no change has been logged", () => {
    const panel = open([]);

    expect(
      within(panel).queryByText("Logged changes the model cannot see"),
    ).not.toBeInTheDocument();
  });
});
