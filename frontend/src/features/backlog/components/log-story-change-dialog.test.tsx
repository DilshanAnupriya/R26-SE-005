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
    const key = within(dialog).getByText("MBA-14");
    expect(within(dialog).getByText("Export the settlement report")).toBeInTheDocument();

    // The story is the first thing on the form. Opened from a row, it is a statement
    // about which row — anything that let it be chosen could disagree with the row. So
    // it has to precede the first control, not merely be somewhere on the form.
    const firstControl = within(dialog).getByText("What changed?");
    expect(
      key.compareDocumentPosition(firstControl) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("lays the paired controls out in two columns, inside a bounded form", async () => {
    open();

    const dialog = await screen.findByRole("dialog");
    // Structural rather than visual, but it is the part that regresses silently: a
    // reflow that drops the grid puts the form back to one tall stack, and nothing
    // else in this file would notice.
    expect(dialog.className).toContain("sm:max-w-3xl");

    const form = dialog.querySelector("form");
    // The dialog is centred on the viewport, so an unbounded form clips at both ends
    // and takes the submit button with it.
    expect(form?.className).toContain("overflow-y-auto");
    expect(form?.querySelectorAll(":scope > .grid.sm\\:grid-cols-2")).toHaveLength(2);
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
    // `delay: null` because this test types twice and the direction only depends on the
    // final length. At the default per-keystroke delay it was the slowest test in the
    // file and tipped over the timeout once the file grew.
    const user = userEvent.setup({ delay: null });
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Description updated");
    const box = await screen.findByLabelText("Description");

    // The story's description is "Export the report." — 18 characters once whitespace is
    // collapsed, which is the number the server compares against.
    await user.type(box, " Also as PDF.");
    expect(await screen.findByText(/risk will go up/i)).toBeInTheDocument();

    await user.clear(box);
    await user.type(box, "Export.");
    expect(await screen.findByText(/risk will come down/i)).toBeInTheDocument();
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
 * Acceptance criteria, the second permanent field.
 *
 * <p>Same reasoning as priority: revealed only by the change-type dropdown, the control
 * people came here to use was the one they had to go looking for. It is a list rather
 * than a textarea because the count is what the register reads — a criterion added is
 * more to satisfy, one deleted is less, and the server derives the sign by comparing the
 * two counts.</p>
 */
describe("Log a change · acceptance criteria", () => {
  beforeEach(() => {
    applyChange.mockReset();
  });

  it("shows the story's criteria as rows without a change type being picked", async () => {
    open();

    // The dialog opens on "Scope added", and the list is on screen regardless.
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Scope added");
    expect(screen.getByDisplayValue("Downloads as CSV")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Includes the archived rows")).toBeInTheDocument();
    expect(screen.getByText("2 unchanged")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add criterion/i })).toBeInTheDocument();
  });

  it("reads an added criterion as harder, and carries the change type along", async () => {
    const user = userEvent.setup();
    open();

    await user.click(await screen.findByRole("button", { name: /add criterion/i }));
    // The new row takes focus on mount, so typing lands in it — indexing into every
    // textbox on the form would have hit the Detail box or an existing row.
    await user.keyboard("Signed by the auditor");

    expect(await screen.findByText(/risk will go up/i)).toBeInTheDocument();
    expect(screen.getByText("Was 2, now 3")).toBeInTheDocument();
    // Without this the server would write the estimate the form opened on and drop the
    // criteria silently.
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent(
      "Acceptance criteria changed",
    );
  });

  it("reads a deleted criterion as easier", async () => {
    const user = userEvent.setup();
    open();

    await user.click(
      await screen.findByRole("button", {
        name: /Delete criterion 2: Includes the archived rows/i,
      }),
    );

    expect(await screen.findByText(/risk will come down/i)).toBeInTheDocument();
    expect(screen.getByText("Was 2, now 1")).toBeInTheDocument();
  });

  it("submits the criteria as the rows now stand", async () => {
    const user = userEvent.setup();
    open();

    await user.click(await screen.findByRole("button", { name: /add criterion/i }));
    await user.keyboard("Signed by the auditor");
    await user.click(screen.getByRole("button", { name: "Log change" }));

    await waitFor(() => expect(applyChange).toHaveBeenCalled());
    const body = applyChange.mock.calls[0][0];
    expect(body.changeType).toBe("ACCEPTANCE_CRITERIA_CHANGED");
    expect(body.newAcceptanceCriteria).toBe(
      "Downloads as CSV\nIncludes the archived rows\nSigned by the auditor",
    );
    // The other permanent field is untouched, so it must not travel as an edit.
    expect(body.newPriority).toBeNull();
  });

  it("does not offer a second criteria control when the type names it", async () => {
    const user = userEvent.setup();
    open();

    await choose(user, await screen.findByLabelText("What changed?"), "Acceptance criteria changed");

    // One list, one value. Two bound to the same state would let the form disagree with
    // itself about what is being logged.
    expect(screen.getAllByRole("button", { name: /add criterion/i })).toHaveLength(1);
    expect(screen.getByText(/Add, edit or delete rows in the/i)).toBeInTheDocument();
  });

  it("says which edit will be saved when both permanent fields have moved", async () => {
    const user = userEvent.setup();
    open();

    await user.click(await screen.findByRole("button", { name: /add criterion/i }));
    await user.keyboard("Signed by the auditor");
    await choose(user, await screen.findByLabelText("Priority"), "Critical");

    // The server writes one field. Saying so here beats the other edit silently not
    // having happened.
    expect(await screen.findByText(/one change is logged at a time/i)).toBeInTheDocument();
    expect(await screen.findByLabelText("What changed?")).toHaveTextContent("Priority changed");
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
