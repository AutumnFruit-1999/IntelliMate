const BASE_URL = import.meta.env.VITE_API_URL ?? `http://${window.location.hostname}:3007`;

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

export async function createJob(data: CreateJobRequest): Promise<ScheduledJobConfig> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to create job: ${res.status}`);
  }
  return res.json();
}

export async function deleteJob(jobName: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}`, { method: "DELETE" });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Failed to delete job: ${res.status}`);
  }
}

export async function fetchScheduledJobs(): Promise<ScheduledJobConfig[]> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs`);
  if (!res.ok) throw new Error(`Failed to fetch jobs: ${res.status}`);
  return res.json();
}

export async function fetchJobDetail(jobName: string): Promise<ScheduledJobConfig> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}`);
  if (!res.ok) throw new Error(`Failed to fetch job: ${res.status}`);
  return res.json();
}

export async function updateJob(jobName: string, updates: Record<string, unknown>): Promise<ScheduledJobConfig> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(updates),
  });
  if (!res.ok) throw new Error(`Failed to update job: ${res.status}`);
  return res.json();
}

export async function triggerJob(jobName: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}/trigger`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to trigger job: ${res.status}`);
}

export async function pauseJob(jobName: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}/pause`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to pause job: ${res.status}`);
}

export async function resumeJob(jobName: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}/resume`, { method: "POST" });
  if (!res.ok) throw new Error(`Failed to resume job: ${res.status}`);
}

export async function fetchJobLogs(jobName: string, page = 0, size = 20): Promise<ScheduledJobLog[]> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/${jobName}/logs?page=${page}&size=${size}`);
  if (!res.ok) throw new Error(`Failed to fetch logs: ${res.status}`);
  return res.json();
}

export async function fetchRecentLogs(limit = 50): Promise<ScheduledJobLog[]> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/logs/recent?limit=${limit}`);
  if (!res.ok) throw new Error(`Failed to fetch recent logs: ${res.status}`);
  return res.json();
}

export async function fetchStatsOverview(): Promise<JobStatsOverview> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/stats/overview`);
  if (!res.ok) throw new Error(`Failed to fetch stats: ${res.status}`);
  return res.json();
}

export async function fetchJobStats(jobName: string, days = 7): Promise<JobStats> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/stats/${jobName}?days=${days}`);
  if (!res.ok) throw new Error(`Failed to fetch job stats: ${res.status}`);
  return res.json();
}

export async function fetchTimeline(hours = 24): Promise<ScheduledJobLog[]> {
  const res = await fetch(`${BASE_URL}/api/scheduled-jobs/stats/timeline?hours=${hours}`);
  if (!res.ok) throw new Error(`Failed to fetch timeline: ${res.status}`);
  return res.json();
}
