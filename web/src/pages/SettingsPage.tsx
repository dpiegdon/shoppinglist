import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import * as api from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { useT } from "../i18n";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { useConfirm } from "../hooks/useConfirm";
import LanguagePicker from "../components/LanguagePicker";
import { getCachedDefaultCurrency, setCachedDefaultCurrency, useDefaultCurrency } from "../hooks/useDefaultCurrency";
import type { Session } from "../api/contract";
import { formatLastSeen } from "../lib/relativeTime";
import { errorMessage } from "../i18n/apiErrors";
import type { MessageKey } from "../i18n/messages/en";

function useFormStatus() {
  // A custom hook, so it takes the translate function itself rather than being handed one — the
  // component's `t` is not in scope here.
  const t = useT();
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState(false);
  // [overrides] lets one form say something narrower for a code (see errorMessage).
  async function run(fn: () => Promise<void>, overrides: Partial<Record<string, MessageKey>> = {}) {
    setError(null);
    setOk(false);
    try {
      await fn();
      setOk(true);
    } catch (err) {
      setError(errorMessage(t, err, "error.generic", overrides));
    }
  }
  return { error, ok, run };
}

/**
 * A form's outcome inside its card: an error announced at once, a confirmation announced politely
 * (W4/W6), so a screen reader hears what the Save did.
 */
function FormStatusLines({ status, okText }: { status: { error: string | null; ok: boolean }; okText: string }) {
  return (
    <>
      {status.error && (
        <p className="error-text" role="alert">
          {status.error}
        </p>
      )}
      {status.ok && (
        <p className="muted" role="status">
          {okText}
        </p>
      )}
    </>
  );
}

