import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import AppShell from "./components/AppShell";
import LoginPage from "./pages/LoginPage";
import OverviewPage from "./pages/OverviewPage";
import ListPage from "./pages/ListPage";
import RegistryPage from "./pages/RegistryPage";
import ListPropsPage from "./pages/ListPropsPage";
import SettingsPage from "./pages/SettingsPage";
import RedeemPage from "./pages/RedeemPage";

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { account } = useAuth();
  if (!account) {
    return <Navigate to="/login" replace />;
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
            <ListPage />
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
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
