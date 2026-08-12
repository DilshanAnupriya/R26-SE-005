import { describe, expect, it } from "vitest";
import { flatten, pathTo, statusTone, treeSchema, type DocNode } from "./api";

const node = (id: string, title: string, children: DocNode[] = []): DocNode => ({
  id,
  kind: children.length > 0 ? "FOLDER" : "DOCUMENT",
  title,
  status: "PUBLISHED",
  position: 0,
  children,
});

const TREE: DocNode[] = [
  node("11111111-1111-4111-8111-111111111111", "Engineering", [
    node("22222222-2222-4222-8222-222222222222", "Runbooks", [
      node("33333333-3333-4333-8333-333333333333", "Incident runbook"),
    ]),
    node("44444444-4444-4444-8444-444444444444", "On-call"),
  ]),
  node("55555555-5555-4555-8555-555555555555", "Handbook"),
];

/**
 * The breadcrumb is walked from the tree rather than stored on the row.
 *
 * <p>A stored path is a second copy of the parent chain, so moving one folder means
 * rewriting every descendant to keep it true — and the day that rewrite is missed,
 * the breadcrumb points somewhere the page no longer is.</p>
 */
describe("pathTo", () => {
  it("returns the chain from the root to a nested page", () => {
    const path = pathTo(TREE, "33333333-3333-4333-8333-333333333333");
    expect(path.map((entry) => entry.title)).toEqual([
      "Engineering",
      "Runbooks",
      "Incident runbook",
    ]);
  });

  it("returns just the node for a root-level page", () => {
    expect(pathTo(TREE, "55555555-5555-4555-8555-555555555555").map((n) => n.title)).toEqual([
      "Handbook",
    ]);
  });

  it("returns nothing for an id that is not in the tree", () => {
    expect(pathTo(TREE, "99999999-9999-4999-8999-999999999999")).toEqual([]);
  });
});

describe("flatten", () => {
  it("returns the tree in drawn order with each node's depth", () => {
    expect(flatten(TREE).map((entry) => [entry.node.title, entry.depth])).toEqual([
      ["Engineering", 0],
      ["Runbooks", 1],
      ["Incident runbook", 2],
      ["On-call", 1],
      ["Handbook", 0],
    ]);
  });
});

describe("treeSchema", () => {
  it("parses a nested tree, which needs the schema to be recursive", () => {
    const parsed = treeSchema.parse({
      roots: [
        {
          id: "11111111-1111-4111-8111-111111111111",
          kind: "FOLDER",
          title: "Engineering",
          status: "PUBLISHED",
          position: 0,
          children: [
            {
              id: "44444444-4444-4444-8444-444444444444",
              kind: "DOCUMENT",
              title: "On-call",
              status: "DRAFT",
              position: 0,
              children: [],
            },
          ],
        },
      ],
      totalDocuments: 1,
      totalFolders: 1,
    });

    expect(parsed.roots[0].children[0].title).toBe("On-call");
  });

  it("defaults a missing children array rather than failing the page", () => {
    // A leaf may arrive without the key at all; Jackson omits empty collections in
    // places and a strict schema would blank the whole tree over it.
    const parsed = treeSchema.parse({
      roots: [
        {
          id: "55555555-5555-4555-8555-555555555555",
          kind: "DOCUMENT",
          title: "Handbook",
          status: "PUBLISHED",
          position: 0,
        },
      ],
      totalDocuments: 1,
      totalFolders: 0,
    });

    expect(parsed.roots[0].children).toEqual([]);
  });
});

describe("statusTone", () => {
  it("marks a draft as unfinished rather than as normal", () => {
    expect(statusTone("DRAFT")).toBe("amber");
    expect(statusTone("PUBLISHED")).toBe("green");
    expect(statusTone("ARCHIVED")).toBe("neutral");
  });
});
