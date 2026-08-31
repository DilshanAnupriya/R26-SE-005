import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SprintRisk } from "@/lib/api/ai-schemas";
import { RiskAdvisorChat } from "./risk-advisor-chat";

const SPRINT = "11111111-1111-1111-1111-111111111111";

/**
 * What the hook was asked for, and what it will answer.
 *
 * <p>`vi.hoisted` because `vi.mock` factories are lifted above the file's `const`s and
 * would otherwise close over a binding still in its temporal dead zone.</p>
 */
const harness = vi.hoisted(() => ({
  /** Every payload the hook was called with, oldest first. */
  calls: [] as { question: string; history: { role: string; content: string }[] }[],
  answer: {
    available: true,
    report: "Commitment ratio is 1.4, which is the driver doing most of the work.",
    model: "gemini-2.0-flash",
    reason: null,
  } as Record<string, unknown>,
}));

vi.mock("../hooks", () => ({
  useAskRiskAdvisor: () => ({
    isPending: false,
    mutate: (
      input: { question: string; history: { role: string; content: string }[] },
      opts?: { onSuccess?: (answer: Record<string, unknown>) => void },
    ) => {
      harness.calls.push(input);
      opts?.onSuccess?.(harness.answer);
    },
  }),
}));

const RISK: SprintRisk = {
  sprintId: SPRINT,
  overBudget: null,
  requirementChange: null,
  communicationCollaboration: null,
  highestSeverity: "HIGH",
  overall: { score: 0.7, level: "HIGH", degraded: false, weights: {}, rationale: null },
};

describe("RiskAdvisorChat", () => {
  beforeEach(() => {
    harness.calls = [];
    harness.answer = {
      available: true,
      report: "Commitment ratio is 1.4, which is the driver doing most of the work.",
      model: "gemini-2.0-flash",
      reason: null,
    };
  });

  it("offers openers before anything has been asked", () => {
    render(<RiskAdvisorChat risk={RISK} />);
    expect(screen.getByRole("button", { name: /Why is the overall risk/ })).toBeInTheDocument();
  });

  it("shows the question and the answer", async () => {
    const user = userEvent.setup();
    render(<RiskAdvisorChat risk={RISK} />);

    await user.type(screen.getByLabelText(/Your question/), "Why so high?");
    await user.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => expect(screen.getByText("Why so high?")).toBeInTheDocument());
    expect(screen.getByText(/Commitment ratio is 1.4/)).toBeInTheDocument();
  });

  it("sends the thread as it stood before the question, not including it", async () => {
    const user = userEvent.setup();
    render(<RiskAdvisorChat risk={RISK} />);

    await user.type(screen.getByLabelText(/Your question/), "First?");
    await user.click(screen.getByRole("button", { name: "Ask" }));
    await waitFor(() => expect(harness.calls).toHaveLength(1));

    await user.type(screen.getByLabelText(/Your question/), "And second?");
    await user.click(screen.getByRole("button", { name: "Ask" }));
    await waitFor(() => expect(harness.calls).toHaveLength(2));

    // The opening question carries no history at all.
    expect(harness.calls[0].history).toEqual([]);
    expect(harness.calls[0].question).toBe("First?");

    // The follow-up carries the first exchange — and does not repeat itself, which
    // would have the model answering its own echo.
    expect(harness.calls[1].question).toBe("And second?");
    expect(harness.calls[1].history).toEqual([
      { role: "user", content: "First?" },
      { role: "assistant", content: harness.answer.report },
    ]);
  });

  it("reports an unavailable advisor as a notice, not as an answer", async () => {
    harness.answer = {
      available: false,
      report: null,
      model: null,
      reason: "No advisor API key is configured.",
    };
    const user = userEvent.setup();
    render(<RiskAdvisorChat risk={RISK} />);

    await user.type(screen.getByLabelText(/Your question/), "Why so high?");
    await user.click(screen.getByRole("button", { name: "Ask" }));

    // The reason shows, and the question stays on screen — clearing it would look
    // like the app lost the message.
    await waitFor(() =>
      expect(screen.getByText(/No advisor API key is configured/)).toBeInTheDocument(),
    );
    expect(screen.getByText("Why so high?")).toBeInTheDocument();
  });

  it("will not send an empty question", async () => {
    const user = userEvent.setup();
    render(<RiskAdvisorChat risk={RISK} />);

    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    await user.type(screen.getByLabelText(/Your question/), "   ");
    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    expect(harness.calls).toHaveLength(0);
  });
});
