import { beforeEach, describe, expect, it, vi } from "vitest";
import { render as renderBare, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EstimatePanel } from "./estimate-panel";
import { TooltipProvider } from "@/components/ui/tooltip";
import type { Story } from "@/lib/api/delivery-schemas";

/** The prediction meta line uses a tooltip, which the app provides at the root. */
const render = (ui: React.ReactElement) =>
  renderBare(<TooltipProvider>{ui}</TooltipProvider>);

const submitFeedback = vi.fn();
const runEstimate = vi.fn();

// State the mocked mutations report back. Set per test to stand for "a suggestion
// has just been produced for story X".
let estimateData: unknown = undefined;

vi.mock("../hooks", () => ({
  useEstimateStory: () => ({ data: estimateData, mutate: runEstimate, isPending: false }),
  useSubmitFeedback: () => ({ mutateAsync: submitFeedback, isPending: false }),
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

const STORY_A = "3f1b6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
const STORY_B = "7c2e6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
const PREDICTION_A = "9a4d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";

const story = (overrides: Partial<Story> = {}) =>
  ({
    id: STORY_B,
    itemKey: "PAY-7",
    title: "FX rate provider integration",
    storyPoints: 13,
    estimateSource: "MANUAL",
    aiEstimate: null,
    ...overrides,
  }) as Story;

/** A suggestion as the estimate mutation returns it. */
const suggestionFor = (storyId: string, points: number, predictionId: string) => ({
  storyId,
  itemKey: "PAY-5",
  title: "SCIM user provisioning",
  currentPoints: points,
  suggestedPoints: points,
  prediction: {
    predictionId,
    modelCode: "M1_STORY_POINTS",
    modelVersion: "2.0.0",
    value: points,
    lowerBound: 2,
    upperBound: points,
    confidence: 0.6,
    drivers: [],
    explanation: `Estimated ${points} points.`,
    degraded: false,
  },
});

describe("EstimatePanel", () => {
  beforeEach(() => {
    estimateData = undefined;
    submitFeedback.mockReset().mockResolvedValue({});
    runEstimate.mockReset();
  });

  it("ignores a suggestion produced for a different story", () => {
    // The regression: the estimate mutation's result outlives the story it was run
    // for. Selecting a second story showed the first story's number under the second
    // story's title, and Accept then wrote those points back to the first story — the
    // request returned 200 and nothing on screen changed, so the button looked dead.
    estimateData = suggestionFor(STORY_A, 5, PREDICTION_A);

    render(<EstimatePanel story={story()} />);

    // No suggestion for this story, so the panel offers to make one.
    expect(screen.getByRole("button", { name: /Suggest/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Accept/ })).not.toBeInTheDocument();
  });

  it("accepts against the prediction belonging to the story on screen", async () => {
    estimateData = suggestionFor(STORY_B, 5, PREDICTION_A);

    render(<EstimatePanel story={story()} />);
    await userEvent.click(screen.getByRole("button", { name: /Accept 5/ }));

    expect(submitFeedback).toHaveBeenCalledWith(
      expect.objectContaining({
        predictionId: PREDICTION_A,
        action: "ACCEPTED",
        storyId: STORY_B,
      }),
    );
  });

  it("says the estimate was applied, so an unchanged number is not mistaken for a dead button", async () => {
    // Accepting a suggestion equal to the story's current points changes nothing else
    // on screen. Without an acknowledgement the click is indistinguishable from a
    // failure, which is exactly how this was reported.
    estimateData = suggestionFor(STORY_B, 13, PREDICTION_A);

    render(<EstimatePanel story={story({ storyPoints: 13 })} />);
    await userEvent.click(screen.getByRole("button", { name: /Accept 13/ }));

    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("PAY-7 is 13 points"),
    );
    // And it stops offering the verdict it has already recorded.
    expect(screen.queryByRole("button", { name: /Accept 13/ })).not.toBeInTheDocument();
  });

  it("reports a failed verdict instead of swallowing the click", async () => {
    estimateData = suggestionFor(STORY_B, 5, PREDICTION_A);
    submitFeedback.mockRejectedValue(new Error("Prediction not found"));

    render(<EstimatePanel story={story()} />);
    await userEvent.click(screen.getByRole("button", { name: /Accept 5/ }));

    const { toast } = await import("sonner");
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("Prediction not found"));
    // The buttons stay, because nothing was recorded.
    expect(screen.getByRole("button", { name: /Accept 5/ })).toBeInTheDocument();
  });

  it("runs the model again on request, because a story's text keeps changing", async () => {
    estimateData = suggestionFor(STORY_B, 5, PREDICTION_A);

    render(<EstimatePanel story={story()} />);
    await userEvent.click(screen.getByRole("button", { name: /Re-estimate/ }));

    expect(runEstimate).toHaveBeenCalledWith(STORY_B);
  });

  it("drops the recorded verdict when a fresh estimate is asked for", async () => {
    // The confirmation describes the number that was just applied. Leaving it up
    // beside a newly requested estimate would attribute the old verdict to the new
    // suggestion.
    estimateData = suggestionFor(STORY_B, 5, PREDICTION_A);

    render(<EstimatePanel story={story()} />);
    await userEvent.click(screen.getByRole("button", { name: /Accept 5/ }));
    await waitFor(() => expect(screen.getByRole("status")).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: /Re-estimate/ }));

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(runEstimate).toHaveBeenCalledWith(STORY_B);
  });

  it("overrides with the number the user typed", async () => {
    estimateData = suggestionFor(STORY_B, 5, PREDICTION_A);

    render(<EstimatePanel story={story()} />);
    await userEvent.click(screen.getByRole("button", { name: "Override" }));

    const input = screen.getByLabelText("Your estimate");
    await userEvent.clear(input);
    await userEvent.type(input, "8");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(submitFeedback).toHaveBeenCalledWith(
      expect.objectContaining({ action: "OVERRIDDEN", finalValue: 8 }),
    );
  });
});
