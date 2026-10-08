import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ConversationDetail, CopilotStatus } from "@/lib/api/copilot-schemas";
import { CopilotThread } from "./copilot-thread";

const CONVERSATION = "11111111-1111-1111-1111-111111111111";
const NEW_CONVERSATION = "33333333-3333-3333-3333-333333333333";

const harness = vi.hoisted(() => ({
  status: { enabled: true, model: "gemini-2.0-flash", reason: null } as CopilotStatus,
  detail: undefined as ConversationDetail | undefined,
  sent: [] as { conversationId: string; content: string }[],
  created: 0,
}));

vi.mock("../hooks", () => ({
  useCopilotStatus: () => ({ data: harness.status, isLoading: false }),
  useConversation: () => ({ data: harness.detail, isLoading: false, isError: false }),
  useCreateConversation: () => ({
    isPending: false,
    mutateAsync: async () => {
      harness.created += 1;
      return { id: NEW_CONVERSATION, title: "New conversation" };
    },
  }),
  useSendMessage: () => ({
    isPending: false,
    isError: false,
    error: null,
    mutate: (input: { conversationId: string; content: string }) => harness.sent.push(input),
  }),
  useConfirmProposal: () => ({ isPending: false, variables: undefined, mutate: vi.fn() }),
  useRejectProposal: () => ({ isPending: false, variables: undefined, mutate: vi.fn() }),
}));

describe("CopilotThread", () => {
  beforeEach(() => {
    harness.status = { enabled: true, model: "gemini-2.0-flash", reason: null };
    harness.detail = undefined;
    harness.sent = [];
    harness.created = 0;
  });

  it("explains why it is off instead of offering a box to type into", () => {
    harness.status = { enabled: false, model: null, reason: "Set ADVISOR_API_KEY to enable it." };
    render(<CopilotThread conversationId={null} onConversationCreated={vi.fn()} />);

    expect(screen.getByText(/Set ADVISOR_API_KEY/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("starts a conversation when the first question is a suggestion", async () => {
    const onCreated = vi.fn();
    render(<CopilotThread conversationId={null} onConversationCreated={onCreated} />);

    await userEvent.click(screen.getByRole("button", { name: /most at risk/i }));

    await waitFor(() => expect(harness.sent).toHaveLength(1));
    expect(harness.created).toBe(1);
    expect(onCreated).toHaveBeenCalledWith(NEW_CONVERSATION);
    expect(harness.sent[0].conversationId).toBe(NEW_CONVERSATION);
  });

  it("sends a typed question to the open conversation on Enter", async () => {
    harness.detail = { id: CONVERSATION, title: "t", messages: [] };
    render(<CopilotThread conversationId={CONVERSATION} onConversationCreated={vi.fn()} />);

    await userEvent.type(screen.getByRole("textbox"), "Who should take MOB-40?{Enter}");

    expect(harness.sent).toEqual([{ conversationId: CONVERSATION, content: "Who should take MOB-40?" }]);
    expect(harness.created).toBe(0);
  });

  it("renders answers with the tools they used and dash lines as a list", () => {
    harness.detail = {
      id: CONVERSATION,
      title: "Risk",
      messages: [
        { id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", role: "USER", content: "How risky?", toolSteps: [], proposals: [] },
        {
          id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          role: "ASSISTANT",
          content: "Sprint 7 is at 70%.\n- Commitment ratio 1.4\n- Two silent tasks",
          model: "gemini-2.0-flash",
          toolSteps: [
            { tool: "list_sprints", ok: true, summary: "Listed sprints" },
            { tool: "get_sprint_risk", ok: true, summary: "Read sprint risk (M2)" },
          ],
          proposals: [],
        },
      ],
    };
    render(<CopilotThread conversationId={CONVERSATION} onConversationCreated={vi.fn()} />);

    expect(screen.getByText("Sprint 7 is at 70%.")).toBeInTheDocument();
    const items = screen.getAllByRole("listitem").map((li) => li.textContent);
    expect(items).toContain("Commitment ratio 1.4");
    expect(items).toContain("Two silent tasks");
    expect(screen.getByText(/Used 2 tools/)).toBeInTheDocument();
    expect(screen.getByText("Read sprint risk (M2)")).toBeInTheDocument();
  });
});
