import { z } from "zod";
import type { ApiClient } from "@/lib/api/client";
import { pageResponseSchema } from "@/lib/api/schemas";

export const sprintCostMemberSchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  userId: z.uuid(),
  fullName: z.string().nullish(),
  email: z.string().nullish(),
  avatarUrl: z.string().nullish(),
  projectRole: z.string(),
  stakeholder: z.boolean(),
  allocationPct: z.coerce.number(),
});
export type SprintCostMember = z.infer<typeof sprintCostMemberSchema>;

export const sprintCostProfileSchema = z.object({
  userId: z.uuid(),
  seniority: z.string(),
  yearsExperience: z.coerce.number(),
  hourlyRate: z.coerce.number().nullish(),
  weeklyCapacityHours: z.coerce.number(),
  availabilityRate: z.coerce.number(),
  avgVelocity: z.coerce.number().nullish(),
  tasksCompleted: z.number().int().optional(),
  measured: z.boolean().optional(),
});
export type SprintCostProfile = z.infer<typeof sprintCostProfileSchema>;

export const sprintCostSkillSchema = z.object({
  skillId: z.uuid(),
  skillName: z.string(),
  category: z.string().nullish(),
  proficiency: z.coerce.number(),
});
export type SprintCostSkill = z.infer<typeof sprintCostSkillSchema>;

export const sprintTimeEntrySchema = z.object({
  id: z.uuid(),
  projectId: z.uuid(),
  sprintId: z.uuid().nullish(),
  entityId: z.uuid(),
  userId: z.uuid(),
  userName: z.string().nullish(),
  minutes: z.number().int(),
  overtime: z.boolean(),
  description: z.string().nullish(),
  loggedOn: z.string(),
});
export type SprintTimeEntry = z.infer<typeof sprintTimeEntrySchema>;

export const sprintCostApi = {
  members: (api: ApiClient, projectId: string) =>
    api.request(`/projects/${projectId}/members`, {
      schema: z.array(sprintCostMemberSchema),
    }),

  developerProfile: (api: ApiClient, userId: string) =>
    api.request(`/users/${userId}/developer-profile`, {
      schema: sprintCostProfileSchema,
    }),

  developerSkills: (api: ApiClient, userId: string) =>
    api.request(`/users/${userId}/skills`, {
      schema: z.array(sprintCostSkillSchema),
    }),

  timeEntries: (api: ApiClient, projectId: string, sprintId: string) =>
    api.request("/time-entries", {
      schema: pageResponseSchema(sprintTimeEntrySchema),
      query: { projectId, sprintId, size: 100, sort: "loggedOn,desc" },
    }),
};
