import { describe, expect, it } from "vitest";
import { defaultOrganization, resolveOrganization } from "./resolve-org";
import type { OrganizationSummary } from "@/lib/api/schemas";

const acme: OrganizationSummary = {
  id: "11111111-1111-4111-8111-111111111111",
  name: "Acme Robotics",
  slug: "acme-robotics",
  logoUrl: null,
  roleCode: "OWNER",
};
const globex: OrganizationSummary = {
  id: "22222222-2222-4222-8222-222222222222",
  name: "Globex Media",
  slug: "globex-media",
  logoUrl: null,
  roleCode: "MEMBER",
};

describe("resolveOrganization", () => {
  it("resolves a slug to its organization", () => {
    const result = resolveOrganization("globex-media", [acme, globex]);

    expect(result.kind).toBe("resolved");
    expect(result.kind === "resolved" && result.organization.id).toBe(globex.id);
  });

  it("matches case-insensitively, since URLs get typed by hand", () => {
    const result = resolveOrganization("ACME-Robotics", [acme, globex]);
    expect(result.kind === "resolved" && result.organization.id).toBe(acme.id);
  });

  it("falls back to the first membership when no slug is given", () => {
    const result = resolveOrganization(undefined, [acme, globex]);
    expect(result.kind === "resolved" && result.organization.id).toBe(acme.id);
  });

  it("distinguishes an unknown slug from having no memberships", () => {
    // Different screens: one offers the orgs you do belong to, the other explains
    // you need an invitation. A shared 404 would lose that.
    const unknown = resolveOrganization("someone-elses-org", [acme]);
    expect(unknown.kind).toBe("unknown-slug");
    expect(unknown.kind === "unknown-slug" && unknown.available).toEqual([acme]);

    expect(resolveOrganization("anything", []).kind).toBe("no-memberships");
  });

  it("reports no memberships even when a slug is absent", () => {
    expect(resolveOrganization(undefined, []).kind).toBe("no-memberships");
  });
});

describe("defaultOrganization", () => {
  it("prefers the organization the session is already scoped to", () => {
    // Landing anywhere else would immediately require an org switch, since the JWT
    // is scoped to this one.
    expect(defaultOrganization([acme, globex], globex.id)).toEqual(globex);
  });

  it("falls back to the first when the active id is unknown", () => {
    expect(defaultOrganization([acme, globex], "33333333-3333-4333-8333-333333333333")).toEqual(acme);
  });

  it("returns null when there are no memberships", () => {
    expect(defaultOrganization([], null)).toBeNull();
  });
});
