"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "@/components/session-provider";
import { organizationApi } from "./api";

export function useMembers() {
  const { api, status, activeOrganizationId } = useSession();
  return useQuery({
    queryKey: ["organizations", "members", activeOrganizationId],
    queryFn: () => organizationApi.getMembers(api),
    enabled: status === "authenticated",
  });
}

export function useInvitations() {
  const { api, status, activeOrganizationId } = useSession();
  return useQuery({
    queryKey: ["organizations", "invitations", activeOrganizationId],
    queryFn: () => organizationApi.getInvitations(api),
    enabled: status === "authenticated",
  });
}

export function useRoles() {
  const { api, status, activeOrganizationId } = useSession();
  return useQuery({
    queryKey: ["organizations", "roles", activeOrganizationId],
    queryFn: () => organizationApi.getRoles(api),
    enabled: status === "authenticated",
  });
}

export function useInviteMember() {
  const { api, activeOrganizationId } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (input: { email: string; roleId: string; workspaceId?: string }) =>
      organizationApi.inviteMember(api, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["organizations", "invitations", activeOrganizationId],
      });
    },
  });
}

export function useRevokeInvitation() {
  const { api, activeOrganizationId } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (invitationId: string) =>
      organizationApi.revokeInvitation(api, invitationId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["organizations", "invitations", activeOrganizationId],
      });
    },
  });
}

export function useRemoveMember() {
  const { api, activeOrganizationId } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (userId: string) =>
      organizationApi.removeMember(api, userId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["organizations", "members", activeOrganizationId],
      });
    },
  });
}

export function useUpdateMemberRole() {
  const { api, activeOrganizationId } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      organizationApi.updateMemberRole(api, userId, roleId),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: ["organizations", "members", activeOrganizationId],
      });
    },
  });
}
