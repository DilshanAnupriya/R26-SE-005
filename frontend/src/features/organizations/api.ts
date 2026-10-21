import { backendOrigin } from "@/lib/env";
import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";

export const memberResponseSchema = z.object({
  userId: z.string(),
  email: z.string().nullable().optional(),
  fullName: z.string().nullable().optional(),
  avatarUrl: z.string().nullable().optional(),
  jobTitle: z.string().nullable().optional(),
  roleId: z.string(),
  roleCode: z.string().nullable().optional(),
  roleName: z.string().nullable().optional(),
  status: z.string(),
  joinedAt: z.string().nullable().optional(),
  lastLoginAt: z.string().nullable().optional(),
});
export type MemberResponse = z.infer<typeof memberResponseSchema>;

export const invitationResponseSchema = z.object({
  id: z.string(),
  email: z.string(),
  roleId: z.string(),
  roleCode: z.string().nullable().optional(),
  status: z.string(),
  expiresAt: z.string(),
  createdAt: z.string(),
  invitedBy: z.string().nullable().optional(),
  inviteToken: z.string().nullable().optional(),
});
export type InvitationResponse = z.infer<typeof invitationResponseSchema>;

export const invitationDetailsResponseSchema = z.object({
  email: z.string(),
  organizationName: z.string(),
  roleCode: z.string(),
  roleName: z.string(),
  inviterName: z.string(),
  expiresAt: z.string(),
});
export type InvitationDetailsResponse = z.infer<typeof invitationDetailsResponseSchema>;

export const roleResponseSchema = z.object({
  id: z.string(),
  name: z.string(),
  code: z.string(),
  description: z.string().nullish(),
  systemRole: z.boolean().optional(),
  rank: z.number().optional(),
  memberCount: z.number().nullish(),
  permissionCodes: z.array(z.string()).nullish(),
});
export type RoleResponse = z.infer<typeof roleResponseSchema>;

export const developerProfileSchema = z.object({
  userId: z.string(),
  seniority: z.string(),
  yearsExperience: z.number(),
  hourlyRate: z.number().nullable().optional(),
  weeklyCapacityHours: z.number(),
  availabilityRate: z.number(),
  avgVelocity: z.number().optional(),
  consistencyScore: z.number().optional(),
  learningRate: z.number().optional(),
  defectRate: z.number().optional(),
  reopenRate: z.number().optional(),
  blockedFrequency: z.number().optional(),
  completionVariance: z.number().optional(),
  tasksCompleted: z.number().optional(),
  hasMeaningfulHistory: z.boolean().optional(),
  lastComputedAt: z.string().nullable().optional(),
});
export type DeveloperProfile = z.infer<typeof developerProfileSchema>;

export const userSkillSchema = z.object({
  skillId: z.string(),
  skillName: z.string(),
  category: z.string().nullable().optional(),
  proficiency: z.number(),
});
export type UserSkill = z.infer<typeof userSkillSchema>;

export const organizationApi = {
  getMembers: (api: ApiClient) =>
    api.request("/organizations/current/members", {
      method: "GET",
      schema: pageResponseSchema(memberResponseSchema),
    }),

  getInvitations: (api: ApiClient) =>
    api.request("/organizations/current/invitations", {
      method: "GET",
      schema: pageResponseSchema(invitationResponseSchema),
    }),

  getRoles: (api: ApiClient) =>
    api.request("/organizations/current/roles", {
      method: "GET",
      schema: z.array(roleResponseSchema),
    }),

  inviteMember: (api: ApiClient, input: { email: string; roleId: string; workspaceId?: string }) =>
    api.request("/organizations/current/invitations", {
      method: "POST",
      body: input,
      schema: invitationResponseSchema,
    }),

  revokeInvitation: (api: ApiClient, invitationId: string) =>
    api.request(`/organizations/current/invitations/${invitationId}`, {
      method: "DELETE",
    }),

  removeMember: (api: ApiClient, userId: string) =>
    api.request(`/organizations/current/members/${userId}`, {
      method: "DELETE",
    }),

  updateMemberRole: (api: ApiClient, userId: string, roleId: string) =>
    api.request(`/organizations/current/members/${userId}/role`, {
      method: "PATCH",
      body: { roleId },
      schema: memberResponseSchema,
    }),

  verifyInvitation: async (token: string): Promise<InvitationDetailsResponse> => {
    const backendUrl = backendOrigin;
    const url = `${backendUrl.replace(/\/$/, "")}/api/v1/organizations/invitations/verify?token=${encodeURIComponent(token)}`;
    const res = await fetch(url, { headers: { Accept: "application/json" } });
    if (!res.ok) {
      const error = await res.json().catch(() => null);
      throw new Error(error?.message || "Invalid or expired invitation token.");
    }
    return invitationDetailsResponseSchema.parse(await res.json());
  },

  acceptInvitation: async (input: { token: string; fullName: string; password: string }): Promise<{ organizationId: string }> => {
    const backendUrl = backendOrigin;
    const url = `${backendUrl.replace(/\/$/, "")}/api/v1/organizations/invitations/accept`;
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(input),
    });
    if (!res.ok) {
      const error = await res.json().catch(() => null);
      throw new Error(error?.message || "Failed to accept invitation.");
    }
    return res.json();
  },

  updateDeveloperProfile: (api: ApiClient, userId: string, profile: {
    seniority: string;
    yearsExperience: number;
    weeklyCapacityHours: number;
    availabilityRate: number;
    hourlyRate?: number;
  }) =>
    api.request(`/users/${userId}/developer-profile`, {
      method: "PUT",
      body: profile,
      schema: developerProfileSchema,
    }),

  updateDeveloperSkills: (api: ApiClient, userId: string, skills: Array<{ skillName: string; proficiency: number }>) =>
    api.request(`/users/${userId}/skills`, {
      method: "PUT",
      body: { skills },
      schema: z.array(userSkillSchema),
    }),
};
