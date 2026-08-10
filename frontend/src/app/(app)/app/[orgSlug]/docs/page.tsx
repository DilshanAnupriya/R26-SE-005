"use client";

import { useState } from "react";
import { ChevronRight, FilePlus2, FileText, Folder, FolderPlus, History, Trash2 } from "lucide-react";
import { Card } from "@/components/design/surfaces";
import { StatusPill } from "@/components/design/item-chrome";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  useCreateDocument,
  useDeleteDocument,
  useDocument,
  useDocumentHistory,
  useDocumentTree,
  useRestoreVersion,
  useUpdateDocument,
} from "@/features/documents/hooks";
import { pathTo, statusTone, type DocNode } from "@/features/documents/api";
import { cn } from "@/lib/utils";

export default function DocumentsPage() {
  const tree = useDocumentTree();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  const roots = tree.data?.roots ?? [];
  const breadcrumb = selectedId ? pathTo(roots, selectedId) : [];

  return (
    <div className="flex h-full animate-fade-up flex-col">
      <div className="flex flex-none flex-wrap items-end justify-between gap-4 px-1 pb-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-[-0.02em]">Documents</h1>
          <p className="mt-1 text-base text-ink-3">
            Specs, notes and decisions beside the work — with every edit kept.
          </p>
        </div>
        <NewButtons parentId={null} onCreated={setSelectedId} />
      </div>

      <div className="grid min-h-0 flex-1 gap-3.5 px-1 pb-6 lg:grid-cols-[280px_1fr]">
        <Card className="min-h-0 overflow-auto p-2">
          {tree.isLoading ? (
            <div className="space-y-2 p-1">
              {[0, 1, 2, 3].map((row) => (
                <Skeleton key={row} className="h-7 w-full" />
              ))}
            </div>
          ) : roots.length === 0 ? (
            <p className="p-3 text-sm text-ink-3">
              Nothing yet. A folder groups pages; a page holds words.
            </p>
          ) : (
            <ul role="tree" aria-label="Documents">
              {roots.map((node) => (
                <TreeNode
                  key={node.id}
                  node={node}
                  depth={0}
                  selectedId={selectedId}
                  onSelect={(id) => {
                    setSelectedId(id);
                    setShowHistory(false);
                  }}
                />
              ))}
            </ul>
          )}
        </Card>

        {selectedId ? (
          <Editor
            documentId={selectedId}
            breadcrumb={breadcrumb}
            showHistory={showHistory}
            onToggleHistory={() => setShowHistory((open) => !open)}
            onDeleted={() => setSelectedId(null)}
          />
        ) : (
          <Card className="flex items-center justify-center p-10 text-center">
            <div className="max-w-sm">
              <h2 className="text-lg font-semibold">Choose a page</h2>
              <p className="mt-1.5 text-base text-ink-2">
                Or create one. Every title and body change is kept as a version, so nothing
                anybody wrote is lost to somebody else&apos;s paste.
              </p>
            </div>
          </Card>
        )}
      </div>
    </div>
  );
}

function NewButtons({
  parentId,
  onCreated,
}: {
  parentId: string | null;
  onCreated: (id: string) => void;
}) {
  const create = useCreateDocument();

  return (
    <div className="flex gap-2">
      <Button
        variant="outline"
        size="sm"
        disabled={create.isPending}
        onClick={() =>
          create.mutate(
            { title: "New folder", kind: "FOLDER", parentId: parentId ?? undefined },
            { onSuccess: (created) => onCreated(created.id) },
          )
        }
      >
        <FolderPlus className="size-4" aria-hidden />
        Folder
      </Button>
      <Button
        size="sm"
        disabled={create.isPending}
        onClick={() =>
          create.mutate(
            {
              title: "Untitled page",
              kind: "DOCUMENT",
              parentId: parentId ?? undefined,
              content: "",
            },
            { onSuccess: (created) => onCreated(created.id) },
          )
        }
      >
        <FilePlus2 className="size-4" aria-hidden />
        Page
      </Button>
    </div>
  );
}

function TreeNode({
  node,
  depth,
  selectedId,
  onSelect,
}: {
  node: DocNode;
  depth: number;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const [open, setOpen] = useState(depth === 0);
  const isFolder = node.kind === "FOLDER";
  const selected = node.id === selectedId;

  // A folder toggles; a page opens. Named so click and Enter cannot drift apart —
  // a tree where the keyboard does something different is worse than one with none.
  const activate = () => (isFolder ? setOpen((current) => !current) : onSelect(node.id));

  return (
    <li role="none">
      <div
        role="treeitem"
        aria-selected={selected}
        aria-expanded={isFolder ? open : undefined}
        className={cn(
          "flex cursor-pointer items-center gap-1.5 rounded-md py-1.5 pr-2 text-sm transition-colors",
          selected ? "bg-accent-soft font-medium text-accent-text" : "hover:bg-surface-2",
        )}
        style={{ paddingLeft: 8 + depth * 14 }}
        onClick={activate}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            activate();
          }
        }}
        tabIndex={0}
      >
        {isFolder ? (
          <ChevronRight
            className={cn("size-3.5 flex-none text-ink-3 transition-transform", open && "rotate-90")}
            aria-hidden
          />
        ) : (
          <span className="w-3.5 flex-none" />
        )}
        {isFolder ? (
          <Folder className="size-4 flex-none text-ink-3" aria-hidden />
        ) : (
          <FileText className="size-4 flex-none text-ink-3" aria-hidden />
        )}
        <span className="min-w-0 truncate">{node.title}</span>
        {node.status === "DRAFT" && !isFolder ? (
          <span className="ml-auto text-2xs text-ink-3">draft</span>
        ) : null}
      </div>

      {isFolder && open ? (
        <ul role="group">
          {node.children.length === 0 ? (
            <li
              className="py-1 text-2xs text-ink-3"
              style={{ paddingLeft: 8 + (depth + 1) * 14 + 20 }}
            >
              Empty
            </li>
          ) : (
            node.children.map((child) => (
              <TreeNode
                key={child.id}
                node={child}
                depth={depth + 1}
                selectedId={selectedId}
                onSelect={onSelect}
              />
            ))
          )}
        </ul>
      ) : null}
    </li>
  );
}

