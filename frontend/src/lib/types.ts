export type ToyCondition = "NEW" | "GOOD" | "FAIR" | "POOR";
export type ToyStatus = "AVAILABLE" | "RENTED" | "DAMAGED" | "CLEANING" | "RETIRED";
export type RentalType = "WEEKLY" | "MONTHLY";
export type BookingStatus = "PENDING" | "CONFIRMED" | "ACTIVE" | "RETURNED" | "CANCELLED" | "OVERDUE";
export type PaymentStatus = "PENDING" | "SUCCESS" | "FAILED" | "REFUNDED";
export type PaymentMethod = "UPI" | "COD";

export interface ToyImage {
  id: string;
  url: string;
  primary: boolean;
  sortOrder: number;
}

export interface Toy {
  id: string;
  name: string;
  description: string | null;
  brand: string | null;
  category: string;
  ageGroup: string;
  condition: ToyCondition;
  status: ToyStatus;
  mrp: number;
  weeklyPrice: number;
  monthlyPrice: number;
  depositAmount: number;
  active: boolean;
  images: ToyImage[];
  createdAt: string;
  updatedAt: string;
}

export interface ToyMetadata {
  categories: string[];
  ageGroups: string[];
  conditions: ToyCondition[];
  statuses: ToyStatus[];
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface AvailabilityResponse {
  toyId: string;
  toyName: string;
  status: string;
  available: boolean;
  blockedDates: { bookingId: string; from: string; to: string; reason: string }[];
  nextAvailable: string | null;
  lastUpdated: string;
}

export interface Customer {
  id: string;
  name: string;
  phone: string;
  email: string | null;
  area: string | null;
  flat: string | null;
  building: string | null;
  city: string;
  pincode: string | null;
  createdAt: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  customer: Customer;
}

export interface AdminLoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface Booking {
  id: string;
  toyId: string;
  customerId: string;
  startDate: string;
  endDate: string;
  rentalType: RentalType;
  rentalAmount: number;
  depositAmount: number;
  totalAmount: number;
  status: BookingStatus;
  paymentStatus: PaymentStatus;
  deliveryFlat: string | null;
  deliveryBuilding: string | null;
  deliveryArea: string | null;
  deliveryCity: string | null;
  deliveryPincode: string | null;
  razorpayOrderId: string | null;
  createdAt: string;
}

export interface ToyRequest {
  name: string;
  description: string;
  brand: string;
  category: string;
  ageGroup: string;
  condition: ToyCondition;
  status: ToyStatus;
  mrp: number;
  weeklyPrice: number;
  monthlyPrice: number;
  depositAmount: number;
}

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  correlationId: string;
  path: string;
}
