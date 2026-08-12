"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { useSession } from "@/components/session-provider";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/query-keys";
import { saveBlob } from "@/lib/api/client";
import { reportsApi, type ExportJob } from "./api";

function useReport<T>(key: readonly unknown[], fetcher: () => Promise<T>, enabled: boolean) {
  return useQuery({
    queryKey: key,
    queryFn: fetcher,
    enabled,
    // Reports aggregate a lot for one number and nobody expects them live. Five minutes
    // keeps a reports page from re-running eight aggregations on every tab focus.
    staleTime: 5 * 60_000,
  });
}

export function useVelocityReport(projectId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.velocity(projectId ?? ""),
    () => reportsApi.velocity(api, projectId!),
    status === "authenticated" && Boolean(projectId),
  );
}

export function useBurndownReport(sprintId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.burndown(sprintId ?? ""),
    () => reportsApi.burndown(api, sprintId!),
    status === "authenticated" && Boolean(sprintId),
  );
}

export function useCfdReport(projectId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.cfd(projectId ?? ""),
    () => reportsApi.cfd(api, projectId!),
    status === "authenticated" && Boolean(projectId),
  );
}

export function useCycleTimeReport(projectId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.cycleTime(projectId ?? ""),
    () => reportsApi.cycleTime(api, projectId!),
    status === "authenticated" && Boolean(projectId),
  );
}

export function useLeadTimeReport(projectId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.leadTime(projectId ?? ""),
    () => reportsApi.leadTime(api, projectId!),
    status === "authenticated" && Boolean(projectId),
  );
}

export function useProductivityReport(projectId: string | undefined, sprintId?: string) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.productivity(projectId ?? "", sprintId),
    () => reportsApi.productivity(api, projectId!, sprintId),
    status === "authenticated" && Boolean(projectId),
  );
}

export function useWorkloadBalanceReport(projectId: string | undefined) {
  const { api, status } = useSession();
  return useReport(
    queryKeys.reports.workloadBalance(projectId ?? ""),
    () => reportsApi.workloadBalance(api, projectId!),
    status === "authenticated" && Boolean(projectId),
  );
}

/**
 * Queues an export and polls until it finishes.
 *
 * <p>Polling rather than a websocket push: an export is a one-off the user is actively
 * waiting on, and a two-second poll for a few seconds is simpler than a subscription
 * that has to be torn down correctly. It gives up after two minutes rather than polling
 * forever — a job that has not finished by then has a problem the UI should name.</p>
 */
export function useRequestExport() {
  const { api } = useSession();
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (body: { report: string; projectId?: string; sprintId?: string }) => {
      const queued = await reportsApi.requestExport(api, body);

      const deadline = Date.now() + 120_000;
      let job: ExportJob = queued;

      while (job.status === "QUEUED" || job.status === "RUNNING") {
        if (Date.now() > deadline) {
          throw new ApiError({
            status: 504,
            code: "EXPORT_TIMEOUT",
            message: "The export is taking longer than expected. Check your exports shortly.",
          });
        }
        await new Promise((resolve) => setTimeout(resolve, 2_000));
        job = await reportsApi.exportStatus(api, queued.jobId);
      }
      return job;
    },

    onSuccess: (job) => {
      void queryClient.invalidateQueries({ queryKey: [...queryKeys.reports.all, "exports"] });

      if (job.status === "COMPLETED") {
        toast.success("Export ready", {
          action: {
            label: "Download",
            onClick: () => {
              void reportsApi.downloadExport(api, job.jobId).then(({ blob, fileName }) => {
                saveBlob(blob, fileName ?? `${job.report}.csv`);
              });
            },
          },
        });
      } else {
        toast.error(job.errorMessage ?? "That export did not complete.");
      }
    },

    onError: (error) => {
      toast.error(error instanceof ApiError ? error.message : "Could not start the export.");
    },
  });
}

export function useMyExports() {
  const { api, status } = useSession();
  return useQuery({
    queryKey: [...queryKeys.reports.all, "exports"],
    queryFn: () => reportsApi.myExports(api),
    enabled: status === "authenticated",
  });
}
