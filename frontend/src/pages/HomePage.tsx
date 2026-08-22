import { useEffect, useState } from "react";
import { toyApi } from "../lib/api";
import type { PagedResponse, Toy, ToyMetadata } from "../lib/types";
import ToyCard from "../components/ToyCard";
import { titleCase } from "../lib/format";

export default function HomePage() {
  const [metadata, setMetadata] = useState<ToyMetadata | null>(null);
  const [toys, setToys] = useState<Toy[]>([]);
  const [totalPages, setTotalPages] = useState(1);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("");
  const [ageGroup, setAgeGroup] = useState("");

  useEffect(() => {
    toyApi.get<ToyMetadata>("/api/v1/toys/metadata").then((res) => setMetadata(res.data));
  }, []);

  useEffect(() => {
    setLoading(true);
    setError(null);

    const params: Record<string, string | number> = { page, size: 12 };
    if (category) params.category = category;

    const request = query.trim()
      ? toyApi.get<PagedResponse<Toy>>("/api/v1/toys/search", {
          params: { ...params, q: query.trim(), age: ageGroup || undefined },
        })
      : toyApi.get<PagedResponse<Toy>>("/api/v1/toys", {
          params: { ...params, ageGroup: ageGroup || undefined },
        });

    request
      .then((res) => {
        setToys(res.data.content);
        setTotalPages(res.data.totalPages);
      })
      .catch(() => setError("Couldn't load toys right now — is toy-service running?"))
      .finally(() => setLoading(false));
  }, [page, query, category, ageGroup]);

  return (
    <div>
      <section className="relative overflow-hidden bg-gradient-to-br from-coral via-sunny to-teal">
        <div className="mx-auto flex max-w-6xl flex-col items-start gap-4 px-6 py-16 text-white sm:py-20">
          <span className="rounded-full bg-white/20 px-4 py-1.5 text-sm font-bold backdrop-blur">
            🚚 Free delivery across Navi Mumbai
          </span>
          <h1 className="font-display max-w-2xl text-4xl font-bold leading-tight drop-shadow-sm sm:text-5xl">
            Playtime, delivered. Toys rented, not landfilled.
          </h1>
          <p className="max-w-xl text-lg font-semibold text-white/90">
            Browse hundreds of premium toys, rent by the week or month, and swap for something new whenever
            the fun wears off.
          </p>
        </div>
        <div className="pointer-events-none absolute -bottom-10 -right-10 text-[10rem] opacity-20 sm:text-[14rem]">
          🧸
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <div className="mb-8 flex flex-col gap-3 rounded-3xl bg-paper p-4 shadow-toy sm:flex-row sm:items-center">
          <input
            type="search"
            value={query}
            onChange={(e) => {
              setPage(0);
              setQuery(e.target.value);
            }}
            placeholder="Search toys, e.g. LEGO, dollhouse, puzzle…"
            className="flex-1 rounded-full border-2 border-ink/10 px-4 py-2.5 font-semibold text-ink placeholder:text-ink-faint focus:border-coral focus:outline-none"
          />
          <select
            value={category}
            onChange={(e) => {
              setPage(0);
              setCategory(e.target.value);
            }}
            className="rounded-full border-2 border-ink/10 px-4 py-2.5 font-semibold text-ink focus:border-teal focus:outline-none"
          >
            <option value="">All categories</option>
            {metadata?.categories.map((c) => (
              <option key={c} value={c}>
                {titleCase(c)}
              </option>
            ))}
          </select>
          <select
            value={ageGroup}
            onChange={(e) => {
              setPage(0);
              setAgeGroup(e.target.value);
            }}
            className="rounded-full border-2 border-ink/10 px-4 py-2.5 font-semibold text-ink focus:border-teal focus:outline-none"
          >
            <option value="">All ages</option>
            {metadata?.ageGroups.map((a) => (
              <option key={a} value={a}>
                Ages {a}
              </option>
            ))}
          </select>
        </div>

        {error && (
          <p className="rounded-2xl bg-coral-light p-4 text-center font-semibold text-coral-dark">{error}</p>
        )}

        {loading ? (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {Array.from({ length: 8 }).map((_, i) => (
              <div key={i} className="aspect-[3/4] animate-pulse rounded-3xl bg-ink/5" />
            ))}
          </div>
        ) : toys.length === 0 ? (
          <p className="py-16 text-center text-lg font-semibold text-ink-soft">
            No toys found. Try a different search or filter 🔍
          </p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
              {toys.map((toy) => (
                <ToyCard key={toy.id} toy={toy} />
              ))}
            </div>

            {totalPages > 1 && (
              <div className="mt-8 flex items-center justify-center gap-3">
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
          </>
        )}
      </section>
    </div>
  );
}
