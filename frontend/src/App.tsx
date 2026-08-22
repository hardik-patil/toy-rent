import { Route, Routes } from "react-router-dom";
import Navbar from "./components/Navbar";
import { AdminRoute, CustomerRoute } from "./components/ProtectedRoute";
import HomePage from "./pages/HomePage";
import ToyDetailPage from "./pages/ToyDetailPage";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import MyBookingsPage from "./pages/MyBookingsPage";
import AdminLoginPage from "./pages/admin/AdminLoginPage";
import AdminInventoryPage from "./pages/admin/AdminInventoryPage";
import AdminToyFormPage from "./pages/admin/AdminToyFormPage";

export default function App() {
  return (
    <div className="flex min-h-screen flex-col bg-cream">
      <Navbar />
      <main className="flex-1">
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/toys/:toyId" element={<ToyDetailPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route
            path="/my-bookings"
            element={
              <CustomerRoute>
                <MyBookingsPage />
              </CustomerRoute>
            }
          />

          <Route path="/admin/login" element={<AdminLoginPage />} />
          <Route
            path="/admin/inventory"
            element={
              <AdminRoute>
                <AdminInventoryPage />
              </AdminRoute>
            }
          />
          <Route
            path="/admin/toys/new"
            element={
              <AdminRoute>
                <AdminToyFormPage />
              </AdminRoute>
            }
          />
          <Route
            path="/admin/toys/:toyId/edit"
            element={
              <AdminRoute>
                <AdminToyFormPage />
              </AdminRoute>
            }
          />
        </Routes>
      </main>
      <footer className="border-t-4 border-ink/5 bg-cream-100 py-6 text-center text-sm font-semibold text-ink-faint">
        🧸 ToyBox Rentals · Navi Mumbai · Playtime without the clutter
      </footer>
    </div>
  );
}
