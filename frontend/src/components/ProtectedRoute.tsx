import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuthStore } from "../store/auth";

export function CustomerRoute({ children }: { children: ReactNode }) {
  const role = useAuthStore((s) => s.role);
  if (role !== "CUSTOMER") return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export function AdminRoute({ children }: { children: ReactNode }) {
  const role = useAuthStore((s) => s.role);
  if (role !== "ADMIN") return <Navigate to="/admin/login" replace />;
  return <>{children}</>;
}
