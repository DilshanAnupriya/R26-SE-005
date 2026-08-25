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

    await choose(user, await screen.findByLabelText("What changed?"), "Priority changed");
    await choose(user, await screen.findByLabelText("New priority"), "Critical");
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

  it("previews a raised priority as harder", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Priority changed");
    await choose(user, await screen.findByLabelText("New priority"), "Critical");

    expect(await screen.findByText(/requirement-change risk will go up/i)).toBeInTheDocument();
  });

  it("previews a dropped priority as easier", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Priority changed");
    await choose(user, await screen.findByLabelText("New priority"), "Low");

    expect(await screen.findByText(/requirement-change risk will come down/i)).toBeInTheDocument();
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

    expect(
      await screen.findByText(/Nothing has moved yet/i),
    ).toBeInTheDocument();
  });
});
