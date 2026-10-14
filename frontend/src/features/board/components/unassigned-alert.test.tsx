import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { taskSchema, type Task } from "@/lib/api/delivery-schemas";
import { UnassignedAlert, unassignedTasks } from "./unassigned-alert";

const PROJECT = "d0000000-0000-4000-8000-000000000001";

function task(overrides: Partial<Record<string, unknown>> & { id: string; itemKey: string }): Task {
  return taskSchema.parse({
    projectId: PROJECT,
    title: `Task ${overrides.itemKey}`,
    status: "TODO",
    estimateMinutes: 60,
    ...overrides,
  });
}

const OPEN = task({ id: "a0000000-0000-4000-8000-000000000001", itemKey: "PAY-1" });
const OWNED = task({
  id: "a0000000-0000-4000-8000-000000000002",
  itemKey: "PAY-2",
  assigneeId: "b0000000-0000-4000-8000-000000000001",
});
const DONE = task({ id: "a0000000-0000-4000-8000-000000000003", itemKey: "PAY-3", status: "DONE" });
// A story card: the board sends a story as a task row carrying its own id as storyId.
const STORY_CARD = task({
  id: "a0000000-0000-4000-8000-000000000004",
  storyId: "a0000000-0000-4000-8000-000000000004",
  itemKey: "PAY-4",
});

const MEMBERS = [{ userId: "c0000000-0000-4000-8000-000000000001", fullName: "Sam Perera" }];

describe("unassignedTasks", () => {
  it("keeps open real tasks with nobody on them, and nothing else", () => {
    expect(unassignedTasks([OPEN, OWNED, DONE, STORY_CARD]).map((t) => t.itemKey)).toEqual(["PAY-1"]);
  });
});

describe("UnassignedAlert", () => {
  it("is red and counts the waiting tasks while any is unassigned", () => {
    render(<UnassignedAlert tasks={[OPEN, OWNED]} members={MEMBERS} onAssign={vi.fn()} />);

    const button = screen.getByRole("button", { name: "1 task with nobody assigned" });
    expect(button.className).toContain("text-red");
    expect(button.textContent).toContain("1");
  });

  it("is quiet when every open task has an owner", () => {
    render(<UnassignedAlert tasks={[OWNED, DONE]} members={MEMBERS} onAssign={vi.fn()} />);

    const button = screen.getByRole("button", { name: "Every open task has an owner" });
    expect(button.className).not.toContain("text-red");
  });

  it("lists the waiting tasks and assigns from the list", () => {
    const onAssign = vi.fn();
    render(<UnassignedAlert tasks={[OPEN]} members={MEMBERS} onAssign={onAssign} />);

    fireEvent.click(screen.getByRole("button", { name: "1 task with nobody assigned" }));
    expect(screen.getByText("Task PAY-1")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("Assign PAY-1"), {
      target: { value: MEMBERS[0].userId },
    });
    expect(onAssign).toHaveBeenCalledWith(OPEN.id, MEMBERS[0].userId);
  });
});
