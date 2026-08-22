import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { bookingApi, apiErrorMessage } from "../../lib/api";
import type { AdminLoginResponse } from "../../lib/types";
import { useAuthStore } from "../../store/auth";

export default function AdminLoginPage() {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const loginAdmin = useAuthStore((s) => s.loginAdmin);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await bookingApi.post<AdminLoginResponse>("/api/v1/admin/login", { username, password });
      loginAdmin(res.data.accessToken);
      navigate("/admin/inventory");
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto flex min-h-[70vh] max-w-md flex-col items-center justify-center px-4">
      <span className="text-5xl">🔐</span>
      <h1 className="font-display mt-2 text-3xl font-bold text-ink">Admin Panel</h1>
      <p className="mt-1 text-ink-soft">Inventory &amp; operations access.</p>

      <form onSubmit={handleSubmit} className="mt-8 w-full space-y-4 rounded-3xl bg-paper p-6 shadow-toy">
        {error && <p className="rounded-xl bg-coral-light p-3 text-sm font-semibold text-coral-dark">{error}</p>}

        <label className="block">
          <span className="mb-1 block text-sm font-bold text-ink-soft">Username</span>
          <input
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
          />
        </label>

        <label className="block">
          <span className="mb-1 block text-sm font-bold text-ink-soft">Password</span>
          <input
            required
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
          />
        </label>

        <button
          disabled={loading}
          className="w-full rounded-full bg-grape py-3 font-bold text-white shadow-toy transition hover:bg-grape-dark disabled:opacity-60"
        >
          {loading ? "Logging in…" : "Log in"}
        </button>
      </form>
    </div>
  );
}
