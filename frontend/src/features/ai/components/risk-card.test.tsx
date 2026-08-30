import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { RiskAssessment } from "@/lib/api/ai-schemas";
import { RiskCard } from "./risk-card";

/**
 * The card must read the same figure the risk analysis page does.
 *
 * <p>`latest` folds the board's ledger into every assessment it returns, and this card
 * used to render `riskScore` — the model's own number from whenever Assess was last
 * pressed. Two pages then quoted different percentages for one sprint, which is the
 * failure the adjusted fields exist to prevent.</p>
 */
function assessment(overrides: Partial<RiskAssessment> = {}): RiskAssessment {
  return {
    id: "33333333-3333-3333-3333-333333333333",
    sprintId: "11111111-1111-1111-1111-111111111111",
    riskType: "OVER_BUDGET",
    riskScore: 0.62,
    atRisk: true,
    severity: "HIGH",
    confidence: 0.8,
    drivers: [],
    mitigations: [],
    explanation: null,
    predictionId: null,
    acknowledgedBy: null,
    acknowledgedAt: null,
    createdAt: "2026-08-31T00:00:00Z",
    adjustmentDelta: 0,
    adjustedScore: 0.62,
    adjustedSeverity: "HIGH",
    ...overrides,
  };
}

describe("RiskCard", () => {
  it("shows the adjusted score, not the model's own", () => {
    render(<RiskCard assessment={assessment({
      riskScore: 0.62, severity: "HIGH",
      adjustmentDelta: 8, adjustedScore: 0.7, adjustedSeverity: "HIGH",
    })} />);

    expect(screen.getByText("70%")).toBeInTheDocument();
    expect(screen.queryByText("62%")).not.toBeInTheDocument();
  });

  it("keeps the model's own figure visible when the board has moved it", () => {
    render(<RiskCard assessment={assessment({
      riskScore: 0.62, adjustmentDelta: 8, adjustedScore: 0.7,
    })} />);

    expect(screen.getByText(/Model said 62%/)).toBeInTheDocument();
    expect(screen.getByText(/\+8 from the board/)).toBeInTheDocument();
  });

  it("bands the badge from the adjusted score", () => {
    // The board pushing a MEDIUM sprint over 0.60 must repaint the badge, or the
    // number and the band disagree on the same line.
    render(<RiskCard assessment={assessment({
      riskScore: 0.55, severity: "MEDIUM",
      adjustmentDelta: 9, adjustedScore: 0.64, adjustedSeverity: "HIGH",
    })} />);

    expect(screen.getByText("HIGH")).toBeInTheDocument();
    expect(screen.queryByText("MEDIUM")).not.toBeInTheDocument();
  });

  it("says nothing about the board when the ledger is empty", () => {
    render(<RiskCard assessment={assessment()} />);

    expect(screen.getByText("62%")).toBeInTheDocument();
    expect(screen.queryByText(/from the board/)).not.toBeInTheDocument();
  });

  it("falls back to the model's figures when the server sends no adjusted ones", () => {
    // `forProject` builds responses through the one-arg `from`, which leaves these null.
    render(<RiskCard assessment={assessment({
      adjustedScore: null, adjustedSeverity: null,
    })} />);

    expect(screen.getByText("62%")).toBeInTheDocument();
    expect(screen.getByText("HIGH")).toBeInTheDocument();
  });
});
