"use client";

import { use, useState, useEffect } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, ScrollText, Sparkles, Trash2, Users, UserPlus, Mail, Copy, Check, Shield } from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { EmptyState, ErrorState } from "@/components/feedback/states";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { adminApi } from "@/features/admin/api";
import { aiApi } from "@/features/ai/api";
import { useAiModels } from "@/features/ai/hooks";
import { useMembers, useInvitations, useRoles, useInviteMember, useRevokeInvitation, useRemoveMember } from "@/features/organizations/hooks";

/**
 * The admin console.
 *
 * <p>Team members & invitations, AI capabilities, model health, recovery and the audit trail.</p>
 */
export default function SettingsPage({ params }: { params: Promise<{ orgSlug: string }> }) {
  use(params);
  const { hasPermission } = useSession();

  return (
    <div className="animate-fade-up mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Administration</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Team members, invitations, capabilities, model health, and audit logs.
        </p>
      </div>

      <Tabs defaultValue="members">
        <TabsList>
          <TabsTrigger value="members">Team & Invitations</TabsTrigger>
          <TabsTrigger value="ai">AI models</TabsTrigger>
          <TabsTrigger value="flags">Capabilities</TabsTrigger>
          <TabsTrigger value="recycle">Recycle bin</TabsTrigger>
          {hasPermission("audit:read") ? <TabsTrigger value="audit">Audit</TabsTrigger> : null}
        </TabsList>

        <TabsContent value="members" className="mt-4">
          <MembersPanel />
        </TabsContent>
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

function MembersPanel() {
  const { hasPermission } = useSession();
  const members = useMembers();
  const invitations = useInvitations();
  const roles = useRoles();
  const inviteMutation = useInviteMember();
  const revokeMutation = useRevokeInvitation();
  const removeMutation = useRemoveMember();

  const [inviteOpen, setInviteOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [roleId, setRoleId] = useState("");
  const [createdInviteUrl, setCreatedInviteUrl] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const canInvite = hasPermission("member:invite");
  const canRemove = hasPermission("member:remove");

  const memberList = members.data?.content ?? [];
  const inviteList = invitations.data?.content ?? [];
  const roleList = roles.data ?? [];
  const defaultRole = roleList.find(r => r.code === "MEMBER") || roleList[0];

  // Auto-select the default role when roles load
  useEffect(() => {
    if (!roleId && defaultRole?.id) {
      setRoleId(defaultRole.id);
    }
  }, [roleId, defaultRole]);

  const handleInvite = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim()) return;

    const targetRoleId = roleId || defaultRole?.id;
    if (!targetRoleId) {
      toast.error("Please select a role.");
      return;
    }

    try {
      const res = await inviteMutation.mutateAsync({
        email: email.trim(),
        roleId: targetRoleId,
      });

      if (res.inviteToken) {
        const fullUrl = `${window.location.origin}/accept-invitation?token=${res.inviteToken}`;
        setCreatedInviteUrl(fullUrl);
      } else {
        toast.success(`Invitation sent to ${email}`);
        setInviteOpen(false);
        setEmail("");
      }
    } catch (err: any) {
      toast.error(err?.message || "Failed to invite member.");
    }
  };

  const copyToClipboard = (url: string) => {
    navigator.clipboard.writeText(url);
    setCopied(true);
    toast.success("Invitation link copied to clipboard!");
    setTimeout(() => setCopied(false), 2000);
  };

  const handleRevoke = async (id: string) => {
    try {
      await revokeMutation.mutateAsync(id);
      toast.success("Invitation revoked.");
    } catch (err: any) {
      toast.error("Could not revoke invitation.");
    }
  };

  const handleRemove = async (userId: string, name?: string | null) => {
    if (!confirm(`Are you sure you want to remove ${name || "this user"} from the organization?`)) return;
    try {
      await removeMutation.mutateAsync(userId);
      toast.success("Member removed.");
    } catch (err: any) {
      toast.error(err?.message || "Could not remove member.");
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4 p-4 rounded-2xl bg-card border">
        <div>
          <h3 className="font-semibold text-foreground">Team Members & Access</h3>
          <p className="text-xs text-muted-foreground">
            Invite developers and team members to collaborate on sprints, tasks, and resource allocation.
          </p>
        </div>

        {canInvite ? (
          <Dialog open={inviteOpen} onOpenChange={(open) => {
            setInviteOpen(open);
            if (!open) {
              setCreatedInviteUrl(null);
              setEmail("");
            }
          }}>
            <DialogTrigger asChild>
              <Button className="flex items-center space-x-2 font-medium shadow-md">
                <UserPlus className="w-4 h-4" />
                <span>Invite Developer / Member</span>
              </Button>
            </DialogTrigger>
            <DialogContent className="sm:max-w-md">
              <DialogHeader>
                <DialogTitle className="flex items-center space-x-2">
                  <UserPlus className="w-5 h-5 text-primary" />
                  <span>Invite to Organization</span>
                </DialogTitle>
                <DialogDescription className="text-xs text-muted-foreground">
                  The user will receive an invitation link to set up their account and developer profile.
                </DialogDescription>
              </DialogHeader>

              {createdInviteUrl ? (
                <div className="space-y-4 py-3">
                  <div className="p-3.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-600 dark:text-emerald-400 text-sm flex items-center space-x-2">
                    <Check className="w-4 h-4 flex-shrink-0" />
                    <span>Invitation created successfully!</span>
                  </div>

                  <div className="space-y-1.5">
                    <Label className="text-xs">Shareable Invitation Link</Label>
                    <div className="flex items-center space-x-2">
                      <Input
                        readOnly
                        value={createdInviteUrl}
                        className="text-xs font-mono"
                      />
                      <Button
                        type="button"
                        onClick={() => copyToClipboard(createdInviteUrl)}
                        variant="secondary"
                        className="shrink-0"
                      >
                        {copied ? <Check className="w-4 h-4 text-emerald-500" /> : <Copy className="w-4 h-4" />}
                      </Button>
                    </div>
                  </div>

                  <Button
                    type="button"
                    onClick={() => {
                      setInviteOpen(false);
                      setCreatedInviteUrl(null);
                      setEmail("");
                    }}
                    className="w-full mt-2"
                  >
                    Done
                  </Button>
                </div>
              ) : (
                <form onSubmit={handleInvite} className="space-y-4 py-2">
                  <div className="space-y-1.5">
                    <Label className="text-xs">Email Address</Label>
                    <Input
                      type="email"
                      required
                      placeholder="developer@company.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label className="text-xs">Role</Label>
                    <select
                      value={roleId || defaultRole?.id || ""}
                      onChange={(e) => setRoleId(e.target.value)}
                      className="w-full px-3 py-2 border rounded-lg text-sm bg-background focus:outline-none focus:ring-2 focus:ring-primary"
                    >
                      {roles.isLoading ? (
                        <option value="" disabled>Loading roles...</option>
                      ) : roleList.length === 0 ? (
                        <option value="" disabled>No roles available</option>
                      ) : (
                        roleList.map((r) => (
                          <option key={r.id} value={r.id}>
                            {r.name} ({r.code})
                          </option>
                        ))
                      )}
                    </select>
                  </div>

                  <Button
                    type="submit"
                    disabled={inviteMutation.isPending}
                    className="w-full mt-3 font-semibold"
                  >
                    {inviteMutation.isPending ? "Generating Invitation..." : "Send Invitation"}
                  </Button>
                </form>
              )}
            </DialogContent>
          </Dialog>
        ) : null}
      </div>

      {/* Active Members Table */}
      <div className="space-y-3">
        <h4 className="text-sm font-semibold">
          Active Members ({memberList.length})
        </h4>

        {members.isPending ? (
          <Skeleton className="h-48 w-full" />
        ) : memberList.length === 0 ? (
          <EmptyState icon={Users} title="No members found" />
        ) : (
          <div className="rounded-xl border overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground bg-muted/40">
                  <th className="p-3 font-medium">User</th>
                  <th className="p-3 font-medium">Email</th>
                  <th className="p-3 font-medium">Role</th>
                  <th className="p-3 font-medium">Status</th>
                  <th className="p-3 font-medium">Joined</th>
                  {canRemove ? <th className="p-3 font-medium text-right">Actions</th> : null}
                </tr>
              </thead>
              <tbody className="divide-y">
                {memberList.map((m) => (
                  <tr key={m.userId} className="hover:bg-muted/30 transition-colors">
                    <td className="p-3 flex items-center space-x-2.5">
                      <Avatar className="w-7 h-7">
                        <AvatarImage src={m.avatarUrl || undefined} />
                        <AvatarFallback className="text-[10px]">
                          {(m.fullName || "U").slice(0, 2).toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                      <span className="font-medium text-xs">{m.fullName || "Unknown"}</span>
                    </td>
                    <td className="p-3 text-xs text-muted-foreground">{m.email}</td>
                    <td className="p-3">
                      <Badge variant="outline" className="text-[10px] font-mono">
                        {m.roleCode || "MEMBER"}
                      </Badge>
                    </td>
                    <td className="p-3">
                      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20">
                        {m.status}
                      </span>
                    </td>
                    <td className="p-3 text-xs text-muted-foreground whitespace-nowrap">
                      {m.joinedAt ? new Date(m.joinedAt).toLocaleDateString() : "—"}
                    </td>
                    {canRemove ? (
                      <td className="p-3 text-right">
                        {m.roleCode !== "OWNER" ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleRemove(m.userId, m.fullName)}
                            className="text-xs text-destructive hover:bg-destructive/10 h-7 px-2"
                          >
                            <Trash2 className="w-3.5 h-3.5 mr-1" />
                            Remove
                          </Button>
                        ) : null}
                      </td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Pending Invitations */}
      {inviteList.length > 0 && (
        <div className="space-y-3 pt-2">
          <h4 className="text-sm font-semibold">
            Pending Invitations ({inviteList.length})
          </h4>

          <div className="rounded-xl border overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground bg-muted/40">
                  <th className="p-3 font-medium">Invited Email</th>
                  <th className="p-3 font-medium">Assigned Role</th>
                  <th className="p-3 font-medium">Expires At</th>
                  <th className="p-3 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {inviteList.map((inv) => (
                  <tr key={inv.id} className="hover:bg-muted/30 transition-colors">
                    <td className="p-3 text-xs font-medium flex items-center space-x-2">
                      <Mail className="w-3.5 h-3.5 text-muted-foreground" />
                      <span>{inv.email}</span>
                    </td>
                    <td className="p-3">
                      <Badge variant="outline" className="text-[10px] font-mono">
                        {inv.roleCode || "MEMBER"}
                      </Badge>
                    </td>
                    <td className="p-3 text-xs text-muted-foreground">
                      {new Date(inv.expiresAt).toLocaleDateString()}
                    </td>
                    <td className="p-3 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleRevoke(inv.id)}
                        className="text-xs text-destructive hover:bg-destructive/10 h-7 px-2"
                      >
                        Revoke
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
