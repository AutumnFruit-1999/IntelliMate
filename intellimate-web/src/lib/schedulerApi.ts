import { apiFetch, apiFetchRaw } from "./httpClient";

export interface ScheduledJobConfig {
  jobName: string;
  jobGroup: string;
  displayName: string;
  triggerType: string;
  triggerValue: string;
  enabled: boolean;
  nextFireTime: string | null;
  lastFireTime: string | null;
  lastStatus: string | null;
  consecutiveFailures: number;
  running: boolean;
  timeoutMs: number;
  maxRetryCount: number;
  retryBackoffMs: number;
  description?: string;
  jobClass?: string;
  timezone?: string;
  paramsJson?: string;
  concurrentAllowed?: boolean;
  recentLogs?: ScheduledJobLog[];
}

export interface ScheduledJobLog {
  id: number;
  jobName: string;
  jobGroup: string;
  fireTime: string;
  startTime: string;
  endTime: string | null;
  durationMs: number | null;
  status: string;
  retryCount: number;
  resultMessage: string | null;
  errorMessage: string | null;
  errorStack: string | null;
  metricsJson: string | null;
  triggerSource: string;
  createdAt: string;
}

export interface JobStatsOverview {
  totalJobs: number;
  enabledJobs: number;
  todayExecutions: number;
  todaySuccessRate: number;
  todayFailures: number;
  todayTimeouts: number;
  currentlyRunning: number;
}

export interface JobStats {
  jobName: string;
  days: number;
  totalExecutions: number;
  successCount: number;
  successRate: number;
  avgDurationMs: number;
  maxDurationMs: number;
}

export interface CreateJobRequest {
  jobName: string;
  jobClass?: string;
  jobGroup?: string;
  displayName?: string;
  description?: string;
  triggerType: string;
  triggerValue: string;
  timeoutMs?: number;
  maxRetryCount?: number;
  retryBackoffMs?: number;
  paramsJson?: string;
}

export function fetchScheduledJobs(): Promise<ScheduledJobConfig[]> {
  return apiFetch<ScheduledJobConfig[]>("/api/scheduled-jobs");
}

export function fetchJobDetail(jobName: string): Promise<ScheduledJobConfig> {
  return apiFetch<ScheduledJobConfig>(`/api/scheduled-jobs/${jobName}`);
}

export function fetchJobLogs(jobName: string, page = 0, size = 20): Promise<ScheduledJobLog[]> {
  return apiFetch<ScheduledJobLog[]>(`/api/scheduled-jobs/${jobName}/logs?page=${page}&size=${size}`);
}

export function fetchRecentLogs(limit = 50): Promise<ScheduledJobLog[]> {
  return apiFetch<ScheduledJobLog[]>(`/api/scheduled-jobs/logs/recent?limit=${limit}`);
}

export function fetchStatsOverview(): Promise<JobStatsOverview> {
  return apiFetch<JobStatsOverview>("/api/scheduled-jobs/stats/overview");
}

export function fetchJobStats(jobName: string, days = 7): Promise<JobStats> {
  return apiFetch<JobStats>(`/api/scheduled-jobs/stats/${jobName}?days=${days}`);
}

export function fetchTimeline(hours = 24): Promise<ScheduledJobLog[]> {
  return apiFetch<ScheduledJobLog[]>(`/api/scheduled-jobs/stats/timeline?hours=${hours}`);
}

export function createJob(data: CreateJobRequest): Promise<ScheduledJobConfig> {
  return apiFetch<ScheduledJobConfig>("/api/scheduled-jobs", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function deleteJob(jobName: string): Promise<void> {
  const res = await apiFetchRaw(`/api/scheduled-jobs/${jobName}`, { method: "DELETE" });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to delete job: ${res.status}`);
  }
}

export function updateJob(jobName: string, updates: Record<string, unknown>): Promise<ScheduledJobConfig> {
  return apiFetch<ScheduledJobConfig>(`/api/scheduled-jobs/${jobName}`, {
    method: "PUT",
    body: JSON.stringify(updates),
  });
}

export async function triggerJob(jobName: string): Promise<void> {
  const res = await apiFetchRaw(`/api/scheduled-jobs/${jobName}/trigger`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to trigger job: ${res.status}`);
}

export async function pauseJob(jobName: string): Promise<void> {
  const res = await apiFetchRaw(`/api/scheduled-jobs/${jobName}/pause`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to pause job: ${res.status}`);
}

export async function resumeJob(jobName: string): Promise<void> {
  const res = await apiFetchRaw(`/api/scheduled-jobs/${jobName}/resume`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to resume job: ${res.status}`);
}
