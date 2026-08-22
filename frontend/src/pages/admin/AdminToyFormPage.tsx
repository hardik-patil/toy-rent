import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { toyApi, apiErrorMessage } from "../../lib/api";
import type { Toy, ToyCondition, ToyMetadata, ToyRequest, ToyStatus } from "../../lib/types";
import { titleCase } from "../../lib/format";

const NEW_CATEGORY = "__new_category__";
const NEW_AGE_GROUP = "__new_age_group__";

const emptyForm: ToyRequest = {
  name: "",
  description: "",
  brand: "",
  category: "",
  ageGroup: "",
  condition: "GOOD",
  status: "AVAILABLE",
  mrp: 0,
  weeklyPrice: 0,
  monthlyPrice: 0,
  depositAmount: 0,
};

export default function AdminToyFormPage() {
  const { toyId } = useParams<{ toyId: string }>();
  const isEdit = Boolean(toyId);
  const navigate = useNavigate();

  const [metadata, setMetadata] = useState<ToyMetadata | null>(null);
  const [form, setForm] = useState<ToyRequest>(emptyForm);
  const [customCategory, setCustomCategory] = useState("");
  const [customAgeGroup, setCustomAgeGroup] = useState("");
  const [images, setImages] = useState<Toy["images"]>([]);

  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedToyId, setSavedToyId] = useState<string | null>(toyId ?? null);

  const [uploading, setUploading] = useState(false);
  const [uploadPrimary, setUploadPrimary] = useState(true);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    toyApi.get<ToyMetadata>("/api/v1/toys/metadata").then((res) => setMetadata(res.data));
  }, []);

  useEffect(() => {
    if (!toyId) return;
    toyApi
      .get<Toy>(`/api/v1/toys/${toyId}`)
      .then((res) => {
        const t = res.data;
        setForm({
          name: t.name,
          description: t.description ?? "",
          brand: t.brand ?? "",
          category: t.category,
          ageGroup: t.ageGroup,
          condition: t.condition,
          status: t.status,
          mrp: t.mrp,
          weeklyPrice: t.weeklyPrice,
          monthlyPrice: t.monthlyPrice,
          depositAmount: t.depositAmount,
        });
        setImages(t.images);
      })
      .catch((err) => setError(apiErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [toyId]);

  function set<K extends keyof ToyRequest>(key: K, value: ToyRequest[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);

    const payload: ToyRequest = {
      ...form,
      category: form.category === NEW_CATEGORY ? customCategory.trim() : form.category,
      ageGroup: form.ageGroup === NEW_AGE_GROUP ? customAgeGroup.trim() : form.ageGroup,
    };

    try {
      if (isEdit && savedToyId) {
        await toyApi.put<Toy>(`/api/v1/toys/${savedToyId}`, payload);
      } else {
        const res = await toyApi.post<Toy>("/api/v1/toys", payload);
        setSavedToyId(res.data.id);
        setImages(res.data.images);
        navigate(`/admin/toys/${res.data.id}/edit`, { replace: true });
        return;
      }
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault();
    const file = fileInputRef.current?.files?.[0];
    if (!file || !savedToyId) return;

    setUploading(true);
    setError(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("primary", String(uploadPrimary));
      formData.append("sortOrder", String(images.length));
      const res = await toyApi.post<Toy["images"][number]>(
        `/api/v1/toys/${savedToyId}/images/upload`,
        formData
      );
      setImages((imgs) => [...imgs, res.data]);
      if (fileInputRef.current) fileInputRef.current.value = "";
    } catch (err) {
      setError(apiErrorMessage(err));
    } finally {
      setUploading(false);
    }
  }

  if (loading) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <div className="h-96 animate-pulse rounded-3xl bg-ink/5" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 sm:px-6">
      <h1 className="font-display text-3xl font-bold text-ink">
        {isEdit ? "Edit toy ✏️" : "Add a new toy 🧸"}
      </h1>

      {error && <p className="mt-4 rounded-xl bg-coral-light p-3 font-semibold text-coral-dark">{error}</p>}

      <form onSubmit={handleSubmit} className="mt-6 space-y-6 rounded-3xl bg-paper p-6 shadow-toy">
        <div className="grid grid-cols-2 gap-4">
          <label className="col-span-2 block">
            <span className="mb-1 block text-sm font-bold text-ink-soft">Name</span>
            <input
              required
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-sm font-bold text-ink-soft">Brand</span>
            <input
              value={form.brand}
              onChange={(e) => set("brand", e.target.value)}
              className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
            />
          </label>

          {/* Dropdown fetched from the DB via GET /api/v1/toys/metadata, not hardcoded. */}
          <label className="block">
            <span className="mb-1 block text-sm font-bold text-ink-soft">Category</span>
            <select
              required
              value={form.category}
              onChange={(e) => set("category", e.target.value)}
              className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
            >
              <option value="" disabled>
                Select a category…
              </option>
              {metadata?.categories.map((c) => (
                <option key={c} value={c}>
                  {titleCase(c)}
                </option>
              ))}
              <option value={NEW_CATEGORY}>+ New category…</option>
            </select>
            {form.category === NEW_CATEGORY && (
              <input
                required
                placeholder="e.g. MUSICAL_INSTRUMENTS"
                value={customCategory}
                onChange={(e) => setCustomCategory(e.target.value.toUpperCase().replace(/\s+/g, "_"))}
                className="mt-2 w-full rounded-2xl border-2 border-grape/40 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
              />
            )}
          </label>

          {/* Also fetched from the DB via /metadata (distinct ageGroups already in the catalogue). */}
          <label className="block">
            <span className="mb-1 block text-sm font-bold text-ink-soft">Age group</span>
            <select
              required
              value={form.ageGroup}
              onChange={(e) => set("ageGroup", e.target.value)}
              className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
            >
              <option value="" disabled>
                Select an age group…
              </option>
              {metadata?.ageGroups.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
              <option value={NEW_AGE_GROUP}>+ New age group…</option>
            </select>
            {form.ageGroup === NEW_AGE_GROUP && (
              <input
                required
                placeholder="e.g. 4-10"
                value={customAgeGroup}
                onChange={(e) => setCustomAgeGroup(e.target.value)}
                className="mt-2 w-full rounded-2xl border-2 border-grape/40 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
              />
            )}
          </label>

          <label className="col-span-2 block">
            <span className="mb-1 block text-sm font-bold text-ink-soft">Description</span>
            <textarea
              value={form.description}
              onChange={(e) => set("description", e.target.value)}
              rows={3}
              className="w-full rounded-2xl border-2 border-ink/10 px-4 py-2.5 font-semibold focus:border-grape focus:outline-none"
            />
          </label>
        </div>

        {/* Radio buttons, options fetched from the DB via /metadata's fixed enum lists. */}
        <div>
          <span className="mb-2 block text-sm font-bold text-ink-soft">Condition</span>
          <div className="flex flex-wrap gap-2">
            {metadata?.conditions.map((c) => (
              <RadioPill key={c} name="condition" value={c} current={form.condition} onChange={(v) => set("condition", v as ToyCondition)} />
            ))}
          </div>
        </div>

        <div>
          <span className="mb-2 block text-sm font-bold text-ink-soft">Status</span>
          <div className="flex flex-wrap gap-2">
            {metadata?.statuses.map((s) => (
              <RadioPill key={s} name="status" value={s} current={form.status} onChange={(v) => set("status", v as ToyStatus)} />
            ))}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <MoneyField label="MRP" value={form.mrp} onChange={(v) => set("mrp", v)} />
          <MoneyField label="Weekly price" value={form.weeklyPrice} onChange={(v) => set("weeklyPrice", v)} />
          <MoneyField label="Monthly price" value={form.monthlyPrice} onChange={(v) => set("monthlyPrice", v)} />
          <MoneyField label="Deposit" value={form.depositAmount} onChange={(v) => set("depositAmount", v)} />
        </div>

        <button
          disabled={saving}
          className="w-full rounded-full bg-grape py-3 font-bold text-white shadow-toy transition hover:bg-grape-dark disabled:opacity-60"
        >
          {saving ? "Saving…" : isEdit ? "Save changes" : "Create toy"}
        </button>
      </form>

      <div className="mt-6 rounded-3xl bg-paper p-6 shadow-toy">
        <h2 className="font-display text-xl font-bold text-ink">Photos 📸</h2>

        {!savedToyId ? (
          <p className="mt-2 text-ink-soft">Save the toy first, then you can upload photos.</p>
        ) : (
          <>
            {images.length > 0 && (
              <div className="mt-4 flex flex-wrap gap-3">
                {images.map((img) => (
                  <div key={img.id} className="relative h-24 w-24 overflow-hidden rounded-2xl border-2 border-ink/10">
                    <img src={img.url} alt="" className="h-full w-full object-cover" />
                    {img.primary && (
                      <span className="absolute bottom-0 left-0 right-0 bg-teal py-0.5 text-center text-[10px] font-bold text-white">
                        Primary
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}

            <form onSubmit={handleUpload} className="mt-4 flex flex-wrap items-center gap-3">
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                required
                className="text-sm font-semibold text-ink-soft file:mr-3 file:rounded-full file:border-0 file:bg-grape-light file:px-4 file:py-2 file:font-bold file:text-grape-dark hover:file:bg-grape/20"
              />
              <label className="flex items-center gap-1.5 text-sm font-bold text-ink-soft">
                <input
                  type="checkbox"
                  checked={uploadPrimary}
                  onChange={(e) => setUploadPrimary(e.target.checked)}
                  className="h-4 w-4 accent-grape"
                />
                Set as primary
              </label>
              <button
                disabled={uploading}
                className="rounded-full bg-teal px-4 py-2 text-sm font-bold text-white shadow-toy transition hover:bg-teal-dark disabled:opacity-60"
              >
                {uploading ? "Uploading…" : "Upload photo"}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}

function RadioPill({
  name,
  value,
  current,
  onChange,
}: {
  name: string;
  value: string;
  current: string;
  onChange: (v: string) => void;
}) {
  const active = current === value;
  return (
    <label
      className={`cursor-pointer rounded-full border-2 px-4 py-2 text-sm font-bold transition ${
        active ? "border-grape bg-grape-light text-grape-dark" : "border-ink/10 text-ink-soft"
      }`}
    >
      <input
        type="radio"
        name={name}
        value={value}
        checked={active}
        onChange={() => onChange(value)}
        className="sr-only"
      />
      {value}
    </label>
  );
}

function MoneyField({ label, value, onChange }: { label: string; value: number; onChange: (v: number) => void }) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-bold text-ink-soft">{label}</span>
      <input
        required
        type="number"
        min={0}
        step="0.01"
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        className="w-full rounded-2xl border-2 border-ink/10 px-3 py-2.5 font-semibold focus:border-grape focus:outline-none"
      />
    </label>
  );
}
