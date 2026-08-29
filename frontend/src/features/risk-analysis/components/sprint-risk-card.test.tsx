import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { OverallRisk, SprintRisk } from "@/lib/api/ai-schemas";
import type { Sprint } from "@/lib/api/delivery-schemas";
import { SprintRiskCard } from "./sprint-risk-card";

const SPRINT: Sprint = {
  id: "11111111-1111-1111-1111-111111111111",
  projectId: "22222222-2222-2222-2222-222222222222",
  sequenceNo: 7,
  name: "Sprint 7",
  goal: null,
  status: "ACTIVE",
  startDate: null,
  endDate: null,
  durationDays: 14,
  teamSize: 5,
  capacityPoints: 40,
  capacityHours: 200,
  committedPoints: 34,
  completedPoints: 12,
  addedPoints: 0,
  removedPoints: 0,
  completionRate: null,
  plannedCostAmount: null,
  forecastCostAmount: null,
  budgetAmount: null,
  daysRemaining: 6,
  storyCount: 9,
};

function risk(score: number, level: OverallRisk["level"]): SprintRisk {
  return {
    sprintId: SPRINT.id,
    overBudget: null,
    requirementChange: null,
    communicationCollaboration: null,
    highestSeverity: level,
    overall: { score, level, degraded: false, weights: {}, rationale: null },
  };
}

function renderCard(value: SprintRisk | undefined, isLoading = false) {
  const { container } = render(
    <SprintRiskCard sprint={SPRINT} risk={value} isLoading={isLoading} />,
  );
  return container.querySelector("article") as HTMLElement;
}

/**
 * The four states as they actually reach the screen.
 *
 * <p>`sprint-risk-bands.test.ts` proves which band a figure falls in; this proves the
 * card then paints it. They are worth separating because the bug that matters here is
 * not a mis-banded score but a correctly-banded one wired to the wrong class — the
 * blink landing on "already high", say, which no amount of testing the pure function
 * would catch.</p>
 *
 * <p>The seeded database has one assessed sprint scoring 36%, so without this test
 * three of these four states have never been rendered at all.</p>
 */
describe("sprint risk card", () => {
  it("blinks only for a medium sprint within three points of high", () => {
    const card = renderCard(risk(0.58, "MEDIUM"));
    expect(card.className).toContain("animate-risk-blink");
    // The figure and the gap to the boundary, both on the card and agreeing with it.
    expect(card.textContent).toContain("58");
    expect(screen.getByText(/2 points from high risk/)).toBeInTheDocument();
  });

  it("does not blink for a sprint that is already high", () => {
    const card = renderCard(risk(0.72, "HIGH"));
    expect(card.className).not.toContain("animate-risk-blink");
    expect(screen.getByText("High risk")).toBeInTheDocument();
    expect(screen.getByText(/At or above the high-risk threshold/)).toBeInTheDocument();
  });

  it("does not blink for a critical sprint either", () => {
    const card = renderCard(risk(0.9, "CRITICAL"));
    expect(card.className).not.toContain("animate-risk-blink");
    expect(screen.getByText("High risk")).toBeInTheDocument();
  });

  it("paints an ordinary medium sprint amber, unmoving", () => {
    const card = renderCard(risk(0.42, "MEDIUM"));
    expect(card.className).not.toContain("animate-risk-blink");
    expect(card.getAttribute("style")).toContain("--tone: var(--risk-medium)");
    expect(screen.getByText("Medium risk")).toBeInTheDocument();
  });

  it("paints a calm sprint green", () => {
    const card = renderCard(risk(0.15, "LOW"));
    expect(card.className).not.toContain("animate-risk-blink");
    expect(card.getAttribute("style")).toContain("--tone: var(--risk-low)");
    expect(screen.getByText("Low risk")).toBeInTheDocument();
  });

  it("uses the high-risk token for both alarming states", () => {
    expect(renderCard(risk(0.58, "MEDIUM")).getAttribute("style")).toContain(
      "--tone: var(--risk-high)",
    );
    expect(renderCard(risk(0.72, "HIGH")).getAttribute("style")).toContain(
      "--tone: var(--risk-high)",
    );
  });

  it("says a sprint was never assessed rather than scoring it zero", () => {
    renderCard(undefined);
    expect(screen.getByText("Never assessed")).toBeInTheDocument();
    // The distinction the whole risk module is careful about: no figure at all, rather
    // than a reassuring 0% for a sprint nobody ever looked at.
    expect(screen.queryByText(/0%/)).not.toBeInTheDocument();
  });

  it("does not claim a sprint is unassessed while it is still loading", () => {
    renderCard(undefined, true);
    expect(screen.getByText("Reading risk…")).toBeInTheDocument();
    expect(screen.queryByText("Never assessed")).not.toBeInTheDocument();
  });
});
