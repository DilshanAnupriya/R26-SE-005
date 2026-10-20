import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StoryPointsField } from "./story-points-field";
import type { Story } from "@/lib/api/delivery-schemas";

const estimateMutate = vi.fn();

vi.mock("../hooks", () => ({
  useEstimateStory: () => ({ mutate: estimateMutate, isPending: false }),
}));

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

const STORY_ID = "7c2e6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
const PROJECT_ID = "1a2b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";

const story = (overrides: Partial<Story> = {}) =>
  ({
    id: STORY_ID,
    itemKey: "PAY-7",
    title: "FX rate provider integration",
    storyPoints: null,
    estimateSource: "UNESTIMATED",
    ...overrides,
  }) as Story;

describe("StoryPointsField", () => {
  beforeEach(() => estimateMutate.mockReset());

  it("sizes a story with no AI suggestion anywhere in sight", async () => {
    // The point of this control: the only way to set points used to be "Override" on
    // an AI suggestion, which made the model a gatekeeper for the team's own number.
    render(<StoryPointsField story={story()} projectId={PROJECT_ID} />);

    await userEvent.click(screen.getByRole("button", { name: /Set/ }));
    await userEvent.click(screen.getByRole("button", { name: "8" }));

    expect(estimateMutate).toHaveBeenCalledWith(
      { storyId: STORY_ID, storyPoints: 8 },
      expect.anything(),
    );
  });

  it.each([0, 0.5, 20, 40, 100])("offers %s on the deck and saves it", async (value) => {
    // Past 13 and below 1: the cards a planning session plays for an epic or for work
    // that is barely work, which the scale used to stop short of.
    render(<StoryPointsField story={story()} projectId={PROJECT_ID} />);

    await userEvent.click(screen.getByRole("button", { name: /Set/ }));
    await userEvent.click(screen.getByRole("button", { name: String(value) }));

    expect(estimateMutate).toHaveBeenCalledWith(
      { storyId: STORY_ID, storyPoints: value },
      expect.anything(),
    );
  });

  it("takes a number off the Fibonacci scale for teams that do not use it", async () => {
    render(<StoryPointsField story={story({ storyPoints: 5, estimateSource: "MANUAL" })} projectId={PROJECT_ID} />);

    await userEvent.click(screen.getByRole("button", { name: /Change/ }));
    const input = screen.getByLabelText("Or another number");
    await userEvent.clear(input);
    await userEvent.type(input, "4.5");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(estimateMutate).toHaveBeenCalledWith(
      { storyId: STORY_ID, storyPoints: 4.5 },
      expect.anything(),
    );
  });

  it("says where the current number came from", () => {
    render(
      <StoryPointsField
        story={story({ storyPoints: 8, estimateSource: "AI_ACCEPTED" })}
        projectId={PROJECT_ID}
      />,
    );

    // "8, because someone accepted a model's suggestion" carries different weight in a
    // planning discussion than "8, because the team argued about it".
    expect(screen.getByText("AI suggestion, accepted")).toBeInTheDocument();
  });

  it("shows an em dash rather than a zero for a story nobody has sized", () => {
    // Zero is a real estimate. Printing it for "unknown" would put a number in the
    // sprint total that no one ever agreed to.
    render(<StoryPointsField story={story()} projectId={PROJECT_ID} />);

    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("Not sized yet")).toBeInTheDocument();
  });
});
