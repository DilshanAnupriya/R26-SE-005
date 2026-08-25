import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import TimelinePage from "./page";
import type { Bar, Timeline } from "@/features/timeline/api";

/**
 * The log-change button on a timeline row.
 *
 * <p>It shipped hidden until the row was hovered, which on a chart of thin rows meant
 * most people never found it — a control nobody can see is a control that does not
 * exist. These pin that it is rendered outright, and that it is offered only on the
 * rows it actually works for.</p>
 *
 * <p>Rendering the real page with its two data hooks stubbed, rather than a copy of the
 * row markup: the thing worth testing is what this page draws, and a fixture that
 * duplicated the markup would go on passing after somebody changed it.</p>
 */

const bar = (overrides: Partial<Bar>): Bar =>
  ({
    id: "3f1b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
    itemKey: "MBA-14",
    title: "Export the settlement report",
    kind: "STORY",
    start: "2026-08-24",
    end: "2026-08-28",
    dateSource: "SPRINT",
    progressPct: 40,
    status: "IN_PROGRESS",
    assigneeId: null,
    assigneeName: null,
    blocked: false,
    overdue: false,
    ...overrides,
  }) as Bar;

const TIMELINE = {
  from: "2026-08-17",
  to: "2026-08-31",
  today: "2026-08-25",
  weeks: [{ start: "2026-08-17", label: "17 Aug" }],
  groups: [
    {
      bar: bar({ id: "epic-1", itemKey: "MBA-E1", title: "Settlement", kind: "EPIC" }),
      rows: [
        bar({ id: "story-1", itemKey: "MBA-14" }),
        bar({ id: "story-2", itemKey: "MBA-15", title: "Reconcile the ledger" }),
      ],
    },
  ],
  milestones: [bar({ id: "ms-1", itemKey: null, title: "Go live", kind: "MILESTONE" })],
  sprints: [],
  undatedCount: 0,
} as unknown as Timeline;

vi.mock("@/features/timeline/hooks", () => ({
  useTimeline: () => ({ data: TIMELINE, isLoading: false }),
}));

vi.mock("@/features/projects/hooks", () => ({
  useProjects: () => ({
    data: { content: [{ id: "p-1", projectKey: "MBA", name: "Mobile App" }] },
    isLoading: false,
  }),
}));

// The dialog fetches the story it was opened on; nothing here opens it.
vi.mock("@/features/backlog/hooks", () => ({
  useStory: () => ({ data: undefined, isLoading: true }),
  useApplyRequirementChange: () => ({ mutate: vi.fn(), isPending: false }),
}));

describe("Timeline rows", () => {
  it("shows a log-change button on every story row, not only on hover", () => {
    render(<TimelinePage />);

    const buttons = screen.getAllByRole("button", { name: /Log a requirement change/i });
    expect(buttons).toHaveLength(2);

    for (const button of buttons) {
      // The regression was `opacity-0` with a hover reveal. Anything that hides the
      // control until the pointer is over it puts it back out of reach.
      expect(button.className).not.toMatch(/opacity-0/);
      expect(button.className).not.toMatch(/group-hover/);
    }
  });

  it("names the story each button belongs to", () => {
    render(<TimelinePage />);

    expect(
      screen.getByRole("button", { name: /Log a requirement change on MBA-14/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Log a requirement change on MBA-15/i }),
    ).toBeInTheDocument();
  });

  it("offers nothing on epics or milestones", () => {
    render(<TimelinePage />);

    // An epic is not a requirement anybody revises, and a milestone is a date. A button
    // that opened a form the server would then refuse is worse than no button.
    expect(
      screen.queryByRole("button", { name: /Log a requirement change on MBA-E1/i }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Log a requirement change on Go live/i }),
    ).not.toBeInTheDocument();
  });

  it("keeps the story titles readable beside the button", () => {
    render(<TimelinePage />);

    // The button sits in the fixed-width label column. If it were paid for out of that
    // column rather than added to it, the titles would truncate sooner than before.
    const row = screen
      .getByRole("button", { name: /Log a requirement change on MBA-14/i })
      .closest("div") as HTMLElement;
    expect(within(row).getByText("Export the settlement report")).toBeInTheDocument();
  });
});
