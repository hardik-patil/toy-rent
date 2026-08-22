import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { bookingApi, apiErrorMessage } from "../lib/api";
import type { LoginResponse } from "../lib/types";
import { useAuthStore } from "../store/auth";

export default function LoginPage() {
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const loginCustomer = useAuthStore((s) => s.loginCustomer);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const res = await bookingApi.post<LoginResponse>("/api/v1/customers/login", { phone, password });
      loginCustomer(res.data.accessToken, res.data.customer);
      navigate("/");
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-md flex-col items-center px-4 py-16">
      <span className="text-5xl">👋</span>
      <h1 className="font-display mt-2 text-3xl font-bold text-ink">Welcome back!</h1>
      <p className="mt-1 text-ink-soft">Log in to see your rentals.</p>

      <form onSubmit={handleSubmit} className="mt-8 w-full space-y-4 rounded-3xl bg-paper p-6 shadow-toy">
        {error && <p className="rounded-xl bg-coral-light p-3 text-sm font-semibold text-coral-dark">{error}</p>}

        <label className="block">
          <span className="mb-1 block text-sm font-bold text-ink-soft">Phone number</span>
          <input
            required
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            pattern="\d{10}"
            placeholder="9876543210"
            className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-coral focus:outline-none"
          />
        </label>

        <label className="block">
          <span className="mb-1 block text-sm font-bold text-ink-soft">Password</span>
          <input
            required
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-coral focus:outline-none"
          />
        </label>

        <button
          disabled={loading}
          className="w-full rounded-full bg-coral py-3 font-bold text-white shadow-toy transition hover:bg-coral-dark disabled:opacity-60"
        >
          {loading ? "Logging in…" : "Log in"}
        </button>
      </form>

      <p className="mt-6 text-ink-soft">
        New here?{" "}
        <Link to="/register" className="font-bold text-teal-dark hover:underline">
          Create an account
        </Link>
      </p>
    </div>
  );
}
