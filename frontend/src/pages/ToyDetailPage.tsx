import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { bookingApi, toyApi, apiErrorMessage } from "../lib/api";
import type { AvailabilityResponse, Booking, RentalType, Toy } from "../lib/types";
import { useAuthStore } from "../store/auth";
import { categoryEmoji, conditionBadgeClass, formatMoney, titleCase } from "../lib/format";

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function addDaysIso(iso: string, days: number): string {
  const d = new Date(iso);
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

export default function ToyDetailPage() {
  const { toyId } = useParams<{ toyId: string }>();
  const { role, customer } = useAuthStore();

  const [toy, setToy] = useState<Toy | null>(null);
  const [activeImage, setActiveImage] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [rentalType, setRentalType] = useState<RentalType>("WEEKLY");
  const [startDate, setStartDate] = useState(todayIso());
  const [endDate, setEndDate] = useState(addDaysIso(todayIso(), 7));

  const [availability, setAvailability] = useState<AvailabilityResponse | null>(null);
  const [checkingAvailability, setCheckingAvailability] = useState(false);

  const [address, setAddress] = useState({
    deliveryFlat: "",
    deliveryBuilding: "",
    deliveryArea: "",
    deliveryCity: "Navi Mumbai",
    deliveryPincode: "",
  });

  const [booking, setBooking] = useState<Booking | null>(null);
  const [bookingError, setBookingError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [paying, setPaying] = useState(false);

  useEffect(() => {
    if (!toyId) return;
    toyApi
      .get<Toy>(`/api/v1/toys/${toyId}`)
      .catch(() => setLoadError("Couldn't find that toy."))
      .then((res) => res && setToy(res.data));
  }, [toyId]);

  useEffect(() => {
    if (customer) {
      setAddress({
        deliveryFlat: customer.flat ?? "",
        deliveryBuilding: customer.building ?? "",
        deliveryArea: customer.area ?? "",
        deliveryCity: customer.city ?? "Navi Mumbai",
        deliveryPincode: customer.pincode ?? "",
      });
    }
  }, [customer]);

  useEffect(() => {
    if (!toyId || !startDate || !endDate || endDate < startDate) {
      setAvailability(null);
      return;
    }
    setCheckingAvailability(true);
    toyApi
      .get<AvailabilityResponse>(`/api/v1/toys/${toyId}/availability`, { params: { from: startDate, to: endDate } })
      .then((res) => setAvailability(res.data))
      .catch(() => setAvailability(null))
      .finally(() => setCheckingAvailability(false));
  }, [toyId, startDate, endDate]);

  useEffect(() => {
    // keep endDate sensible when switching rental type
    if (rentalType === "WEEKLY") setEndDate(addDaysIso(startDate, 7));
    else setEndDate(addDaysIso(startDate, 30));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rentalType, startDate]);

  async function handleBook(e: React.FormEvent) {
    e.preventDefault();
    if (!toyId) return;
    setSubmitting(true);
    setBookingError(null);
    try {
      const res = await bookingApi.post<Booking>("/api/v1/bookings", {
        toyId,
        startDate,
        endDate,
        rentalType,
        ...address,
      });
      setBooking(res.data);
    } catch (err) {
      setBookingError(apiErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSimulatePayment() {
    if (!booking?.razorpayOrderId) return;
    setPaying(true);
    setBookingError(null);
    try {
      // Dev-only stand-in for a real checkout redirect: WireMock's Razorpay stub always
      // returns these fixed ids (see wiremock/mappings/razorpay-*-stub.json), and the
      // webhook just correlates them back to this booking's pending payment.
      await bookingApi.post("/api/v1/payments/webhook", {
        razorpay_order_id: booking.razorpayOrderId,
        razorpay_payment_id: "pay_mock456",
        razorpay_signature: "mock_signature_abc",
      });
      setBooking({ ...booking, status: "CONFIRMED", paymentStatus: "SUCCESS" });
    } catch (err) {
      setBookingError(apiErrorMessage(err));
    } finally {
      setPaying(false);
    }
  }

  if (loadError) {
    return <p className="mx-auto max-w-2xl px-4 py-16 text-center text-lg font-semibold text-coral-dark">{loadError}</p>;
  }

  if (!toy) {
    return (
      <div className="mx-auto max-w-5xl px-4 py-16">
        <div className="h-96 animate-pulse rounded-3xl bg-ink/5" />
      </div>
    );
  }

  const images = toy.images.length > 0 ? toy.images : null;
  const price = rentalType === "WEEKLY" ? toy.weeklyPrice : toy.monthlyPrice;

  return (
    <div className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
      <div className="grid gap-8 md:grid-cols-2">
        <div>
          <div className="flex aspect-square items-center justify-center overflow-hidden rounded-3xl bg-gradient-to-br from-sunny-light via-teal-light to-coral-light shadow-toy">
            {images ? (
              <img src={images[activeImage].url} alt={toy.name} className="h-full w-full object-cover" />
            ) : (
              <span className="text-8xl">{categoryEmoji(toy.category)}</span>
            )}
          </div>
          {images && images.length > 1 && (
            <div className="mt-3 flex gap-2">
              {images.map((img, i) => (
                <button
                  key={img.id}
                  onClick={() => setActiveImage(i)}
                  className={`h-16 w-16 overflow-hidden rounded-xl border-2 ${i === activeImage ? "border-coral" : "border-transparent"}`}
                >
                  <img src={img.url} alt="" className="h-full w-full object-cover" />
                </button>
              ))}
            </div>
          )}
        </div>

        <div>
          <div className="flex items-center gap-2 text-sm font-bold uppercase tracking-wide text-ink-faint">
            <span>{categoryEmoji(toy.category)}</span>
            <span>{titleCase(toy.category)}</span>
            <span>·</span>
            <span>Ages {toy.ageGroup}</span>
          </div>
          <h1 className="font-display mt-1 text-3xl font-bold text-ink">{toy.name}</h1>
          {toy.brand && <p className="mt-1 font-semibold text-ink-soft">by {toy.brand}</p>}

          <div className="mt-3 flex flex-wrap gap-2">
            <span className={`rounded-full px-3 py-1 text-xs font-bold ${conditionBadgeClass(toy.condition)}`}>
              Condition: {toy.condition}
            </span>
            <span className="rounded-full bg-ink/5 px-3 py-1 text-xs font-bold text-ink-soft">
              MRP {formatMoney(toy.mrp)}
            </span>
          </div>

          {toy.description && <p className="mt-4 leading-relaxed text-ink-soft">{toy.description}</p>}

          <div className="mt-6 rounded-3xl bg-paper p-5 shadow-toy">
            <div className="mb-4 flex gap-3">
              <label
                className={`flex-1 cursor-pointer rounded-2xl border-2 p-3 text-center font-bold transition ${
                  rentalType === "WEEKLY" ? "border-coral bg-coral-light text-coral-dark" : "border-ink/10 text-ink-soft"
                }`}
              >
                <input
                  type="radio"
                  name="rentalType"
                  value="WEEKLY"
                  checked={rentalType === "WEEKLY"}
                  onChange={() => setRentalType("WEEKLY")}
                  className="sr-only"
                />
                Weekly · {formatMoney(toy.weeklyPrice)}
              </label>
              <label
                className={`flex-1 cursor-pointer rounded-2xl border-2 p-3 text-center font-bold transition ${
                  rentalType === "MONTHLY" ? "border-coral bg-coral-light text-coral-dark" : "border-ink/10 text-ink-soft"
                }`}
              >
                <input
                  type="radio"
                  name="rentalType"
                  value="MONTHLY"
                  checked={rentalType === "MONTHLY"}
                  onChange={() => setRentalType("MONTHLY")}
                  className="sr-only"
                />
                Monthly · {formatMoney(toy.monthlyPrice)}
              </label>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <label className="block">
                <span className="mb-1 block text-xs font-bold text-ink-soft">Start date</span>
                <input
                  type="date"
                  min={todayIso()}
                  value={startDate}
                  onChange={(e) => setStartDate(e.target.value)}
                  className="w-full rounded-xl border-2 border-ink/10 px-3 py-2 font-semibold focus:border-coral focus:outline-none"
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs font-bold text-ink-soft">End date</span>
                <input
                  type="date"
                  min={startDate}
                  value={endDate}
                  onChange={(e) => setEndDate(e.target.value)}
                  className="w-full rounded-xl border-2 border-ink/10 px-3 py-2 font-semibold focus:border-coral focus:outline-none"
                />
              </label>
            </div>

            <div className="mt-3 min-h-6 text-sm font-semibold">
              {checkingAvailability ? (
                <span className="text-ink-faint">Checking availability…</span>
              ) : availability?.available === false ? (
                <span className="text-coral-dark">
                  😕 Not available for these dates
                  {availability.nextAvailable && ` — next free from ${availability.nextAvailable}`}
                </span>
              ) : availability?.available ? (
                <span className="text-teal-dark">✓ Available for these dates</span>
              ) : null}
            </div>

            <div className="mt-4 flex items-center justify-between border-t-2 border-ink/5 pt-4">
              <span className="text-sm font-bold text-ink-soft">Refundable deposit</span>
              <span className="font-bold text-ink">{formatMoney(toy.depositAmount)}</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm font-bold text-ink-soft">Rental ({rentalType.toLowerCase()})</span>
              <span className="font-bold text-ink">{formatMoney(price)}</span>
            </div>

            {role !== "CUSTOMER" ? (
              <Link
                to="/login"
                className="mt-4 block rounded-full bg-coral py-3 text-center font-bold text-white shadow-toy transition hover:bg-coral-dark"
              >
                Log in to rent this toy
              </Link>
            ) : !booking ? (
              <form onSubmit={handleBook}>
                <div className="mt-4 grid grid-cols-2 gap-2">
                  <AddressField
                    placeholder="Flat / House no."
                    value={address.deliveryFlat}
                    onChange={(v) => setAddress((a) => ({ ...a, deliveryFlat: v }))}
                  />
                  <AddressField
                    placeholder="Building"
                    value={address.deliveryBuilding}
                    onChange={(v) => setAddress((a) => ({ ...a, deliveryBuilding: v }))}
                  />
                  <AddressField
                    placeholder="Area"
                    value={address.deliveryArea}
                    onChange={(v) => setAddress((a) => ({ ...a, deliveryArea: v }))}
                  />
                  <AddressField
                    placeholder="Pincode"
                    value={address.deliveryPincode}
                    onChange={(v) => setAddress((a) => ({ ...a, deliveryPincode: v }))}
                  />
                </div>

                {bookingError && (
                  <p className="mt-3 rounded-xl bg-coral-light p-3 text-sm font-semibold text-coral-dark">
                    {bookingError}
                  </p>
                )}

                <button
                  disabled={submitting || availability?.available === false}
                  className="mt-4 w-full rounded-full bg-coral py-3 font-bold text-white shadow-toy transition hover:bg-coral-dark disabled:opacity-50"
                >
                  {submitting ? "Booking…" : `Rent for ${formatMoney(price)}`}
                </button>
              </form>
            ) : booking.status === "CONFIRMED" ? (
              <div className="mt-4 rounded-2xl bg-teal-light p-4 text-center">
                <p className="font-bold text-teal-dark">🎉 Booking confirmed!</p>
                <Link to="/my-bookings" className="mt-2 inline-block font-bold text-teal-dark underline">
                  View my bookings
                </Link>
              </div>
            ) : (
              <div className="mt-4 rounded-2xl bg-sunny-light p-4 text-center">
                <p className="font-bold text-sunny-dark">Booking created — payment pending</p>
                <p className="mt-1 text-sm text-ink-soft">
                  This dev environment stubs Razorpay via WireMock — simulate the payment to confirm.
                </p>
                {bookingError && <p className="mt-2 text-sm font-semibold text-coral-dark">{bookingError}</p>}
                <button
                  onClick={handleSimulatePayment}
                  disabled={paying}
                  className="mt-3 w-full rounded-full bg-teal py-3 font-bold text-white shadow-toy transition hover:bg-teal-dark disabled:opacity-60"
                >
                  {paying ? "Confirming…" : `Simulate payment of ${formatMoney(booking.totalAmount)}`}
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function AddressField({
  placeholder,
  value,
  onChange,
}: {
  placeholder: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <input
      required
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded-xl border-2 border-ink/10 px-3 py-2 text-sm font-semibold focus:border-coral focus:outline-none"
    />
  );
}
