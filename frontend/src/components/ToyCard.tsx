import { Link } from "react-router-dom";
import type { Toy } from "../lib/types";
import { categoryEmoji, conditionBadgeClass, formatMoney, titleCase } from "../lib/format";

export default function ToyCard({ toy }: { toy: Toy }) {
  const primaryImage = toy.images.find((i) => i.primary) ?? toy.images[0];

  return (
    <Link
      to={`/toys/${toy.id}`}
      className="hover-wobble group flex flex-col overflow-hidden rounded-3xl bg-paper shadow-toy transition hover:-translate-y-1 hover:shadow-toy-lg"
    >
      <div className="relative flex aspect-square items-center justify-center overflow-hidden bg-gradient-to-br from-sunny-light via-teal-light to-coral-light">
        {primaryImage ? (
          <img
            src={primaryImage.url}
            alt={toy.name}
            className="h-full w-full object-cover transition duration-300 group-hover:scale-105"
          />
        ) : (
          <span className="text-6xl">{categoryEmoji(toy.category)}</span>
        )}
        <span
          className={`absolute right-3 top-3 rounded-full px-2.5 py-1 text-xs font-bold ${conditionBadgeClass(toy.condition)}`}
        >
          {toy.condition}
        </span>
      </div>

      <div className="flex flex-1 flex-col gap-2 p-4">
        <div className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-ink-faint">
          <span>{categoryEmoji(toy.category)}</span>
          <span>{titleCase(toy.category)}</span>
          <span className="text-ink-faint/50">·</span>
          <span>Ages {toy.ageGroup}</span>
        </div>

        <h3 className="font-display text-lg font-bold leading-snug text-ink">{toy.name}</h3>
        {toy.brand && <p className="text-sm text-ink-soft">{toy.brand}</p>}

        <div className="mt-auto flex items-end justify-between pt-2">
          <div>
            <p className="font-display text-xl font-bold text-coral">
              {formatMoney(toy.weeklyPrice)}
              <span className="text-sm font-normal text-ink-soft">/wk</span>
            </p>
            <p className="text-xs text-ink-faint">or {formatMoney(toy.monthlyPrice)}/mo</p>
          </div>
          <span className="rounded-full bg-teal px-3 py-1.5 text-xs font-bold text-white transition group-hover:bg-teal-dark">
            Rent me →
          </span>
        </div>
      </div>
    </Link>
  );
}
