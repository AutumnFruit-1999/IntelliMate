const BASE_URL =
  import.meta.env.VITE_API_URL ?? `http://${window.location.hostname}:3007`;

function getAuthHeaders(): Record<string, string> {
  const token = import.meta.env.VITE_AUTH_TOKEN ?? "";
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
}

function getAuthHeadersWithoutContentType(): Record<string, string> {
  const token = import.meta.env.VITE_AUTH_TOKEN ?? "";
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  return {};
}

export async function apiFetch<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: getAuthHeaders(),
    ...options,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`API ${res.status}: ${text}`);
  }
  const text = await res.text();
  if (!text) return undefined as unknown as T;
  const json = JSON.parse(text);
  if (json && typeof json === "object" && "success" in json && "data" in json) {
    if (!json.success) {
      throw new Error(json.message || "API request failed");
    }
    return json.data as T;
  }
  return json;
}

export async function apiFetchRaw(
  path: string,
  options?: RequestInit,
): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    headers: getAuthHeadersWithoutContentType(),
    ...options,
  });
}

export function apiUrl(path: string): string {
  return `${BASE_URL}${path}`;
}

export { BASE_URL, getAuthHeaders, getAuthHeadersWithoutContentType };