export default function SettingsPage() {

  const t = useT();
  const [confirmDialog, askConfirm] = useConfirm();
  useDocumentTitle(t("settings.title"));
  const { account, logout } = useAuth();
  const navigate = useNavigate();
  const currentCurrency = useDefaultCurrency();
  const [currency, setCurrency] = useState(currentCurrency);
  const currencyStatus = useFormStatus();

  // Not covered by useDefaultCurrency (that hook is currency-only and used well beyond this
  // page) — a small dedicated fetch, mirroring the same on-mount pattern.
  //
  // `null` means "preload hasn't resolved yet (or failed)" — distinct from a genuinely empty
  // string once loaded, so the input can render blank without that blank looking like a real
  // value the user chose.
  //
  // Note this value is the server's *resolved* initials: for an account with no override it is
  // the email-derived default, indistinguishable here from a stored one. That's why only the
  // initials form below ever sends it, and only as a deliberate user action — see the currency
  // save (T-103).
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
  // Typed twice so a slip of the finger does not lock the user out (T-313). Checked here only: the
  // request still carries the one new password.
  const [newPasswordAgain, setNewPasswordAgain] = useState("");
  const [passwordMismatch, setPasswordMismatch] = useState(false);
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
      // Never send `initials` from this form — it's the currency form, and the server treats an
      // absent key as "leave unchanged" (T-87), so omitting it is both correct and sufficient.
      //
      // Sending it was actively harmful (T-103). GET /settings returns the *resolved* value, so
      // an account with no override reads back the email-derived default ("BO" for bob@…) with
      // nothing marking it as derived. Echoing that back stored it as an explicit override,
      // silently pinning the initials so a later email change no longer re-derived them. T-101
      // fixed only the narrower case where the preload hadn't resolved; the resolved case had
      // the same bug.
      const result = await api.updateSettings({ default_currency: currency.toUpperCase() });
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
      //
      // Never send default_currency from this form (T-272), for the same reason the currency
      // form never sends initials: the server treats an absent key as "leave unchanged". Sending
      // whatever sits in the currency box meant typing an unsaved currency and then saving
      // initials either failed on the currency's format, or silently persisted a currency the
      // cached value still didn't show as saved.
      const result = await api.updateSettings({
        initials: (initials ?? "").trim().toUpperCase(),
      });
      setInitials(result.initials);
    });
  }

  async function handlePasswordSave(e: FormEvent) {
    e.preventDefault();
    if (newPassword !== newPasswordAgain) {
      setPasswordMismatch(true);
      return;
    }
    await passwordStatus.run(async () => {
      await api.changePassword({ current_password: currentPassword, new_password: newPassword });
      setCurrentPassword("");
      setNewPassword("");
      setNewPasswordAgain("");
    }, { invalid_credentials: "settings.passwordIncorrect" });
  }

  async function handleEmailSave(e: FormEvent) {
    e.preventDefault();
    await emailStatus.run(async () => {
      await api.changeEmail({ password: emailPassword, new_email: newEmail });
      setEmailPassword("");
      setNewEmail("");
    }, { invalid_credentials: "settings.passwordWrong" });
  }

  async function handleRevokeSession(id: string) {
    await sessionsStatus.run(async () => {
      await api.revokeSession(id);
      setSessions((s) => s.filter((sess) => sess.id !== id));
    });
  }

  async function handleDeleteAccount(e: FormEvent) {
    e.preventDefault();
    const confirmed = await askConfirm({
      title: t("settings.deleteAccountTitle"),
      body: t("settings.deleteConfirm"),
      confirmLabel: t("action.delete"),
      destructive: true,
    });
    if (!confirmed) return;
    await deleteStatus.run(async () => {
      await api.deleteAccount({ password: deletePassword });
      await logout();
      navigate("/login", { replace: true });
    }, { invalid_credentials: "settings.passwordWrong" });
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <h1 style={{ fontSize: "1.3rem" }}>{t("settings.title")}</h1>

      <LanguagePicker />
      <p className="muted">{account?.email}</p>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 id="settings-currency-title" style={{ fontSize: "1rem", marginTop: 0 }}>
          {t("settings.defaultCurrency")}
        </h2>
        <form onSubmit={handleCurrencySave} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            aria-labelledby="settings-currency-title"
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            style={{ width: "6rem" }}
          />
          <button type="submit" className="btn">
            {t("action.save")}
          </button>
        </form>
        <FormStatusLines status={currencyStatus} okText={t("common.saved")} />
        <p className="muted" style={{ fontSize: "0.8rem" }}>
          {t("settings.currentlyCached", { currency: getCachedDefaultCurrency() })}
        </p>
      </section>

      {/* Shown as a small indicator on shared-list item rows so collaborators can see who last
          touched an item (T-64); defaults to the email's initials until customized here. */}
      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 id="settings-initials-title" style={{ fontSize: "1rem", marginTop: 0 }}>
          {t("settings.initials")}
        </h2>
        <form onSubmit={handleInitialsSave} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            aria-labelledby="settings-initials-title"
            value={initials ?? ""}
            maxLength={3}
            onChange={(e) => setInitials(e.target.value.toUpperCase())}
            style={{ width: "6rem" }}
          />
          <button type="submit" className="btn">
            {t("action.save")}
          </button>
        </form>
        <FormStatusLines status={initialsStatus} okText={t("common.saved")} />
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("settings.changePassword")}</h2>
        <form onSubmit={handlePasswordSave}>
          <div className="form-field">
            <label htmlFor="current-password">{t("settings.currentPassword")}</label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="form-field">
            <label htmlFor="new-password">{t("settings.newPassword")}</label>
            <input
              id="new-password"
              type="password"
              minLength={8}
              value={newPassword}
              onChange={(e) => {
                setNewPassword(e.target.value);
                setPasswordMismatch(false);
              }}
            />
          </div>
          <div className="form-field">
            <label htmlFor="new-password-again">{t("settings.newPasswordAgain")}</label>
            <input
              id="new-password-again"
              type="password"
              value={newPasswordAgain}
              aria-invalid={passwordMismatch ? true : undefined}
              aria-describedby={passwordMismatch ? "new-password-again-error" : undefined}
              onChange={(e) => {
                setNewPasswordAgain(e.target.value);
                setPasswordMismatch(false);
              }}
            />
            {passwordMismatch && (
              <p id="new-password-again-error" className="error-text" role="alert">
                {t("settings.passwordsDoNotMatch")}
              </p>
            )}
          </div>
          <FormStatusLines status={passwordStatus} okText={t("settings.passwordChanged")} />
          <button type="submit" className="btn">
            {t("settings.changePassword")}
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("settings.changeEmail")}</h2>
        <form onSubmit={handleEmailSave}>
          {/* The new address first, then the password that confirms it, as on Android. */}
          <div className="form-field">
            <label htmlFor="new-email">{t("settings.newEmail")}</label>
            <input id="new-email" type="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)} />
          </div>
          <div className="form-field">
            <label htmlFor="email-password">{t("settings.password")}</label>
            <input
              id="email-password"
              type="password"
              value={emailPassword}
              onChange={(e) => setEmailPassword(e.target.value)}
            />
          </div>
          <FormStatusLines status={emailStatus} okText={t("settings.emailChanged")} />
          <button type="submit" className="btn">
            {t("settings.changeEmail")}
          </button>
        </form>
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("settings.sessions")}</h2>
        {sessionsStatus.error && (
          <p className="error-text" role="alert">
            {sessionsStatus.error}
          </p>
        )}
        <ul style={{ listStyle: "none", padding: 0 }}>
          {sessions.map((s) => (
            <li
              key={s.id}
              style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "0.3rem 0" }}
            >
              <span>
                <span>
                  {s.device_label ?? t("settings.unknownDevice")} {s.current && <strong>{t("settings.thisDevice")}</strong>}
                </span>
                {/* The current session is active by definition — this request is it. Showing
                    its stored last_seen_at instead would read as up to 15 minutes stale, since
                    the server throttles that write (auth.LAST_SEEN_REFRESH_MS). */}
                <span
                  className="muted"
                  style={{ display: "block", fontSize: "0.8rem" }}
                  title={new Date(s.last_seen_at).toLocaleString()}
                >
                  {s.current ? t("lastSeen.activeNow") : formatLastSeen(s.last_seen_at, Date.now(), t)}
                </span>
              </span>
              {!s.current && (
                <button type="button" className="btn btn-danger" onClick={() => handleRevokeSession(s.id)}>
                  {t("action.revoke")}
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section className="card" style={{ padding: "1rem", borderColor: "var(--color-danger)" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0, color: "var(--color-danger)" }}>{t("settings.deleteAccount")}</h2>
        <form onSubmit={handleDeleteAccount}>
          <div className="form-field">
            <label htmlFor="delete-password">{t("settings.password")}</label>
            <input
              id="delete-password"
              type="password"
              value={deletePassword}
              onChange={(e) => setDeletePassword(e.target.value)}
            />
          </div>
          {deleteStatus.error && (
            <p className="error-text" role="alert">
              {deleteStatus.error}
            </p>
          )}
          <button type="submit" className="btn btn-danger">
            {t("settings.deleteMyAccount")}
          </button>
        </form>
      </section>
      {confirmDialog}
    </main>
  );
}
