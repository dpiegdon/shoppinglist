import { useEffect, useState } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router-dom";
import { isClientOutdated, onClientOutdated } from "./api/protocol";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import AppShell from "./components/AppShell";
import UpdateRequiredNotice from "./components/UpdateRequiredNotice";
import LoginPage from "./pages/LoginPage";
import OverviewPage from "./pages/OverviewPage";
import ListRoute from "./pages/ListRoute";
import BalancesPage from "./pages/BalancesPage";
import RegistryPage from "./pages/RegistryPage";
import ListPropsPage from "./pages/ListPropsPage";
import SettingsPage from "./pages/SettingsPage";
import AdminPage from "./pages/AdminPage";
import AboutPage from "./pages/AboutPage";
import RedeemPage from "./pages/RedeemPage";

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { account } = useAuth();
  const location = useLocation();
  if (!account) {
    // Preserve where the user was headed (incl. /redeem?token=...) so login can return them there,
    // instead of dropping them on the overview and silently losing the invite/deep link (T-43).
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <AppShell>{children}</AppShell>;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <OverviewPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/list/:listId"
        element={
          <ProtectedRoute>
            <ListRoute />
          </ProtectedRoute>
        }
      />
      <Route
        path="/list/:listId/balances"
        element={
          <ProtectedRoute>
            <BalancesPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/list/:listId/registry"
        element={
          <ProtectedRoute>
            <RegistryPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/list/:listId/properties"
        element={
          <ProtectedRoute>
            <ListPropsPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/settings"
        element={
          <ProtectedRoute>
            <SettingsPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/admin"
        element={
          <ProtectedRoute>
            <AdminPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/about"
        element={
          <ProtectedRoute>
            <AboutPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/redeem"
        element={
          <ProtectedRoute>
            <RedeemPage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  // This bundle is older than the server's protocol and reloading has already been tried (T-240).
  // Seeded from the module rather than starting false: the refusal can arrive before this mounts.
  const [outdated, setOutdated] = useState(isClientOutdated);

  useEffect(() => {
    onClientOutdated(() => setOutdated(true));
    return () => onClientOutdated(null);
  }, []);

  // Replaces the routes, so the sync hook inside them unmounts and the syncing stops with them.
  if (outdated) {
    return <UpdateRequiredNotice />;
  }

  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
