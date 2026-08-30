import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AcceptanceCriteriaList, joinCriteria, parseCriteria } from "./acceptance-criteria-list";

/**
 * Criteria as rows, and the count that comes out of them.
 *
 * <p>The count is load-bearing twice over: the story-point model sums criteria, and the
 * requirement-change model decides which way an edit pushed by comparing how many there
 * were against how many there are. So the thing worth pinning is not the markup but the
 * string this emits — one criterion per line, no blanks, whatever order somebody added
 * and deleted them in.</p>
 */

function Harness({ initial = "" }: { initial?: string }) {
  return <AcceptanceCriteriaList value={initial} onChange={onChange} />;
}

const onChange = vi.fn();

function lastValue(): string {
  return onChange.mock.calls.at(-1)?.[0] ?? "";
}

describe("acceptance criteria list", () => {
  it("parses stored criteria into rows, tolerating the bullets people paste", () => {
    expect(parseCriteria("- Downloads as CSV\n* Includes archived rows\n• Signed off")).toEqual([
      "Downloads as CSV",
      "Includes archived rows",
      "Signed off",
    ]);
  });

  it("drops blank lines rather than counting them as criteria", () => {
    // The whole reason this is not a textarea: a stray blank line used to be a criterion
    // as far as both models were concerned.
    expect(parseCriteria("One\n\n\nTwo\n   \n")).toEqual(["One", "Two"]);
    expect(joinCriteria(["One", "  ", "Two", ""])).toBe("One\nTwo");
  });

  it("shows one row per stored criterion", () => {
    render(<Harness initial={"Downloads as CSV\nIncludes archived rows"} />);

    const rows = screen.getAllByRole("textbox");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveValue("Downloads as CSV");
    expect(rows[1]).toHaveValue("Includes archived rows");
  });

  it("adds a row on demand, and keeps adding", async () => {
    const user = userEvent.setup();
    onChange.mockClear();
    render(<Harness />);

    expect(screen.queryAllByRole("textbox")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: /add criterion/i }));
    await user.type(screen.getByRole("textbox"), "Downloads as CSV");

    await user.click(screen.getByRole("button", { name: /add criterion/i }));
    expect(screen.getAllByRole("textbox")).toHaveLength(2);
    await user.type(screen.getAllByRole("textbox")[1], "Includes archived rows");

    await user.click(screen.getByRole("button", { name: /add criterion/i }));
    expect(screen.getAllByRole("textbox")).toHaveLength(3);

    expect(lastValue()).toBe("Downloads as CSV\nIncludes archived rows");
  });

  it("keeps an empty row on screen while it is being typed into", async () => {
    const user = userEvent.setup();
    onChange.mockClear();
    render(<Harness initial="Downloads as CSV" />);

    await user.click(screen.getByRole("button", { name: /add criterion/i }));

    // The row survives even though it contributes nothing to the emitted string. Deriving
    // the rows back from that string would delete the row somebody just asked for.
    expect(screen.getAllByRole("textbox")).toHaveLength(2);
    expect(lastValue()).toBe("Downloads as CSV");
  });

  it("deletes the row its icon belongs to, not the last one", async () => {
    const user = userEvent.setup();
    onChange.mockClear();
    render(<Harness initial={"First\nSecond\nThird"} />);

    await user.click(screen.getByRole("button", { name: /Delete criterion 2: Second/i }));

    expect(lastValue()).toBe("First\nThird");
    expect(screen.getAllByRole("textbox")).toHaveLength(2);
  });

  it("names each delete button by its own criterion", () => {
    render(<Harness initial={"Downloads as CSV\nIncludes archived rows"} />);

    // Five identical "delete" buttons is what a screen reader gets otherwise.
    expect(
      screen.getByRole("button", { name: "Delete criterion 1: Downloads as CSV" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Delete criterion 2: Includes archived rows" }),
    ).toBeInTheDocument();
  });

  it("adds the next row on Enter rather than submitting the form", async () => {
    const user = userEvent.setup();
    const submitted = vi.fn();
    onChange.mockClear();
    render(
      <form onSubmit={submitted}>
        <AcceptanceCriteriaList value="First" onChange={onChange} />
      </form>,
    );

    await user.type(screen.getByRole("textbox"), "{Enter}");

    expect(submitted).not.toHaveBeenCalled();
    expect(screen.getAllByRole("textbox")).toHaveLength(2);
  });

  it("emits criteria in the order they are on screen", async () => {
    const user = userEvent.setup();
    onChange.mockClear();
    render(<Harness initial={"First\nSecond"} />);

    // Enter inserts after the focused row, not at the end.
    await user.click(screen.getAllByRole("textbox")[0]);
    await user.keyboard("{Enter}");
    await user.type(screen.getAllByRole("textbox")[1], "Inserted");

    expect(lastValue()).toBe("First\nInserted\nSecond");
  });
});
