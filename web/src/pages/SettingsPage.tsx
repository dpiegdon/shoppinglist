import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { getCachedDefaultCurrency, setCachedDefaultCurrency, useDefaultCurrency } from "../hooks/useDefaultCurrency";
import type { Session } from "../api/contract";

function useFormStatus() {
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState(false);
  async function run(fn: () => Promise<void>) {
    setError(null);
    setOk(false);
    try {
      await fn();
      setOk(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong.");
    }
  }
  return { error, ok, run };
}

export default function SettingsPage() {
  const { account, logout } = useAuth();
  const navigate = useNavigate();
  const currentCurrency = useDefaultCurrency();
  const [currency, setCurrency] = useState(currentCurrency);
  const currencyStatus = useFormStatus();

  // Not covered by useDefaultCurrency (that hook is currency-only and used well beyond this
  // page) — a small dedicated fetch, mirroring the same on-mount pattern.
  //
  // `null` means "preload hasn't resolved yet (or failed)" — distinct from a genuinely empty
  // string once loaded. This matters because the server now treats an ABSENT `initials` key in
  // PATCH /settings as "leave unchanged" (T-87), but a *present* "" still overwrites a custom
  // override. Before that fix, this state started at "" and a currency-only save (below) would
  // resend that unresolved "" as if it were real, wiping the override (T-101). Keeping the
  // "not loaded yet" state distinguishable lets the currency save omit the key entirely until we
  // actually know the value.
  const [initials, setInitials] = useState<string | null>(null);
  const initialsStatus = useFormStatus();
  useEffect(() => {
    let cancelled = false;
    api
      .getSettings()
      .then((s) => { if (!cancelled) setInitials(s.initials); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const passwordStatus = useFormStatus();

  const [emailPassword, setEmailPassword] = useState("");
  const [newEmail, setNewEmail] = useState("");
  const emailStatus = useFormStatus();

  const [sessions, setSessions] = useState<Session[]>([]);
  const sessionsStatus = useFormStatus();

  const [deletePassword, setDeletePassword] = useState("");
  const deleteStatus = useFormStatus();

  useEffect(() => {
    setCurrency(currentCurrency);
  }, [currentCurrency]);

  useEffect(() => {
    api.listSessions().then((r) => setSessions(r.sessions)).catch(() => {});
  }, []);

  async function handleCurrencySave(e: FormEvent) {
    e.preventDefault();
    await currencyStatus.run(async () => {
      // Omit `initials` entirely unless the preload has actually resolved (T-101): the server
      // treats an absent key as "leave unchanged" (T-87), but a *present* "" is a real value
      // that clears a custom override. We can't tell the difference between "not loaded" and
      // "loaded and blank" from an empty string alone, so `initials === null` is the signal —
      // in that case, send only default_currency.
      const result = await api.updateSettings(
        initials === null
          ? { default_currency: currency.toUpperCase() }
          : { default_currency: currency.toUpperCase(), initials },
      );
      setCachedDefaultCurrency(result.default_currency);
      setInitials(result.initials);
    });
  }

  async function handleInitialsSave(e: FormEvent) {
    e.preventDefault();
    await initialsStatus.run(async () => {
      // An explicit save from this form is always a real, user-confirmed value (even if the
      // preload hadn't resolved and the field was still showing blank) — length is validated
      // server-side (422 invalid_initials), same as currency's format check above.
      const result = await api.updateSettings({
        default_currency: currency.toUpperCase(),
        initials: (initials ?? "").trim().toUpperCase(),
      });
      setInitials(result.initials);
    });
  }

  async function handlePasswordSave(e: FormEvent) {
    e.preventDefault();
    await passwordStatus.run(async () => {
      await api.changePassword({ current_password: currentPassword, new_password: newPassword });
      setCurrentPassword("");
      setNewPassword("");
    });
  }

  async function handleEmailSave(e: FormEvent) {
    e.preventDefault();
    await emailStatus.run(async () => {
      await api.changeEmail({ password: emailPassword, new_email: newEmail });
      setEmailPassword("");
      setNewEmail("");
    });
  }

  async function handleRevokeSession(id: string) {
    await sessionsStatus.run(async () => {
      await api.revokeSession(id);
      setSessions((s) => s.filter((sess) => sess.id !== id));
    });
  }

  async function handleDeleteAccount(e: FormEvent) {
    e.preventDefault();
    if (!confirm("This permanently deletes your account. Are you sure?")) return;
    await deleteStatus.run(async () => {
      await api.deleteAccount({ password: deletePassword });
      await logout();
      navigate("/login", { replace: true });
    });
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <h1 style={{ fontSize: "1.3rem" }}>Account settings</h1>
      <p className="muted">{account?.email}</p>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Default currency</h2>
        <form onSubmit={handleCurrencySave} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            style={{ width: "6rem" }}
          />
          <button type="submit" className="btn">
            Save
          </button>
        </form>
        {currencyStatus.error && <p className="error-text">{currencyStatus.error}</p>}
        {currencyStatus.ok && <p className="muted">Saved.</p>}
        <p className="muted" style={{ fontSize: "0.8rem" }}>
          Currently cached: {getCachedDefaultCurrency()}
        </p>
      </section>

      {/* Shown as a small indicator on shared-list item rows so collaborators can see who last
          touched an item (T-64); defaults to the email's initials until customized here. */}
      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Display initials</h2>
        <form onSubmit={handleInitialsSave} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            value={initials ?? ""}
            maxLength={3}
            onChange={(e) => setInitials(e.target.value.toUpperCase())}
            style={{ width: "6rem" }}
          />
          <button type="submit" className="btn">
            Save
          </button>
        </form>
        {initialsStatus.error && <p className="error-text">{initialsStatus.error}</p>}
        {initialsStatus.ok && <p className="muted">Saved.</p>}
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Change password</h2>
        <form onSubmit={handlePasswordSave}>
          <div className="form-field">
            <label htmlFor="current-password">Current password</label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="new-password">New password</label>
            <input
              id="new-password"
              type="password"
              minLength={8}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </div>
          {passwordStatus.error && <p className="error-text">{passwordStatus.error}</p>}
          {passwordStatus.ok && <p className="muted">Password changed.</p>}
          <button type="submit" className="btn">
            Change password
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Change email</h2>
        <form onSubmit={handleEmailSave}>
          <div className="form-field">
            <label htmlFor="email-password">Password</label>
            <input
              id="email-password"
              type="password"
              value={emailPassword}
              onChange={(e) => setEmailPassword(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="new-email">New email</label>
            <input id="new-email" type="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)} />
          </div>
          {emailStatus.error && <p className="error-text">{emailStatus.error}</p>}
          {emailStatus.ok && <p className="muted">Email changed.</p>}
          <button type="submit" className="btn">
            Change email
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>Sessions</h2>
        {sessionsStatus.error && <p className="error-text">{sessionsStatus.error}</p>}
        <ul style={{ listStyle: "none", padding: 0 }}>
          {sessions.map((s) => (
            <li
              key={s.id}
              style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "0.3rem 0" }}
            >
              <span>
                {s.device_label} {s.current && <strong>(this device)</strong>}
              </span>
              {!s.current && (
                <button type="button" className="btn-icon" onClick={() => handleRevokeSession(s.id)}>
                  Revoke
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="card" style={{ padding: "1rem", borderColor: "var(--color-danger)" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0, color: "var(--color-danger)" }}>Delete account</h2>
        <form onSubmit={handleDeleteAccount}>
          <div className="form-field">
            <label htmlFor="delete-password">Password</label>
            <input
              id="delete-password"
              type="password"
              value={deletePassword}
              onChange={(e) => setDeletePassword(e.target.value)}
            />
          </div>
          {deleteStatus.error && <p className="error-text">{deleteStatus.error}</p>}
          <button type="submit" className="btn btn-danger">
            Delete my account
          </button>
        </form>
      </section>
    </main>
  );
}
