import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { bookingApi, apiErrorMessage } from "../lib/api";
import type { Booking, PagedResponse } from "../lib/types";
import { bookingStatusBadgeClass, formatMoney } from "../lib/format";

export default function MyBookingsPage() {
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  function load() {
    setLoading(true);
    bookingApi
      .get<PagedResponse<Booking>>("/api/v1/customers/me/bookings", { params: { size: 50 } })
      .then((res) => setBookings(res.data.content))
      .catch((err) => setError(apiErrorMessage(err)))
      .finally(() => setLoading(false));
  }

  useEffect(load, []);

  async function handleCancel(id: string) {
    setCancellingId(id);
    try {
      await bookingApi.put(`/api/v1/bookings/${id}/cancel`, { reason: "Customer changed their mind" });
      load();
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setCancellingId(null);
    }
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 sm:px-6">
      <h1 className="font-display text-3xl font-bold text-ink">My Bookings 📦</h1>

      {error && <p className="mt-4 rounded-xl bg-coral-light p-3 font-semibold text-coral-dark">{error}</p>}

      {loading ? (
        <div className="mt-6 space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-24 animate-pulse rounded-3xl bg-ink/5" />
          ))}
        </div>
      ) : bookings.length === 0 ? (
        <div className="mt-8 rounded-3xl bg-paper p-8 text-center shadow-toy">
          <p className="text-lg font-semibold text-ink-soft">No bookings yet.</p>
          <Link to="/" className="mt-3 inline-block rounded-full bg-coral px-5 py-2.5 font-bold text-white shadow-toy">
            Browse toys
          </Link>
        </div>
      ) : (
        <div className="mt-6 space-y-3">
          {bookings.map((b) => (
            <div key={b.id} className="rounded-3xl bg-paper p-5 shadow-toy">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="font-display text-lg font-bold text-ink">
                    <Link to={`/toys/${b.toyId}`} className="hover:underline">
                      Toy {b.toyId}
                    </Link>
                  </p>
                  <p className="text-sm text-ink-soft">
                    {b.startDate} → {b.endDate} · {b.rentalType.toLowerCase()}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <span className={`rounded-full px-3 py-1 text-xs font-bold ${bookingStatusBadgeClass(b.status)}`}>
                    {b.status}
                  </span>
                  <span className={`rounded-full px-3 py-1 text-xs font-bold ${bookingStatusBadgeClass(b.paymentStatus)}`}>
                    payment: {b.paymentStatus}
                  </span>
                </div>
              </div>

              <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t-2 border-ink/5 pt-3">
                <span className="font-bold text-ink">
                  {formatMoney(b.totalAmount)}{" "}
                  <span className="font-normal text-ink-faint">(incl. {formatMoney(b.depositAmount)} deposit)</span>
                </span>
                {(b.status === "PENDING" || b.status === "CONFIRMED") && (
                  <button
                    onClick={() => handleCancel(b.id)}
                    disabled={cancellingId === b.id}
                    className="rounded-full bg-ink/5 px-4 py-1.5 text-sm font-bold text-coral-dark transition hover:bg-coral-light disabled:opacity-50"
                  >
                    {cancellingId === b.id ? "Cancelling…" : "Cancel booking"}
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
