import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";

export const documentKindSchema = z.enum(["FOLDER", "DOCUMENT"]);
export type DocumentKind = z.infer<typeof documentKindSchema>;

export const documentStatusSchema = z.enum(["DRAFT", "PUBLISHED", "ARCHIVED"]);
export type DocumentStatus = z.infer<typeof documentStatusSchema>;

export const documentSchema = z.object({
  id: z.uuid(),
  parentId: z.uuid().nullish(),
  projectId: z.uuid().nullish(),
  kind: documentKindSchema,
  title: z.string(),
  content: z.string().nullish(),
  status: documentStatusSchema,
  position: z.number(),
  versionNo: z.number(),
  updatedBy: z.uuid().nullish(),
  updatedByName: z.string().nullish(),
  createdAt: z.string().nullish(),
  updatedAt: z.string().nullish(),
});
export type Doc = z.infer<typeof documentSchema>;

/** Recursive, because the tree is. Typed explicitly since Zod cannot infer a cycle. */
export type DocNode = {
  id: string;
  parentId?: string | null;
  kind: DocumentKind;
  title: string;
  status: DocumentStatus;
  position: number;
  updatedAt?: string | null;
  children: DocNode[];
};

export const docNodeSchema: z.ZodType<DocNode> = z.lazy(() =>
  z.object({
    id: z.uuid(),
    parentId: z.uuid().nullish(),
    kind: documentKindSchema,
    title: z.string(),
    status: documentStatusSchema,
    position: z.number(),
    updatedAt: z.string().nullish(),
    children: z.array(docNodeSchema).default([]),
  }),
);

export const treeSchema = z.object({
  roots: z.array(docNodeSchema).default([]),
  totalDocuments: z.number(),
  totalFolders: z.number(),
});
export type DocTree = z.infer<typeof treeSchema>;

export const versionSchema = z.object({
  id: z.uuid(),
  versionNo: z.number(),
  title: z.string(),
  summary: z.string().nullish(),
  authoredBy: z.uuid().nullish(),
  authoredByName: z.string().nullish(),
  createdAt: z.string(),
  content: z.string().nullish(),
});
export type DocVersion = z.infer<typeof versionSchema>;

export const documentsApi = {
  tree: (api: ApiClient, projectId?: string) =>
    api.get("/documents", { schema: treeSchema, query: { projectId } }),

  get: (api: ApiClient, documentId: string) =>
    api.get(`/documents/${documentId}`, { schema: documentSchema }),

  history: (api: ApiClient, documentId: string) =>
    api.get(`/documents/${documentId}/versions`, { schema: z.array(versionSchema) }),

  create: (
    api: ApiClient,
    body: {
      title: string;
      kind?: DocumentKind;
      parentId?: string;
      projectId?: string;
      content?: string;
    },
  ) => api.post("/documents", body, { schema: documentSchema }),

  update: (
    api: ApiClient,
    documentId: string,
    body: {
      title?: string;
      content?: string;
      status?: DocumentStatus;
      parentId?: string;
      summary?: string;
    },
  ) => api.patch(`/documents/${documentId}`, body, { schema: documentSchema }),

  restore: (api: ApiClient, documentId: string, versionNo: number) =>
    api.post(`/documents/${documentId}/versions/${versionNo}/restore`, {}, {
      schema: documentSchema,
    }),

  remove: (api: ApiClient, documentId: string) => api.delete(`/documents/${documentId}`),
};

export function statusTone(status: DocumentStatus): "green" | "amber" | "neutral" {
  switch (status) {
    case "PUBLISHED":
      return "green";
    case "ARCHIVED":
      return "neutral";
    default:
      return "amber";
  }
}

/** Flattens the tree into the order it is drawn, for keyboard navigation and search. */
export function flatten(nodes: DocNode[], depth = 0): { node: DocNode; depth: number }[] {
  return nodes.flatMap((node) => [
    { node, depth },
    ...flatten(node.children, depth + 1),
  ]);
}

/**
 * The path from the root to a node, for a breadcrumb.
 *
 * <p>Walked from the tree rather than stored on the row. A stored path is a second
 * copy of the parent chain, and moving one folder would have to rewrite every
 * descendant to keep it true.</p>
 */
export function pathTo(nodes: DocNode[], id: string): DocNode[] {
  for (const node of nodes) {
    if (node.id === id) return [node];
    const below = pathTo(node.children, id);
    if (below.length > 0) return [node, ...below];
  }
  return [];
}
