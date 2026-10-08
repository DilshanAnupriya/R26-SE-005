import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Proposal } from "@/lib/api/copilot-schemas";
import { ProposalCard } from "./proposal-card";

function proposal(overrides: Partial<Proposal> = {}): Proposal {
  return {
    id: "22222222-2222-2222-2222-222222222222",
    conversationId: "11111111-1111-1111-1111-111111111111",
    messageId: null,
    type: "ASSIGN_TASK",
    summary: "Assign MOB-40 “Wire up the export” to Priya Raman",
    reason: "Highest fit score, 0.82",
    status: "PENDING",
    expiresAt: null,
    resolvedAt: null,
    resultMessage: null,
    payload: {},
    ...overrides,
  };
}

describe("ProposalCard", () => {
  it("shows the server's summary with Confirm and Reject while pending", async () => {
    const onConfirm = vi.fn();
    const onReject = vi.fn();
    render(<ProposalCard proposal={proposal()} onConfirm={onConfirm} onReject={onReject} />);

    expect(screen.getByText("Assign MOB-40 “Wire up the export” to Priya Raman")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /confirm/i }));
    await userEvent.click(screen.getByRole("button", { name: /reject/i }));

    expect(onConfirm).toHaveBeenCalledWith("22222222-2222-2222-2222-222222222222");
    expect(onReject).toHaveBeenCalledWith("22222222-2222-2222-2222-222222222222");
  });

  it("labels the reason as the assistant's, not as what the button does", () => {
    render(<ProposalCard proposal={proposal()} onConfirm={vi.fn()} onReject={vi.fn()} />);
    expect(screen.getByText(/assistant's reason/i)).toBeInTheDocument();
    expect(screen.getByText(/Highest fit score, 0.82/)).toBeInTheDocument();
  });

  it("disables both buttons while a decision is in flight", () => {
    render(<ProposalCard proposal={proposal()} onConfirm={vi.fn()} onReject={vi.fn()} busy />);
    expect(screen.getByRole("button", { name: /confirm/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /reject/i })).toBeDisabled();
  });

  it("shows the outcome instead of buttons once confirmed", () => {
    render(
      <ProposalCard
        proposal={proposal({ status: "CONFIRMED", resultMessage: "Assigned to Priya Raman." })}
        onConfirm={vi.fn()}
        onReject={vi.fn()}
      />,
    );
    expect(screen.queryByRole("button", { name: /confirm/i })).not.toBeInTheDocument();
    expect(screen.getByText("Confirmed")).toBeInTheDocument();
    expect(screen.getByText("Assigned to Priya Raman.")).toBeInTheDocument();
  });

  it("says why a confirmed change failed", () => {
    render(
      <ProposalCard
        proposal={proposal({ status: "FAILED", resultMessage: "Sprint is over capacity" })}
        onConfirm={vi.fn()}
        onReject={vi.fn()}
      />,
    );
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("Sprint is over capacity")).toBeInTheDocument();
  });

  it("offers nothing to click on an expired proposal", () => {
    render(
      <ProposalCard proposal={proposal({ status: "EXPIRED" })} onConfirm={vi.fn()} onReject={vi.fn()} />,
    );
    expect(screen.getByText("Expired")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
