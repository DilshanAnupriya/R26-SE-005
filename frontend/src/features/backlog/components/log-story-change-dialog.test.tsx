import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { LogStoryChangeDialog } from "./log-story-change-dialog";
import type { Story } from "@/lib/api/delivery-schemas";

/**
 * The form that edits a story and logs the edit as a requirement change.
 *
 * <p>Two things carry it, and both are easy to get subtly wrong. The form has to submit
 * only the field the chosen change type owns — sending a stale description alongside a
 * priority change would rewrite the story behind the author's back. And the direction it
 * previews has to agree with the direction the server derives, because a form that says
 * "this will lower the risk" and then raises it teaches people to stop trusting the
 * number.</p>
 *
 * <p>Priority is the exception to the first rule and has its own group below: it is a
 * permanent field rather than one the change-type dropdown reveals, so moving it has to
 * carry the change type along with it.</p>
 */

const STORY_ID = "3f1b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";

const STORY = {
  id: STORY_ID,
  projectId: "8f1b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  sprintId: "9f1b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  itemKey: "MBA-14",
  title: "Export the settlement report",
  description: "Export the report.",
  acceptanceCriteria: "Downloads as CSV\nIncludes the archived rows",
  priority: "MEDIUM",
  storyPoints: 5,
} as unknown as Story;

const applyChange = vi.fn();

vi.mock("../hooks", () => ({
  useStory: () => ({ data: STORY, isLoading: false }),
  useApplyRequirementChange: () => ({ mutate: applyChange, isPending: false }),
}));

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

/** Picks an option out of the Radix select the form renders. */
async function choose(user: ReturnType<typeof userEvent.setup>, trigger: HTMLElement, option: string) {
  await user.click(trigger);
  await user.click(await screen.findByRole("option", { name: option }));
}

function open() {
  render(<LogStoryChangeDialog storyId={STORY_ID} open onOpenChange={() => {}} />);
}

describe("Log a change", () => {
  beforeEach(() => {
    applyChange.mockReset();
  });

  it("names the story it was opened on, before anything else", async () => {
    open();

    const dialog = await screen.findByRole("dialog");
    const labels = within(dialog).getAllByText(/User story|What changed\?/);
    // The story is the first thing on the form. Opened from a row, it is a statement
    // about which row — anything that let it be chosen could disagree with the row.
    expect(labels[0]).toHaveTextContent("User story");
    expect(within(dialog).getByText("MBA-14")).toBeInTheDocument();
    expect(within(dialog).getByText("Export the settlement report")).toBeInTheDocument();
  });

  it("sends only the field the change type owns", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("Priority"), "Critical");
    await user.click(screen.getByRole("button", { name: "Log change" }));

    await waitFor(() => expect(applyChange).toHaveBeenCalled());
    const body = applyChange.mock.calls[0][0];
    expect(body.changeType).toBe("PRIORITY_CHANGED");
    expect(body.newPriority).toBe("CRITICAL");
    // The description box is pre-filled with the story's current text, and a priority
    // change must not carry it along as an edit.
    expect(body.newDescription).toBeNull();
    expect(body.newAcceptanceCriteria).toBeNull();
    expect(body.newStoryPoints).toBeNull();
  });

  it("previews a longer description as harder and a shorter one as easier", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Description updated");
    const box = await screen.findByLabelText("Description");

    await user.type(box, " Also as PDF, including the archived rows.");
    expect(await screen.findByText(/risk will go up/i)).toBeInTheDocument();

    await user.clear(box);
    await user.type(box, "Export.");
    expect(await screen.findByText(/risk will come down/i)).toBeInTheDocument();
  });

  it("reads an extra acceptance criterion as harder", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Acceptance criteria changed");
    await user.type(await screen.findByLabelText("Acceptance criteria"), "\nSigned by the auditor");

    expect(await screen.findByText(/risk will go up/i)).toBeInTheDocument();
  });

  it("warns when the scope type and the estimate disagree", async () => {
    const user = userEvent.setup();
    open();

    // "Scope added" with a smaller estimate is incoherent; the server refuses it, and
    // the form has to say so before somebody submits and gets an error back.
    const estimate = await screen.findByLabelText("New estimate");
    await user.clear(estimate);
    await user.type(estimate, "3");

    expect(await screen.findByText(/log this as scope removed/i)).toBeInTheDocument();
  });

  it("says nothing has moved until something has", async () => {
    open();

    expect(await screen.findByText(/Nothing has moved yet/i)).toBeInTheDocument();
  });
});

/**
 * Priority, which is a permanent field and therefore its own set of rules.
 *
 * <p>It sits on the form from the moment it opens, showing the story's current level.
 * The alternative — revealed only once "Priority changed" is chosen from a dropdown —
 * meant the single property people most often come here to move was the one they had to
 * go looking for, and priorities got changed on the board instead, where nothing charges
 * the sprint's risk.</p>
 */
describe("Log a change · priority", () => {
  beforeEach(() => {
    applyChange.mockReset();
  });

  it("shows the story's current priority without a change type being picked", async () => {
    open();

    const priority = await screen.findByLabelText("Priority");
    expect(priority).toHaveTextContent("Medium");
    expect(screen.getByText("Unchanged")).toBeInTheDocument();
    // The dialog opens on "Scope added", so this field is visible on a form whose
    // change type owns an entirely different one.
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Scope added");
  });

  it("carries the change type along when the priority moves", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("Priority"), "High");

    // Without this the server would write the estimate this form was set to edit and
    // drop the priority silently, charging the sprint for the wrong thing.
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Priority changed");
    expect(screen.getByText("Was Medium")).toBeInTheDocument();
  });

  it("reads a raised priority as an increase", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("Priority"), "High");
    expect(await screen.findByText(/requirement-change risk will go up/i)).toBeInTheDocument();

    await choose(user, await screen.findByLabelText("Priority"), "Critical");
    expect(await screen.findByText(/requirement-change risk will go up/i)).toBeInTheDocument();
  });

  it("reads a lowered priority as a decrease", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("Priority"), "Low");
    expect(await screen.findByText(/requirement-change risk will come down/i)).toBeInTheDocument();

    await choose(user, await screen.findByLabelText("Priority"), "Lowest");
    expect(await screen.findByText(/requirement-change risk will come down/i)).toBeInTheDocument();
  });

  it("submits the level the author chose, not the one the story had", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("Priority"), "Low");
    await user.click(screen.getByRole("button", { name: "Log change" }));

    await waitFor(() => expect(applyChange).toHaveBeenCalled());
    const body = applyChange.mock.calls[0][0];
    expect(body.changeType).toBe("PRIORITY_CHANGED");
    expect(body.newPriority).toBe("LOW");
  });

  it("gives the change type back when the priority is set to where it started", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Description updated");
    await choose(user, await screen.findByLabelText("Priority"), "Critical");
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Priority changed");

    // Changing your mind must not strand the form on a type nobody picked, describing a
    // field that is back where it was — the server refuses that as a no-op change.
    await choose(user, await screen.findByLabelText("Priority"), "Medium");
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Description updated");
    expect(screen.getByText("Unchanged")).toBeInTheDocument();
  });

  it("does not offer a second priority control when the type names it", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Priority changed");

    // One value, one control. Two selects bound to the same state would let the form
    // disagree with itself about what is being logged.
    expect(screen.getAllByLabelText("Priority")).toHaveLength(1);
    expect(screen.queryByLabelText("New priority")).not.toBeInTheDocument();
    expect(screen.getByText(/Set the new priority in the/i)).toBeInTheDocument();
  });
});
