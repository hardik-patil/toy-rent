export function titleCase(snake: string): string {
  return snake
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

const CATEGORY_EMOJI: Record<string, string> = {
  BUILDING_BLOCKS: "🧱",
  DOLLHOUSES: "🏠",
  INFANT_TOYS: "🍼",
  OUTDOOR_TOYS: "🪁",
  PLAYSETS: "🎡",
  PRETEND_PLAY: "🎭",
  PUZZLES_GAMES: "🧩",
  REMOTE_CONTROL: "🚗",
};

export function categoryEmoji(category: string): string {
  return CATEGORY_EMOJI[category] ?? "🧸";
}

export function formatMoney(value: number): string {
  return `₹${value.toLocaleString("en-IN", { maximumFractionDigits: 0 })}`;
}

const CONDITION_STYLE: Record<string, string> = {
  NEW: "bg-teal-light text-teal-dark",
  GOOD: "bg-sunny-light text-sunny-dark",
  FAIR: "bg-coral-light text-coral-dark",
  POOR: "bg-ink/10 text-ink-soft",
};

export function conditionBadgeClass(condition: string): string {
  return CONDITION_STYLE[condition] ?? "bg-ink/10 text-ink-soft";
}

const STATUS_STYLE: Record<string, string> = {
  AVAILABLE: "bg-teal-light text-teal-dark",
  RENTED: "bg-sunny-light text-sunny-dark",
  DAMAGED: "bg-coral-light text-coral-dark",
  CLEANING: "bg-grape-light text-grape-dark",
  RETIRED: "bg-ink/10 text-ink-soft",
};

export function statusBadgeClass(status: string): string {
  return STATUS_STYLE[status] ?? "bg-ink/10 text-ink-soft";
}

const BOOKING_STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-sunny-light text-sunny-dark",
  CONFIRMED: "bg-teal-light text-teal-dark",
  ACTIVE: "bg-grape-light text-grape-dark",
  RETURNED: "bg-ink/10 text-ink-soft",
  CANCELLED: "bg-coral-light text-coral-dark",
  OVERDUE: "bg-coral text-white",
};

export function bookingStatusBadgeClass(status: string): string {
  return BOOKING_STATUS_STYLE[status] ?? "bg-ink/10 text-ink-soft";
}
