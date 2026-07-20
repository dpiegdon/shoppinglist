import { useEffect, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import type { AdminUser } from "../api/contract";

/**
 * Admin-only server console (T-107): toggle registration for this run, reset a user's password,
 * delete a user. Reached from Settings; gated on the login response's is_admin. Destructive actions
 * re-verify the admin's own password (entered once below), matching the server's step-up check.
 */
export default function AdminPage() {
  const { account } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [allowRegistration, setAllowRegistration] = useState<boolean | null>(null);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [resetResult, setResetResult] = useState<{ email: string; password: string } | null>(null);

  async function load() {
    try {
      const [usersResp, settings] = await Promise.all([api.getAdminUsers(), api.getServerSettings()]);
      setUsers(usersResp.users);
      setAllowRegistration(settings.allow_registration);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load admin data.");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Non-admins (or a stale stored account) never see this page's data.
  if (account && !account.isAdmin) return <Navigate to="/" replace />;

  async function toggleRegistration() {
    if (allowRegistration === null) return;
    setError(null);
    try {
      const result = await api.setServerSettings(!allowRegistration);
      setAllowRegistration(result.allow_registration);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to update.");
    }
  }

  async function resetPassword(user: AdminUser) {
    if (!password) return setError("Enter your password first.");
    setError(null);
    setResetResult(null);
    try {
      const result = await api.adminResetPassword(user.id, password);
      setResetResult({ email: user.email, password: result.password });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to reset password.");
    }
  }

  async function deleteUser(user: AdminUser) {
    if (!password) return setError("Enter your password first.");
    if (!confirm(`Delete ${user.email} and all their data? This cannot be undone.`)) return;
    setError(null);
    try {
      await api.adminDeleteUser(user.id, password);
      setUsers((prev) => prev.filter((u) => u.id !== user.id));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete user.");
    }
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <Link to="/settings" className="muted" style={{ fontSize: "0.85rem" }}>
        ← Settings
      </Link>
      <h1 style={{ fontSize: "1.3rem" }}>Server admin</h1>

      {error && <p className="error-text">{error}</p>}

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Registration</h2>
        <p className="muted" style={{ margin: "0 0 0.6rem", fontSize: "0.85rem" }}>
          Allow new accounts. This is a runtime override — it resets to the server's configured
          default when the server restarts.
        </p>
        <button type="button" className="btn" onClick={toggleRegistration} disabled={allowRegistration === null}>
          {allowRegistration === null
            ? "Loading…"
            : allowRegistration
              ? "Registration is ON — turn off"
              : "Registration is OFF — turn on"}
        </button>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Users</h2>
        <div className="form-field">
          <label htmlFor="admin-password">Your password (required for reset/delete)</label>
          <input
            id="admin-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>

        {resetResult && (
          <div
            style={{
              margin: "0.5rem 0",
              padding: "0.6rem",
              border: "1px solid var(--color-border)",
              borderRadius: "var(--radius)",
            }}
          >
            <p className="muted" style={{ margin: "0 0 0.3rem", fontSize: "0.85rem" }}>
              New password for <strong>{resetResult.email}</strong> — shown once, send it to them
              securely:
            </p>
            <code style={{ userSelect: "all" }}>{resetResult.password}</code>
          </div>
        )}

        <ul style={{ listStyle: "none", padding: 0 }}>
          {users.map((user) => (
            <li
              key={user.id}
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                gap: "0.5rem",
                padding: "0.4rem 0",
                borderTop: "1px solid var(--color-border)",
              }}
            >
              <span style={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis" }}>
                {user.email}
                {user.is_admin && <strong> (admin)</strong>}
                <span className="muted" style={{ display: "block", fontSize: "0.8rem" }}>
                  {user.session_count} session{user.session_count === 1 ? "" : "s"}
                </span>
              </span>
              <span style={{ display: "flex", gap: "0.4rem", flexShrink: 0 }}>
                <button type="button" className="btn btn-secondary" onClick={() => resetPassword(user)}>
                  Reset password
                </button>
                {/* Admins and your own account can't be deleted here (the server enforces this too). */}
                {!user.is_admin && user.id !== account?.id && (
                  <button type="button" className="btn btn-danger" onClick={() => deleteUser(user)}>
                    Delete
                  </button>
                )}
              </span>
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
