"use client";

import { use, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, ScrollText, Sparkles, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { adminApi } from "@/features/admin/api";
import { aiApi } from "@/features/ai/api";
import { useAiModels } from "@/features/ai/hooks";

/**
 * The admin console.
 *
 * <p>Four things an administrator actually does: switch AI capabilities on and off,
 * check what the models are doing, recover something someone deleted, and read the audit
 * trail. Each is its own tab and loads only when opened.</p>
 */
export default function SettingsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const { hasPermission } = useSession();

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Administration</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Capabilities, model health, recovery and the audit trail.
        </p>
      </div>

      <Tabs defaultValue="ai">
        <TabsList>
          <TabsTrigger value="ai">AI models</TabsTrigger>
          <TabsTrigger value="flags">Capabilities</TabsTrigger>
          <TabsTrigger value="recycle">Recycle bin</TabsTrigger>
          {hasPermission("audit:read") ? <TabsTrigger value="audit">Audit</TabsTrigger> : null}
        </TabsList>

        <TabsContent value="ai" className="mt-4">
          <ModelsPanel />
        </TabsContent>
        <TabsContent value="flags" className="mt-4">
          <FlagsPanel />
        </TabsContent>
        <TabsContent value="recycle" className="mt-4">
          <RecycleBinPanel />
        </TabsContent>
        {hasPermission("audit:read") ? (
          <TabsContent value="audit" className="mt-4">
            <AuditPanel />
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}

/**
 * Model registry with rolling usage.
 *
 * <p>Acceptance sits next to the toggle deliberately. The decision on this screen is
 * "should this stay on", and a switch with no acceptance rate beside it turns that into
 * a guess.</p>
 */
function ModelsPanel() {
  const { api, hasPermission } = useSession();
  const models = useAiModels();
  const queryClient = useQueryClient();

  const toggle = useMutation({
    mutationFn: (input: { code: string; enabled: boolean }) =>
      aiApi.updateModel(api, input.code, { enabled: input.enabled }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.models() });
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not update that model.");
    },
  });

  if (models.isPending) return <Skeleton className="h-72 w-full" />;
  if (models.error) return <ErrorState error={models.error} onRetry={() => void models.refetch()} />;

  const canManage = hasPermission("ai:manage");

  return (
    <div className="space-y-3">
      <p className="text-xs text-muted-foreground">
        The registry is platform-wide — disabling a model here affects every workspace. To
        turn a capability off for one workspace only, use Capabilities.
      </p>

      <ul className="divide-y rounded-lg border">
        {(models.data ?? []).map((model) => (
          <li key={model.code} className="p-3">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">{model.name}</span>
                  <Badge variant="outline" className="font-mono text-[10px]">
                    {model.code} v{model.version}
                  </Badge>
                  {!model.flagEnabled ? (
                    <Badge variant="secondary">off for this workspace</Badge>
                  ) : null}
                </p>
                {model.description ? (
                  <p className="mt-0.5 text-xs text-muted-foreground">{model.description}</p>
                ) : null}

                {model.usage ? (
                  <p className="mt-1.5 flex flex-wrap gap-3 text-[11px] text-muted-foreground tabular-nums">
                    <span>{model.usage.predictions} predictions / 30d</span>
                    <span>
                      {model.usage.acceptanceRate != null
                        ? `${model.usage.acceptanceRate}% accepted`
                        : "no verdicts yet"}
                    </span>
                    {model.usage.meanAbsoluteError != null ? (
                      <span>mean error {model.usage.meanAbsoluteError}</span>
                    ) : null}
                    {model.usage.failures > 0 ? (
                      <span className="text-destructive">{model.usage.failures} failed</span>
                    ) : null}
                  </p>
                ) : null}
              </div>

              <label className="flex shrink-0 items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={model.enabled}
                  disabled={!canManage || toggle.isPending}
                  onChange={(event) =>
                    toggle.mutate({ code: model.code, enabled: event.target.checked })
                  }
                />
                <span>{model.enabled ? "Enabled" : "Disabled"}</span>
              </label>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

function FlagsPanel() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  const workspaces = useQuery({
    queryKey: queryKeys.workspaces.list(),
    queryFn: () => adminApi.workspaces(api),
  });

  const [chosenWorkspaceId, setChosenWorkspaceId] = useState<string | null>(null);
  const workspaceId = chosenWorkspaceId ?? workspaces.data?.[0]?.id ?? null;

  const flags = useQuery({
    queryKey: queryKeys.settings.featureFlags(workspaceId ?? ""),
    queryFn: () => adminApi.featureFlags(api, workspaceId!),
    enabled: Boolean(workspaceId),
  });

  const setFlag = useMutation({
    mutationFn: (input: { flagKey: string; enabled: boolean }) =>
      adminApi.setFeatureFlag(api, workspaceId!, input.flagKey, input.enabled),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.settings.featureFlags(workspaceId ?? ""),
      });
      // Insights name their disabled capabilities, so that page is now wrong too.
      void queryClient.invalidateQueries({ queryKey: queryKeys.ai.all });
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not change that setting.");
    },
  });

  if (workspaces.isPending) return <Skeleton className="h-48 w-full" />;
  if (!workspaceId) return <EmptyState title="No workspaces" />;

  return (
    <div className="space-y-3">
      {workspaces.data && workspaces.data.length > 1 ? (
        <label className="text-xs">
          <span className="sr-only">Workspace</span>
          <select
            className="h-8 rounded-md border bg-background px-2 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            value={workspaceId}
            onChange={(event) => setChosenWorkspaceId(event.target.value)}
          >
            {workspaces.data.map((workspace) => (
              <option key={workspace.id} value={workspace.id}>
                {workspace.name}
              </option>
            ))}
          </select>
        </label>
      ) : null}

      {flags.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : (
        <ul className="divide-y rounded-lg border">
          {(flags.data ?? []).map((flag) => (
            <li key={flag.flagKey} className="flex items-center justify-between gap-3 p-3">
              <div className="min-w-0">
                <p className="flex items-center gap-2 text-sm">
                  <Sparkles className="size-3.5 text-primary" aria-hidden />
                  {flag.label}
                  {flag.overridden ? (
                    <Badge variant="outline" className="text-[10px]">
                      overridden here
                    </Badge>
                  ) : null}
                </p>
                <p className="text-[11px] text-muted-foreground font-mono">{flag.flagKey}</p>
              </div>

              <label className="flex shrink-0 items-center gap-2 text-xs">
                <span className="sr-only">{flag.label}</span>
                <input
                  type="checkbox"
                  checked={flag.enabled}
                  disabled={setFlag.isPending}
                  onChange={(event) =>
                    setFlag.mutate({ flagKey: flag.flagKey, enabled: event.target.checked })
                  }
                />
                {flag.enabled ? "On" : "Off"}
              </label>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function RecycleBinPanel() {
  const { api } = useSession();
  const queryClient = useQueryClient();
  const [entityType, setEntityType] = useState("PROJECT");

  const types = useQuery({
    queryKey: [...queryKeys.recycleBin.all, "types"],
    queryFn: () => adminApi.recycleBinTypes(api),
  });

  const entries = useQuery({
    queryKey: queryKeys.recycleBin.list(entityType),
    queryFn: () => adminApi.recycleBin(api, entityType),
  });

  const restore = useMutation({
    mutationFn: (input: { entityType: string; entityId: string }) =>
      adminApi.restore(api, input.entityType, input.entityId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.recycleBin.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.backlog.all });
      toast.success("Restored.");
    },
    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not restore that item.");
    },
  });

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {(types.data ?? ["PROJECT"]).map((type) => (
          <Button
            key={type}
            size="sm"
            variant={type === entityType ? "secondary" : "ghost"}
            onClick={() => setEntityType(type)}
          >
            {type.toLowerCase()}s
          </Button>
        ))}
      </div>

      <p className="text-xs text-muted-foreground">
        Deleted items are recoverable for 30 days. Restoring goes through the module that
        owns the item, so its own rules apply.
      </p>

      {entries.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : entries.error ? (
        <ErrorState error={entries.error} onRetry={() => void entries.refetch()} />
      ) : (entries.data?.content.length ?? 0) === 0 ? (
        <EmptyState
          icon={Trash2}
          title="Nothing deleted"
          description={`No ${entityType.toLowerCase()}s are waiting to be recovered.`}
        />
      ) : (
        <ul className="divide-y rounded-lg border">
          {entries.data!.content.map((entry) => (
            <li key={entry.entityId} className="flex items-center justify-between gap-3 p-3">
              <div className="min-w-0">
                <p className="truncate text-sm">
                  {entry.itemKey ? (
                    <span className="text-muted-foreground tabular-nums">{entry.itemKey} </span>
                  ) : null}
                  {entry.title}
                </p>
                <p className="text-[11px] text-muted-foreground">
                  Deleted {entry.deletedAt ? new Date(entry.deletedAt).toLocaleDateString() : "—"}
                  {entry.purgeAfter
                    ? ` · purged after ${new Date(entry.purgeAfter).toLocaleDateString()}`
                    : null}
                </p>
              </div>

              {entry.restorable ? (
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={restore.isPending}
                  onClick={() =>
                    restore.mutate({ entityType: entry.entityType, entityId: entry.entityId })
                  }
                >
                  <RotateCcw className="size-3.5" aria-hidden />
                  Restore
                </Button>
              ) : (
                // Says why rather than greying out a button with no explanation.
                <span className="shrink-0 text-[11px] text-muted-foreground">
                  {entry.blockedReason ?? "Cannot be restored"}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function AuditPanel() {
  const { api } = useSession();
  const logs = useQuery({
    queryKey: [...queryKeys.settings.all, "audit"],
    queryFn: () => adminApi.auditLogs(api),
  });

  if (logs.isPending) return <Skeleton className="h-72 w-full" />;
  if (logs.error) return <ErrorState error={logs.error} onRetry={() => void logs.refetch()} />;

  const entries = logs.data?.content ?? [];

  if (entries.length === 0) {
    return <EmptyState icon={ScrollText} title="Nothing recorded yet" />;
  }

  return (
    <div className="rounded-lg border">
      <table className="w-full text-sm">
        <caption className="sr-only">Audit trail</caption>
        <thead>
          <tr className="border-b text-left text-xs text-muted-foreground">
            <th scope="col" className="p-2 font-medium">When</th>
            <th scope="col" className="p-2 font-medium">Who</th>
            <th scope="col" className="p-2 font-medium">Action</th>
            <th scope="col" className="p-2 font-medium">Outcome</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id} className="border-b last:border-0">
              <td className="p-2 text-xs text-muted-foreground whitespace-nowrap">
                {new Date(entry.createdAt).toLocaleString()}
              </td>
              <td className="p-2 text-xs">{entry.actorEmail ?? "system"}</td>
              <td className="p-2">
                <span className="font-mono text-xs">{entry.action}</span>
                {entry.entityType ? (
                  <span className="ml-1.5 text-[11px] text-muted-foreground">
                    {entry.entityType.toLowerCase()}
                  </span>
                ) : null}
              </td>
              <td className="p-2">
                <Badge
                  variant={entry.outcome === "SUCCESS" ? "secondary" : "destructive"}
                  className="text-[10px]"
                >
                  {entry.outcome.toLowerCase()}
                </Badge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
