import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import { bookingApi, apiErrorMessage } from "../lib/api";
import type { Customer, LoginResponse } from "../lib/types";
import { useAuthStore } from "../store/auth";

export default function RegisterPage() {
  const [form, setForm] = useState({
    name: "",
    phone: "",
    email: "",
    password: "",
    area: "",
    flat: "",
    building: "",
    city: "Navi Mumbai",
    pincode: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const loginCustomer = useAuthStore((s) => s.loginCustomer);
  const navigate = useNavigate();

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await bookingApi.post<Customer>("/api/v1/customers/register", form);
      const loginRes = await bookingApi.post<LoginResponse>("/api/v1/customers/login", {
        phone: form.phone,
        password: form.password,
      });
      loginCustomer(loginRes.data.accessToken, loginRes.data.customer);
      navigate("/");
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="mx-auto flex max-w-lg flex-col items-center px-4 py-16">
      <span className="text-5xl">🎉</span>
      <h1 className="font-display mt-2 text-3xl font-bold text-ink">Join ToyBox</h1>
      <p className="mt-1 text-ink-soft">A few details and you're ready to rent.</p>

      <form onSubmit={handleSubmit} className="mt-8 w-full space-y-4 rounded-3xl bg-paper p-6 shadow-toy">
        {error && <p className="rounded-xl bg-coral-light p-3 text-sm font-semibold text-coral-dark">{error}</p>}

        <div className="grid grid-cols-2 gap-4">
          <Field label="Full name" value={form.name} onChange={(v) => set("name", v)} required full />
          <Field label="Phone" value={form.phone} onChange={(v) => set("phone", v)} pattern="\d{10}" required />
          <Field label="Email" value={form.email} onChange={(v) => set("email", v)} type="email" />
          <Field
            label="Password"
            value={form.password}
            onChange={(v) => set("password", v)}
            type="password"
            required
          />
        </div>

        <div className="pt-2">
          <p className="mb-2 text-sm font-bold text-ink-soft">Delivery address (optional for now)</p>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Flat / House no." value={form.flat} onChange={(v) => set("flat", v)} />
            <Field label="Building" value={form.building} onChange={(v) => set("building", v)} />
            <Field label="Area" value={form.area} onChange={(v) => set("area", v)} />
            <Field label="Pincode" value={form.pincode} onChange={(v) => set("pincode", v)} />
          </div>
        </div>

        <button
          disabled={loading}
          className="w-full rounded-full bg-coral py-3 font-bold text-white shadow-toy transition hover:bg-coral-dark disabled:opacity-60"
        >
          {loading ? "Creating account…" : "Create account"}
        </button>
      </form>

      <p className="mt-6 text-ink-soft">
        Already have an account?{" "}
        <Link to="/login" className="font-bold text-teal-dark hover:underline">
          Log in
        </Link>
      </p>
    </div>
  );
}

function Field({
  label,
  value,
  onChange,
  type = "text",
  pattern,
  required,
  full,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  pattern?: string;
  required?: boolean;
  full?: boolean;
}) {
  return (
    <label className={`block ${full ? "col-span-2" : ""}`}>
      <span className="mb-1 block text-sm font-bold text-ink-soft">{label}</span>
      <input
        type={type}
        value={value}
        pattern={pattern}
        required={required}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-coral focus:outline-none"
      />
    </label>
  );
}
