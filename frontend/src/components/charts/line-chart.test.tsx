import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LineChart } from ".";

/**
 * jsdom lays nothing out, so every box is zero-sized and the pointer maths has
 * nothing to divide by. The chart reads the box to convert a client x into a
 * viewBox x; the stub gives it the 640-wide box the viewBox assumes, so a clientX
 * is a viewBox x directly and the assertions can name a point by its coordinate.
 */
function stubChartBox() {
  const original = Element.prototype.getBoundingClientRect;
  Element.prototype.getBoundingClientRect = function stubbed() {
    return { x: 0, y: 0, width: 640, height: 260, top: 0, left: 0, right: 640, bottom: 260, toJSON: () => ({}) } as DOMRect;
  };
  return () => {
    Element.prototype.getBoundingClientRect = original;
  };
}

const LABELS = ["01 Sep", "02 Sep", "03 Sep", "04 Sep", "05 Sep"];

const SERIES = [
  { label: "Overall", color: "red", points: [10, 20, 30, null, null] },
  { label: "Over-budget", color: "blue", points: [40, 50, 60, null, null] },
];

afterEach(() => {
  vi.restoreAllMocks();
});

describe("LineChart x-axis", () => {
  it("draws a tick for every point when asked", () => {
    const { container } = render(<LineChart labels={LABELS} series={SERIES} showXAxis />);
    const ticks = [...container.querySelectorAll("svg text")].map((node) => node.textContent);
    for (const label of LABELS) {
      expect(ticks).toContain(label);
    }
  });

  it("leaves the axis bare by default, so existing charts are unchanged", () => {
    const { container } = render(<LineChart labels={LABELS} series={SERIES} />);
    const ticks = [...container.querySelectorAll("svg text")].map((node) => node.textContent);
    expect(ticks).not.toContain("01 Sep");
  });
});

describe("LineChart gaps", () => {
  it("breaks the line at a gap rather than drawing through it", () => {
    const holed = [{ label: "Overall", color: "red", points: [10, null, 30, 40, null] }];
    const { container } = render(<LineChart labels={LABELS} series={holed} />);
    const paths = [...container.querySelectorAll("path")];
    // One run of two points either side of the hole; the lone first point is a dot.
    expect(paths).toHaveLength(1);
    expect(container.querySelectorAll("circle")).toHaveLength(1);
  });

  it("starts a path with a move even when the series opens with a gap", () => {
    const late = [{ label: "Overall", color: "red", points: [null, null, 30, 40, 50] }];
    const { container } = render(<LineChart labels={LABELS} series={late} />);
    const path = container.querySelector("path");
    expect(path?.getAttribute("d")).toMatch(/^M/);
  });
});

describe("LineChart hover readout", () => {
  it("names every series with its value at the hovered point", () => {
    const restore = stubChartBox();
    try {
      const { container } = render(
        <LineChart labels={LABELS} series={SERIES} hover valueSuffix="%" />,
      );
      const svg = container.querySelector("svg");
      // x of index 1 is 36 (left inset) + 1 * (592 / 4) = 184.
      fireEvent.pointerMove(svg!, { clientX: 184 });

      const readout = screen.getByRole("status");
      expect(within(readout).getByText("02 Sep")).toBeInTheDocument();
      expect(within(readout).getByText("Overall")).toBeInTheDocument();
      expect(within(readout).getByText("20%")).toBeInTheDocument();
      expect(within(readout).getByText("Over-budget")).toBeInTheDocument();
      expect(within(readout).getByText("50%")).toBeInTheDocument();
    } finally {
      restore();
    }
  });

  it("lists an unscored series as a dash rather than dropping it", () => {
    const restore = stubChartBox();
    try {
      const { container } = render(
        <LineChart labels={LABELS} series={SERIES} hover valueSuffix="%" />,
      );
      const svg = container.querySelector("svg");
      // x of index 4 is 36 + 4 * 148 = 628 — a day neither series scored.
      fireEvent.pointerMove(svg!, { clientX: 628 });

      const readout = screen.getByRole("status");
      expect(within(readout).getByText("05 Sep")).toBeInTheDocument();
      expect(within(readout).getByText("Overall")).toBeInTheDocument();
      expect(within(readout).getAllByText("—")).toHaveLength(2);
    } finally {
      restore();
    }
  });

  it("clears the readout when the cursor leaves", () => {
    const restore = stubChartBox();
    try {
      const { container } = render(<LineChart labels={LABELS} series={SERIES} hover />);
      const svg = container.querySelector("svg");
      fireEvent.pointerMove(svg!, { clientX: 184 });
      expect(screen.getByRole("status")).toBeInTheDocument();

      fireEvent.pointerLeave(svg!);
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    } finally {
      restore();
    }
  });

  it("stays inert without the hover prop, so existing charts are unchanged", () => {
    const restore = stubChartBox();
    try {
      const { container } = render(<LineChart labels={LABELS} series={SERIES} />);
      fireEvent.pointerMove(container.querySelector("svg")!, { clientX: 184 });
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    } finally {
      restore();
    }
  });
});
