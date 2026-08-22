import { Link, useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/auth";

export default function Navbar() {
  const { role, customer, logout } = useAuthStore();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/");
  }

  return (
    <header className="sticky top-0 z-30 border-b-4 border-ink/5 bg-cream-100/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3 sm:px-6">
        <Link to="/" className="font-display flex items-center gap-2 text-2xl font-bold text-ink">
          <span className="text-3xl">🧸</span>
          <span>
            Toy<span className="text-coral">Box</span>
          </span>
        </Link>

        <nav className="flex items-center gap-2 sm:gap-4">
          <Link
            to="/"
            className="rounded-full px-3 py-1.5 text-sm font-bold text-ink-soft transition hover:bg-teal-light hover:text-teal-dark sm:text-base"
          >
            Browse
          </Link>

          {role === "CUSTOMER" && (
            <Link
              to="/my-bookings"
              className="rounded-full px-3 py-1.5 text-sm font-bold text-ink-soft transition hover:bg-teal-light hover:text-teal-dark sm:text-base"
            >
              My Bookings
            </Link>
          )}

          {role === "CUSTOMER" ? (
            <div className="flex items-center gap-2">
              <span className="hidden text-sm font-bold text-ink-soft sm:inline">
                Hi, {customer?.name.split(" ")[0]}
              </span>
              <button
                onClick={handleLogout}
                className="rounded-full bg-ink/5 px-3 py-1.5 text-sm font-bold text-ink transition hover:bg-ink/10 sm:text-base"
              >
                Log out
              </button>
            </div>
          ) : role === "ADMIN" ? (
            <>
              <Link
                to="/admin/inventory"
                className="rounded-full bg-grape px-3 py-1.5 text-sm font-bold text-white shadow-toy transition hover:bg-grape-dark sm:text-base"
              >
                Inventory
              </Link>
              <button
                onClick={handleLogout}
                className="rounded-full bg-ink/5 px-3 py-1.5 text-sm font-bold text-ink transition hover:bg-ink/10 sm:text-base"
              >
                Log out
              </button>
            </>
          ) : (
            <>
              <Link
                to="/login"
                className="rounded-full px-3 py-1.5 text-sm font-bold text-ink-soft transition hover:bg-teal-light hover:text-teal-dark sm:text-base"
              >
                Log in
              </Link>
              <Link
                to="/register"
                className="rounded-full bg-coral px-4 py-1.5 text-sm font-bold text-white shadow-toy transition hover:bg-coral-dark sm:text-base"
              >
                Sign up
              </Link>
              <Link
                to="/admin/login"
                className="rounded-full px-2 py-1.5 text-xs font-bold text-ink-faint transition hover:text-grape sm:text-sm"
                title="Admin panel"
              >
                Admin
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
