import { describe, expect, it } from "vitest";
import { destinations } from "./client";

/**
 * Destinations are the tenancy boundary on the websocket.
 *
 * <p>The server refuses a SUBSCRIBE whose organization segment is not the caller's, so a
 * destination built with the ids in the wrong order does not leak — it silently
 * subscribes to nothing, and the board stops updating with no error anywhere. These
 * assertions pin the shape the server's parser expects.</p>
 */
describe("realtime destinations", () => {
  const organizationId = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b";
  const projectId = "11111111-2222-4333-8444-555555555555";

  it("puts the organization first, where the server's check reads it", () => {
    const destination = destinations.project(organizationId, projectId);

    expect(destination).toBe(`/topic/org.${organizationId}.project.${projectId}`);
    // The server takes everything between "/topic/org." and the next dot.
    expect(destination.slice("/topic/org.".length).split(".")[0]).toBe(organizationId);
  });

  it("scopes sprint topics the same way", () => {
    const sprintId = "99999999-8888-4777-8666-555555555555";
    const destination = destinations.sprint(organizationId, sprintId);

    expect(destination).toBe(`/topic/org.${organizationId}.sprint.${sprintId}`);
    expect(destination.slice("/topic/org.".length).split(".")[0]).toBe(organizationId);
  });

  it("uses a user destination for notifications, carrying no ids", () => {
    // Spring routes this against the authenticated principal, so an id in the path
    // would be both redundant and a way to ask for someone else's inbox.
    expect(destinations.notifications()).toBe("/user/queue/notifications");
    expect(destinations.notifications()).not.toContain("org.");
  });
});
