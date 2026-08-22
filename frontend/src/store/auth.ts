import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { Customer } from "../lib/types";

type Role = "CUSTOMER" | "ADMIN";

interface AuthState {
  token: string | null;
  role: Role | null;
  customer: Customer | null;
  loginCustomer: (token: string, customer: Customer) => void;
  loginAdmin: (token: string) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      role: null,
      customer: null,
      loginCustomer: (token, customer) => set({ token, role: "CUSTOMER", customer }),
      loginAdmin: (token) => set({ token, role: "ADMIN", customer: null }),
      logout: () => set({ token: null, role: null, customer: null }),
    }),
    { name: "toybox-auth" }
  )
);
