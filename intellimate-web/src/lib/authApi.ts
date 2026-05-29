import { apiFetch } from "./httpClient";

export interface AuthResponse {
  token: string;
  userId: number;
  username: string;
  displayName: string;
  unifiedUserId: string;
}

export function loginApi(username: string, password: string): Promise<AuthResponse> {
  return apiFetch("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
}

export function registerApi(
  username: string,
  password: string,
  displayName?: string
): Promise<AuthResponse> {
  return apiFetch("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ username, password, displayName }),
  });
}

export function fetchMe(): Promise<{ userId: number; username: string }> {
  return apiFetch("/api/auth/me");
}
