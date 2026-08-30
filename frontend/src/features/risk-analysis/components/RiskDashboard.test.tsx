import { render, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import RiskDashboard from "./RiskDashboard";

const PROJECT_A = "aaaaaaaa-0000-4000-8000-000000000001";
const PROJECT_B = "bbbbbbbb-0000-4000-8000-000000000002";
const SPRINT_ACTIVE = "11111111-0000-4000-8000-000000000001";
const SPRINT_PLANNED = "22222222-0000-4000-8000-000000000002";

/**
 * Everything the pickers read, and everything they drove.
 *
 * <p>`vi.hoisted` because `vi.mock` factories are lifted above the file's `const`s and
 * would otherwise close over a binding that is still in its temporal dead zone.</p>
 */
const harness = vi.hoisted(() => ({
  search: "",
  replace: vi.fn(),
  /** Which project the sprint list was asked for, most recent last. */
  sprintsFor: [] as (string | undefined)[],
  /** Which sprint the risk models were asked for, most recent last. */
  riskFor: [] as (string | undefined)[],
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/app/nd/risk",
  useRouter: () => ({ replace: harness.replace, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(harness.search),
}));

vi.mock("@/features/projects/hooks", () => ({
  useProjects: () => ({
    data: {
      content: [
        { id: PROJECT_A, name: "Payments", status: "ACTIVE" },
        { id: PROJECT_B, name: "Onboarding", status: "ACTIVE" },
      ],
    },
    isLoading: false,
  }),
}));

vi.mock("@/features/sprints/hooks", () => ({
  useSprints: (projectId?: string) => {
    harness.sprintsFor.push(projectId);
    return {
      data: {
        content: [
          { id: SPRINT_ACTIVE, name: "Sprint 4", status: "ACTIVE" },
          { id: SPRINT_PLANNED, name: "Sprint 5", status: "PLANNED" },
        ],
      },
      isLoading: false,
    };
  },
}));

vi.mock("../hooks", () => ({
  useSprintRisk: (sprintId?: string) => {
    harness.riskFor.push(sprintId);
    return { data: undefined, isPending: false };
  },
  useRiskAdjustments: () => ({ data: undefined }),
  useDailyRiskTrend: () => ({ data: [], isPending: false }),
  useSprintRequirementChanges: () => ({ data: undefined, isPending: false }),
  useAssessSprintRisk: () => ({ mutate: vi.fn(), isPending: false }),
  useRiskSteps: () => ({ data: undefined }),
  useToggleRiskStep: () => ({ mutate: vi.fn(), isPending: false, variables: undefined }),
  useAbsorbRiskChange: () => ({ mutate: vi.fn(), isPending: false, variables: undefined }),
}));

// The four tab bodies are not what is under test here, and each one drags in charts and
// its own queries. Stubbed so a failure in this file means the pickers are wrong.
vi.mock("./daily-risk-trend", () => ({ DailyRiskTrend: () => <div /> }));
vi.mock("./requirement-changes-panel", () => ({ RequirementChangesPanel: () => <div /> }));
vi.mock("./sprint-compare", () => ({ SprintCompare: () => <div /> }));
vi.mock("./sprint-risk-overview", () => ({ SprintRiskOverview: () => <div /> }));

/**
 * The page as the sprint risk board hands it a sprint.
 *
 * <p>Asserted on the ids the hooks are called with rather than on the two Select
 * triggers. What matters is which sprint is being analysed, and reading that off Radix's
 * rendered value would be a test of Radix.</p>
 */
describe("risk dashboard, arrived at by link", () => {
  beforeEach(() => {
    harness.search = "";
    harness.replace.mockClear();
    harness.sprintsFor.length = 0;
    harness.riskFor.length = 0;
  });

  it("opens on the project and sprint named in the query", async () => {
    harness.search = `?projectId=${PROJECT_B}&sprintId=${SPRINT_PLANNED}`;
    render(<RiskDashboard />);

    await waitFor(() => expect(harness.riskFor.at(-1)).toBe(SPRINT_PLANNED));
    expect(harness.sprintsFor.at(-1)).toBe(PROJECT_B);
    // Neither is the value the page would have picked on its own — the second project
    // in the list, and the sprint that is not the active one.
    expect(harness.riskFor.at(-1)).not.toBe(SPRINT_ACTIVE);
  });

  it("still picks the active sprint when the query names none", async () => {
    render(<RiskDashboard />);

    await waitFor(() => expect(harness.riskFor.at(-1)).toBe(SPRINT_ACTIVE));
    expect(harness.sprintsFor.at(-1)).toBe(PROJECT_A);
  });

  it("writes what it settled on back to the address bar", async () => {
    render(<RiskDashboard />);

    await waitFor(() =>
      expect(harness.replace).toHaveBeenCalledWith(
        `/app/nd/risk?projectId=${PROJECT_A}&sprintId=${SPRINT_ACTIVE}`,
        { scroll: false },
      ),
    );
  });

  it("does not navigate when the address bar already says that", async () => {
    harness.search = `?projectId=${PROJECT_B}&sprintId=${SPRINT_PLANNED}`;
    // jsdom's location, which the effect compares against before replacing.
    window.history.replaceState(null, "", `/app/nd/risk${harness.search}`);

    render(<RiskDashboard />);

    await waitFor(() => expect(harness.riskFor.at(-1)).toBe(SPRINT_PLANNED));
    expect(harness.replace).not.toHaveBeenCalled();
  });
});
