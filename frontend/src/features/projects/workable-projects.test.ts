import { describe, expect, it } from "vitest";
import { workableProjects } from "./api";

const project = (projectKey: string, status: string) => ({ projectKey, status });

/**
 * Which projects a picker offers.
 *
 * <p>This exists because of a real bug: the Backlog and Board pickers queried
 * <code>status=ACTIVE</code>, but a project is created as PLANNING. Every project was
 * therefore invisible the moment it was made, and the screen said "No active projects
 * yet" while the Projects page listed two — with nothing to explain the contradiction
 * or hint that a status change was the way out.</p>
 *
 * <p>The rule mirrors {@code ProjectStatus.isTerminal()} on the backend: finished means
 * COMPLETED or ARCHIVED, and everything else is workable.</p>
 */
describe("workableProjects", () => {
  it("includes a newly created project, which starts as PLANNING", () => {
    // The regression itself. A brand-new project is the one you are most likely to be
    // opening a backlog for, so it must be the first thing the picker offers.
    expect(workableProjects([project("BILL", "PLANNING")]).map((p) => p.projectKey)).toEqual([
      "BILL",
    ]);
  });

  it("includes every status a team can still work in", () => {
    const open = [
      project("PLA", "PLANNING"),
      project("ACT", "ACTIVE"),
      project("HLD", "ON_HOLD"),
    ];
    expect(workableProjects(open)).toHaveLength(3);
  });

  it("leaves out projects that are finished with", () => {
    const all = [
      project("ACT", "ACTIVE"),
      project("DONE", "COMPLETED"),
      project("OLD", "ARCHIVED"),
    ];
    expect(workableProjects(all).map((p) => p.projectKey)).toEqual(["ACT"]);
  });

  it("returns nothing when every project really is finished", () => {
    // The empty state is then truthful rather than a filtering accident.
    expect(workableProjects([project("DONE", "COMPLETED")])).toEqual([]);
  });

  it("passes an unknown status through rather than hiding the project", () => {
    // A status this build has not heard of is far more likely to be a new workable
    // one than a new terminal one, and hiding a project people can see elsewhere is
    // the failure this whole helper exists to prevent.
    expect(workableProjects([project("NEW", "SOMETHING_ELSE")])).toHaveLength(1);
  });
});