function Editor({
  documentId,
  breadcrumb,
  showHistory,
  onToggleHistory,
  onDeleted,
}: {
  documentId: string;
  breadcrumb: DocNode[];
  showHistory: boolean;
  onToggleHistory: () => void;
  onDeleted: () => void;
}) {
  const document = useDocument(documentId);
  const history = useDocumentHistory(showHistory ? documentId : null);
  const update = useUpdateDocument();
  const restore = useRestoreVersion();
  const remove = useDeleteDocument();

  // Draft state is keyed on the document id, so switching pages resets it without an
  // effect. A stale draft written onto a different page is unrecoverable.
  const [draft, setDraft] = useState<{ id: string; title: string; content: string } | null>(null);
  const editing =
    draft && draft.id === documentId
      ? draft
      : {
          id: documentId,
          title: document.data?.title ?? "",
          content: document.data?.content ?? "",
        };

  const dirty =
    document.data != null &&
    (editing.title !== document.data.title || editing.content !== (document.data.content ?? ""));

  if (document.isLoading || !document.data) {
    return <Skeleton className="h-full w-full" />;
  }

  const isFolder = document.data.kind === "FOLDER";

  return (
    <Card className="flex min-h-0 flex-col">
      <div className="flex flex-none flex-wrap items-center gap-2 border-b border-line px-[18px] py-2.5">
        <nav aria-label="Breadcrumb" className="min-w-0 flex-1 truncate text-2xs text-ink-3">
          {breadcrumb.map((node, index) => (
            <span key={node.id}>
              {index > 0 ? <span className="mx-1 opacity-60">/</span> : null}
              {node.title}
            </span>
          ))}
        </nav>
        <StatusPill tone={statusTone(document.data.status)}>
          {document.data.status.toLowerCase()}
        </StatusPill>
        <span className="text-2xs text-ink-3">v{document.data.versionNo}</span>

        {!isFolder ? (
          <Button variant="ghost" size="sm" onClick={onToggleHistory}>
            <History className="size-4" aria-hidden />
            History
          </Button>
        ) : null}
        <Button
          variant="ghost"
          size="sm"
          className="text-ink-3"
          onClick={() =>
            remove.mutate(documentId, { onSuccess: onDeleted })
          }
          aria-label={`Delete ${document.data.title}`}
        >
          <Trash2 className="size-4" aria-hidden />
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-[18px]">
        <Input
          value={editing.title}
          onChange={(event) =>
            setDraft({ id: documentId, title: event.target.value, content: editing.content })
          }
          className="mb-3 h-auto border-0 px-0 text-xl font-semibold shadow-none focus-visible:ring-0"
          aria-label="Title"
        />

        {isFolder ? (
          <div>
            <p className="mb-3 text-sm text-ink-3">
              A folder holds pages; it has no body of its own.
            </p>
            <NewButtons parentId={documentId} onCreated={() => {}} />
          </div>
        ) : (
          <Textarea
            value={editing.content}
            onChange={(event) =>
              setDraft({ id: documentId, title: editing.title, content: event.target.value })
            }
            rows={18}
            placeholder="Markdown. Write the thing somebody will need in three months."
            className="resize-none border-0 px-0 font-mono text-sm shadow-none focus-visible:ring-0"
            aria-label="Content"
          />
        )}

        {showHistory && history.data ? (
          <div className="mt-5 rounded-lg border border-line bg-surface-2 p-3">
            <p className="mb-2 text-2xs font-semibold tracking-[0.05em] text-ink-3 uppercase">
              History
            </p>
            <ul className="space-y-1.5">
              {history.data.map((version) => (
                <li key={version.id} className="flex items-baseline gap-2 text-xs">
                  <span className="font-mono font-medium">v{version.versionNo}</span>
                  <span className="truncate text-ink-2">{version.title}</span>
                  <span className="truncate text-ink-3">{version.summary ?? "No note"}</span>
                  <span className="ml-auto flex-none text-ink-3">
                    {new Intl.DateTimeFormat("en-GB", {
                      day: "numeric",
                      month: "short",
                      hour: "2-digit",
                      minute: "2-digit",
                    }).format(new Date(version.createdAt))}
                  </span>
                  {version.versionNo !== document.data!.versionNo ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 flex-none px-2 text-2xs"
                      onClick={() =>
                        restore.mutate({ documentId, versionNo: version.versionNo })
                      }
                    >
                      Restore
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      <div className="flex flex-none items-center justify-between gap-3 border-t border-line px-[18px] py-2.5">
        <span className="text-2xs text-ink-3">
          {dirty
            ? "Unsaved changes"
            : document.data.updatedByName
              ? `Last edited by ${document.data.updatedByName}`
              : "Saved"}
        </span>
        <div className="flex gap-2">
          {document.data.status === "DRAFT" ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => update.mutate({ documentId, status: "PUBLISHED" })}
            >
              Publish
            </Button>
          ) : null}
          <Button
            size="sm"
            disabled={!dirty || update.isPending}
            onClick={() =>
              update.mutate(
                { documentId, title: editing.title, content: isFolder ? undefined : editing.content },
                { onSuccess: () => setDraft(null) },
              )
            }
          >
            Save
          </Button>
        </div>
      </div>
    </Card>
  );
}
