import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { toyApi, apiErrorMessage } from "../../lib/api";
import type { PagedResponse, Toy } from "../../lib/types";
import { categoryEmoji, conditionBadgeClass, formatMoney, statusBadgeClass, titleCase } from "../../lib/format";

export default function AdminInventoryPage() {
  const [toys, setToys] = useState<Toy[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  function load() {
    setLoading(true);
    toyApi
      .get<PagedResponse<Toy>>("/api/v1/admin/toys/inventory", { params: { page, size: 15 } })
      .then((res) => {
        setToys(res.data.content);
        setTotalPages(res.data.totalPages);
        setTotalElements(res.data.totalElements);
      })
      .catch((err) => setError(apiErrorMessage(err)))
      .finally(() => setLoading(false));
  }

  useEffect(load, [page]);

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-3xl font-bold text-ink">Inventory 📋</h1>
          <p className="text-ink-soft">{totalElements} toys in the catalogue</p>
        </div>
        <Link
          to="/admin/toys/new"
          className="rounded-full bg-grape px-5 py-2.5 font-bold text-white shadow-toy transition hover:bg-grape-dark"
        >
          + Add toy
        </Link>
      </div>

      {error && <p className="mt-4 rounded-xl bg-coral-light p-3 font-semibold text-coral-dark">{error}</p>}

      <div className="mt-6 overflow-x-auto rounded-3xl bg-paper shadow-toy">
        <table className="w-full min-w-[720px] text-left">
          <thead>
            <tr className="border-b-2 border-ink/5 text-xs font-bold uppercase tracking-wide text-ink-faint">
              <th className="px-4 py-3">Toy</th>
              <th className="px-4 py-3">Category</th>
              <th className="px-4 py-3">Age</th>
              <th className="px-4 py-3">Condition</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Weekly</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-ink-faint">
                  Loading…
                </td>
              </tr>
            ) : toys.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-ink-faint">
                  No toys yet.
                </td>
              </tr>
            ) : (
              toys.map((toy) => {
                const primaryImage = toy.images.find((i) => i.primary) ?? toy.images[0];
                return (
                  <tr key={toy.id} className="border-b border-ink/5 last:border-0 hover:bg-cream/60">
                    <td className="flex items-center gap-3 px-4 py-3">
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-teal-light text-lg">
                        {primaryImage ? (
                          <img src={primaryImage.url} alt="" className="h-full w-full object-cover" />
                        ) : (
                          categoryEmoji(toy.category)
                        )}
                      </div>
                      <div>
                        <p className="font-bold text-ink">{toy.name}</p>
                        <p className="text-xs text-ink-faint">{toy.id}</p>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-sm font-semibold text-ink-soft">{titleCase(toy.category)}</td>
                    <td className="px-4 py-3 text-sm font-semibold text-ink-soft">{toy.ageGroup}</td>
                    <td className="px-4 py-3">
                      <span className={`rounded-full px-2.5 py-1 text-xs font-bold ${conditionBadgeClass(toy.condition)}`}>
                        {toy.condition}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`rounded-full px-2.5 py-1 text-xs font-bold ${statusBadgeClass(toy.status)}`}>
                        {toy.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-sm font-bold text-ink">{formatMoney(toy.weeklyPrice)}</td>
                    <td className="px-4 py-3 text-right">
                      <Link
                        to={`/admin/toys/${toy.id}/edit`}
                        className="rounded-full bg-ink/5 px-3 py-1.5 text-sm font-bold text-grape-dark hover:bg-grape-light"
                      >
                        Edit
                      </Link>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="mt-6 flex items-center justify-center gap-3">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="rounded-full bg-paper px-4 py-2 font-bold text-ink shadow-toy disabled:opacity-40"
          >
            ← Prev
          </button>
          <span className="font-bold text-ink-soft">
            Page {page + 1} of {totalPages}
          </span>
          <button
            disabled={page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
            className="rounded-full bg-paper px-4 py-2 font-bold text-ink shadow-toy disabled:opacity-40"
          >
            Next →
          </button>
        </div>
      )}
    </div>
  );
}
