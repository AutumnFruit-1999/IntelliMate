import { create } from "zustand";
import { loginApi, registerApi, fetchMe, type AuthResponse } from "../lib/authApi";

const STORAGE_KEY = "auth_token";

function readInitialToken(): string | null {
  return localStorage.getItem(STORAGE_KEY) ?? import.meta.env.VITE_AUTH_TOKEN ?? null;
}

interface AuthState {
  token: string | null;
  userId: number | null;
  username: string | null;
  displayName: string | null;
  unifiedUserId: string | null;
  isAuthenticated: boolean;

  login: (username: string, password: string) => Promise<void>;
  register: (username: string, password: string, displayName?: string) => Promise<void>;
  logout: () => void;
  initFromStorage: () => void;
}

function applyAuthResponse(
  set: (partial: Partial<AuthState>) => void,
  response: AuthResponse
): void {
  localStorage.setItem(STORAGE_KEY, response.token);
  if (response.unifiedUserId) {
    localStorage.setItem("unified_user_id", response.unifiedUserId);
  }
  set({
    token: response.token,
    userId: response.userId,
    username: response.username,
    displayName: response.displayName,
    unifiedUserId: response.unifiedUserId ?? null,
    isAuthenticated: true,
  });
}

function clearAuth(set: (partial: Partial<AuthState>) => void): void {
  localStorage.removeItem(STORAGE_KEY);
  localStorage.removeItem("unified_user_id");
  set({
    token: null,
    userId: null,
    username: null,
    displayName: null,
    unifiedUserId: null,
    isAuthenticated: false,
  });
}

const initialToken = readInitialToken();

export const useAuthStore = create<AuthState>((set) => ({
  token: initialToken,
  userId: null,
  username: null,
  displayName: null,
  unifiedUserId: localStorage.getItem("unified_user_id"),
  isAuthenticated: initialToken !== null,

  login: async (username, password) => {
    const response = await loginApi(username, password);
    applyAuthResponse(set, response);
  },

  register: async (username, password, displayName) => {
    const response = await registerApi(username, password, displayName);
    applyAuthResponse(set, response);
  },

  logout: () => {
    clearAuth(set);
  },

  initFromStorage: () => {
    const token = localStorage.getItem(STORAGE_KEY);
    if (!token) return;

    set({ token, isAuthenticated: true });
    fetchMe()
      .then((me) => {
        set({ userId: me.userId, username: me.username });
      })
      .catch(() => {
        clearAuth(set);
      });
  },
}));

export function getAuthToken(): string {
  return useAuthStore.getState().token ?? "";
}
