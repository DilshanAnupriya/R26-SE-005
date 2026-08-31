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
const suggestionFor = (
  storyId: string,
  points: number,
  predictionId: string,
  extra: { rawValue?: number; context?: unknown } = {},
) => ({
  storyId,
  itemKey: "PAY-5",
  title: "SCIM user provisioning",
  currentPoints: points,
  suggestedPoints: points,
  context: extra.context ?? null,
  prediction: {
    predictionId,
    modelCode: "M1_STORY_POINTS",
    modelVersion: "2.0.0",
    value: points,
    lowerBound: 2,
    upperBound: points,
    confidence: 0.6,
    rawValue: extra.rawValue ?? null,
    drivers: [],
    explanation: `Estimated ${points} points.`,
    degraded: false,
  },
});

/** The context block as the service reports it, with nothing missing by default. */
const contextOf = (defaulted: string[] = []) => ({
  teamSize: 6,
  devExperienceLevel: "mid",
  domain: "ecommerce",
  techStack: "react",
  defaulted,
  inferredFromText: [],
  textTermsRecognised: 12,
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

  // ------------------------------------------------------- the pre-snap number

  it("shows the model's own number even when it sits mid-band", () => {
    // This used to appear only in the outer third of a Fibonacci band, so the line
    // came and went between one story and the next and nobody learned to look for
    // it. 8.4 is well inside the band and still not what the model said.
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, { rawValue: 8.4 });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText(/model said 8\.4/)).toBeInTheDocument();
    expect(screen.getByText(/rounded down to 8/)).toBeInTheDocument();
  });

  it("calls a raw 6.67 a low 8 rather than reporting it as a 5", () => {
    // The number is nearer 8 than 5, which is why it snapped to 8. Describing it as
    // "close to 5" would invite the reader to conclude the model wanted 5 and was
    // overruled, which is the opposite of what happened.
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, { rawValue: 6.67 });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText(/model said 6\.67 — a low 8/)).toBeInTheDocument();
  });

  // ------------------------------------------------------ the project's context

  it("names the team context the estimate was scored against", () => {
    // The complaint this answers: four drivers, all about the story's own words, and
    // no sign that the model knew anything about the team. It does — team size just
    // cannot rank in a per-story breakdown, because it is identical for every story.
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, { context: contextOf() });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText("team of 6")).toBeInTheDocument();
    expect(screen.getByText("Mostly mid-level")).toBeInTheDocument();
    expect(screen.getByText("E-commerce")).toBeInTheDocument();
    expect(screen.getByText("React")).toBeInTheDocument();
  });

  it("says which inputs the model had to invent, and where to fix them", () => {
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, {
      context: contextOf(["team_size", "tech_stack"]),
    });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText("team size: typical")).toBeInTheDocument();
    expect(screen.getByText("tech stack: typical")).toBeInTheDocument();
    expect(screen.getByText(/team size and tech stack/)).toBeInTheDocument();
    expect(screen.getByText(/Estimation context/)).toBeInTheDocument();
  });

  it("reports a defaulted input the project appears to have filled in", () => {
    // The case worth catching: the model one-hot encodes ten tech stacks, so a
    // project set to anything else has a populated field that scores as an empty
    // one. Reading the nulls would call this project fully specified.
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, {
      context: { ...contextOf(["tech_stack"]), techStack: "laravel" },
    });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText("tech stack: typical")).toBeInTheDocument();
    expect(screen.queryByText("laravel")).not.toBeInTheDocument();
  });

  it("keeps measured inputs out of the fix-this warning", () => {
    // Velocity is computed from closed sprints, so telling somebody to go and supply
    // it points at a form that does not exist.
    estimateData = suggestionFor(STORY_B, 8, PREDICTION_A, {
      context: contextOf(["team_velocity_avg"]),
    });

    render(<EstimatePanel story={story()} />);

    expect(screen.getByText(/no reading for team velocity/)).toBeInTheDocument();
    expect(screen.queryByText(/Estimation context/)).not.toBeInTheDocument();
  });

  it("shows no context for a suggestion restored from the story", () => {
    // A stored suggestion records the number, not the project as it stood when the
    // number was made. Captioning it with the project's current fields would claim
    // it was scored against context it may never have seen.
    render(
      <EstimatePanel
        story={story({ aiEstimate: { points: 8, confidence: 0.6 } } as Partial<Story>)}
      />,
    );

    expect(screen.queryByText(/Sized against/)).not.toBeInTheDocument();
  });
});
