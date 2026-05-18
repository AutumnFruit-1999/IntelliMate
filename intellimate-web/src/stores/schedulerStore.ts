import { create } from "zustand";
import type { ScheduledJobConfig, ScheduledJobLog, JobStatsOverview } from "../lib/schedulerApi";
import {
  fetchScheduledJobs,
  fetchRecentLogs,
  fetchStatsOverview,
  triggerJob,
  pauseJob,
  resumeJob,
} from "../lib/schedulerApi";

interface SchedulerState {
  jobs: ScheduledJobConfig[];
  recentLogs: ScheduledJobLog[];
  overview: JobStatsOverview | null;
  loading: boolean;
  error: string | null;

  loadJobs: () => Promise<void>;
  loadRecentLogs: () => Promise<void>;
  loadOverview: () => Promise<void>;
  trigger: (jobName: string) => Promise<void>;
  pause: (jobName: string) => Promise<void>;
  resume: (jobName: string) => Promise<void>;
  handleSchedulerEvent: (event: { type: string; payload: Record<string, unknown> }) => void;
}

export const useSchedulerStore = create<SchedulerState>((set, get) => ({
  jobs: [],
  recentLogs: [],
  overview: null,
  loading: false,
  error: null,

  loadJobs: async () => {
    set({ loading: true, error: null });
    try {
      const jobs = await fetchScheduledJobs();
      set({ jobs, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e), loading: false });
    }
  },

  loadRecentLogs: async () => {
    try {
      const recentLogs = await fetchRecentLogs(50);
      set({ recentLogs });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  loadOverview: async () => {
    try {
      const overview = await fetchStatsOverview();
      set({ overview });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e) });
    }
  },

  trigger: async (jobName: string) => {
    await triggerJob(jobName);
    await get().loadJobs();
  },

  pause: async (jobName: string) => {
    await pauseJob(jobName);
    await get().loadJobs();
  },

  resume: async (jobName: string) => {
    await resumeJob(jobName);
    await get().loadJobs();
  },

  handleSchedulerEvent: (event) => {
    const { type, payload } = event;
    const jobName = payload.jobName as string;

    set((state) => {
      const jobs = state.jobs.map((job) => {
        if (job.jobName !== jobName) return job;
        if (type === "scheduler.job.started") {
          return { ...job, running: true, lastStatus: "RUNNING" };
        }
        if (type === "scheduler.job.completed") {
          return {
            ...job,
            running: false,
            lastStatus: payload.status as string,
            lastFireTime: new Date().toISOString(),
          };
        }
        return job;
      });
      return { jobs };
    });
  },
}));
