import axios, { type AxiosInstance } from "axios";
import { useAuthStore } from "../store/auth";
import type { ApiErrorBody } from "./types";

function withAuth(instance: AxiosInstance): AxiosInstance {
  instance.interceptors.request.use((config) => {
    const token = useAuthStore.getState().token;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
  return instance;
}

export const toyApi = withAuth(
  axios.create({ baseURL: import.meta.env.VITE_TOY_SERVICE_URL })
);

export const bookingApi = withAuth(
  axios.create({ baseURL: import.meta.env.VITE_BOOKING_SERVICE_URL })
);

/** Every service's GlobalExceptionHandler returns this exact shape (see CLAUDE.md). */
export function apiErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as ApiErrorBody | undefined;
    if (body?.message) return body.message;
    if (err.message) return err.message;
  }
  return "Something went wrong. Please try again.";
}
