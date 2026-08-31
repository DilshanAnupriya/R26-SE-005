import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { LabelPicker } from "./label-picker";

const createLabel = vi.fn();

let labelList: { id: string; name: string; color: string | null }[] = [];
let creating = false;

vi.mock("@/features/projects/hooks", () => ({
  useProjectLabels: () => ({ data: labelList, isPending: false }),
  useCreateLabel: () => ({ mutate: createLabel, isPending: creating, isError: false }),
}));

const PROJECT = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
const PAYMENTS = "2b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
const DESIGN = "3b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";

/** Holds the selection the way the create dialog does, so toggling is observable. */
function Harness({ initial = [] as string[] }) {
  const [selected, setSelected] = useState<string[]>(initial);
  return (
    <>
      <LabelPicker projectId={PROJECT} selected={selected} onChange={setSelected} />
      <output>{selected.join(",")}</output>
    </>
  );
}

describe("LabelPicker", () => {
  beforeEach(() => {
    createLabel.mockReset();
    creating = false;
    labelList = [
      { id: PAYMENTS, name: "payments", color: "#5b5cf0" },
      { id: DESIGN, name: "needs-design", color: null },
    ];
  });

  it("adds and removes a label from the selection", async () => {
    render(<Harness />);

    await userEvent.click(screen.getByRole("button", { name: "payments" }));
    expect(screen.getByRole("status")).toHaveTextContent(PAYMENTS);

    await userEvent.click(screen.getByRole("button", { name: "payments" }));
    expect(screen.getByRole("status")).toHaveTextContent("");
  });

  it("reports selection state to assistive technology", () => {
    render(<Harness initial={[DESIGN]} />);

    expect(screen.getByRole("button", { name: "needs-design" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "payments" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("creates a new label and selects it", async () => {
    // The picker is useless on a fresh project without this: nothing else in the
    // product creates a label, so an empty list would be a permanently empty list.
    const created = { id: "4b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed", name: "billing" };
    createLabel.mockImplementation((_body, options) => options?.onSuccess?.(created));

    render(<Harness />);
    await userEvent.type(screen.getByLabelText("New label name"), "billing");
    await userEvent.click(screen.getByRole("button", { name: /Add/ }));

    expect(createLabel).toHaveBeenCalledWith(
      expect.objectContaining({ name: "billing" }),
      expect.anything(),
    );
    expect(screen.getByRole("status")).toHaveTextContent(created.id);
  });

  it("selects an existing label instead of failing on a duplicate name", async () => {
    // Somebody reaching for the box has not necessarily read the pills above it, and
    // a 409 is a worse answer than doing the obvious thing.
    render(<Harness />);
    await userEvent.type(screen.getByLabelText("New label name"), "Payments");
    await userEvent.click(screen.getByRole("button", { name: /Select/ }));

    expect(createLabel).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent(PAYMENTS);
  });

  it("does not submit the surrounding form when Enter creates a label", async () => {
    // This lives inside the create-story form, where the default Enter behaviour
    // would file a half-written backlog item.
    const submit = vi.fn((event: React.FormEvent) => event.preventDefault());
    createLabel.mockImplementation((_body, options) =>
      options?.onSuccess?.({ id: "5b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed", name: "api" }),
    );

    render(
      <form onSubmit={submit}>
        <Harness />
      </form>,
    );

    await userEvent.type(screen.getByLabelText("New label name"), "api{Enter}");

    expect(submit).not.toHaveBeenCalled();
    expect(createLabel).toHaveBeenCalledWith(
      expect.objectContaining({ name: "api" }),
      expect.anything(),
    );
  });

  it("will not create a label from whitespace", async () => {
    render(<Harness />);
    await userEvent.type(screen.getByLabelText("New label name"), "   ");

    expect(screen.getByRole("button", { name: /Add/ })).toBeDisabled();
  });
});
