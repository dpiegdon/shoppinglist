import { useCallback, useEffect, useRef, useState } from "react";
import { Navigate } from "react-router-dom";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ModalDialog } from "../components/ModalDialog";
import type { AdminUser } from "../api/contract";
import { useT } from "../i18n";
import { errorMessage } from "../i18n/apiErrors";
import { isValidServerMessage } from "../lib/serverMessage";

/** Accessible on/off switch (T-112): green track when on, red when off. */
function ToggleSwitch({
  checked,
  disabled,
  onChange,
  label,
}: {
  checked: boolean;
  disabled?: boolean;
  onChange: () => void;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={onChange}
      style={{
        position: "relative",
        width: "3rem",
        height: "1.6rem",
        borderRadius: "999px",
        border: "none",
        flexShrink: 0,
        cursor: disabled ? "default" : "pointer",
        opacity: disabled ? 0.5 : 1,
        background: checked ? "#2e7d32" : "var(--color-danger)",
        transition: "background 0.15s",
      }}
    >
      <span
        style={{
          position: "absolute",
          top: "0.2rem",
          // Logical, not `left` (T-126): this knob's POSITION is what says on/off, so in an
          // RTL layout it has to travel the other way or the switch reads inverted.
          insetInlineStart: checked ? "1.6rem" : "0.2rem",
          width: "1.2rem",
          height: "1.2rem",
          borderRadius: "50%",
          background: "#fff",
          transition: "inset-inline-start 0.15s",
        }}
      />
    </button>
  );
}

/**
 * Admin-only server console (T-107): toggle registration for this run, reset a user's password,
 * delete a user. Reached from the main menu (T-220); gated on the login response's is_admin. Destructive actions
 * re-verify the admin's own password (entered once below), and resetting or deleting a user requires
 * an explicit confirmation naming them so a stray click can't lock out or nuke an account (T-112,
 * T-313).
 */
export default function AdminPage() {
  const t = useT();
  const { account } = useAuth();
  // null until asked for (T-221): opening the console must not pull every account on an instance
  // with hundreds of them. The registration toggle below is one value, so that still loads on open.
  const [users, setUsers] = useState<AdminUser[] | null>(null);
  const [allowRegistration, setAllowRegistration] = useState<boolean | null>(null);
  // The server message (T-315): the draft in the field, and whether this server has the setting at
  // all — one from before it answers without `message`, and would refuse a PUT of one.
  const [messageDraft, setMessageDraft] = useState("");
  const [messageSupported, setMessageSupported] = useState(false);
  const [messageError, setMessageError] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  // Shown at the password field itself, not with the page-level `error` at the top (T-113): the
  // top message is easy to miss when you're scrolled down among many users, which made a
  // blocked reset/delete look like nothing happened at all.
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [resetResult, setResetResult] = useState<{ email: string; password: string } | null>(null);
  const [passwordCopied, setPasswordCopied] = useState(false);
  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null);
  const closeResetDialog = useCallback(() => setResetTarget(null), []);
  const [deleteTarget, setDeleteTarget] = useState<AdminUser | null>(null);
  const closeDeleteDialog = useCallback(() => setDeleteTarget(null), []);
  const passwordRef = useRef<HTMLInputElement>(null);

  /** True (and complains inline) when the step-up password is missing. */
  function requirePassword(): boolean {
    if (password) {
      setPasswordError(null);
      return true;
    }
    setPasswordError(t("admin.passwordRequired"));
    passwordRef.current?.focus();
    return false;
  }

  async function loadSettings() {
    try {
      const settings = await api.getServerSettings();
      setAllowRegistration(settings.allow_registration);
      if (typeof settings.message === "string") {
        setMessageDraft(settings.message);
        setMessageSupported(true);
      }
    } catch (err) {
      setError(errorMessage(t, err, "admin.loadFailed"));
    }
  }

  /** Fetches (or re-fetches) the user list. Server-ordered by email, so nothing is sorted here. */
  async function loadUsers() {
    try {
      const usersResp = await api.getAdminUsers();
      setUsers(usersResp.users);
      setError(null);
    } catch (err) {
      setError(errorMessage(t, err, "admin.loadFailed"));
    }
  }

  useEffect(() => {
    loadSettings();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Non-admins (or a stale stored account) never see this page's data.
  if (account && !account.isAdmin) return <Navigate to="/" replace />;

  async function toggleRegistration() {
    if (allowRegistration === null) return;
    setError(null);
    try {
      // Only the flag (T-315): the PUT is partial, so the message is left as it is.
      const result = await api.setServerSettings({ allow_registration: !allowRegistration });
      setAllowRegistration(result.allow_registration);
    } catch (err) {
      setError(errorMessage(t, err, "admin.updateFailed"));
    }
  }

  /** Save sends the draft, Clear sends "" (T-315); the rule is checked here before anything goes out. */
  async function saveMessage(text: string) {
    setError(null);
    if (!isValidServerMessage(text)) {
      setMessageError(t("admin.messageInvalid"));
      return;
    }
    setMessageError(null);
    try {
      const result = await api.setServerSettings({ message: text.trim() });
      setMessageDraft(result.message ?? "");
    } catch (err) {
      // The server's own refusal of the same rule reads the same as the check above.
      if (err instanceof ApiError && err.code === "invalid_message") setMessageError(t("admin.messageInvalid"));
      else setError(errorMessage(t, err, "admin.updateFailed"));
    }
  }

  // The password is checked before the confirmation opens, as for a deletion (T-113), so the admin
  // is never asked to confirm a reset that then can't run.
  function requestReset(user: AdminUser) {
    if (!requirePassword()) return;
    setError(null);
    setResetTarget(user);
  }

  async function confirmReset() {
    const user = resetTarget;
    setResetTarget(null);
    if (!user) return;
    setResetResult(null);
    setPasswordCopied(false);
    try {
      const result = await api.adminResetPassword(user.id, password);
      setResetResult({ email: user.email, password: result.password });
    } catch (err) {
      setError(errorMessage(t, err, "admin.resetFailed"));
    }
  }

  async function copyResetPassword() {
    if (!resetResult) return;
    try {
      await navigator.clipboard.writeText(resetResult.password);
      setPasswordCopied(true);
      setTimeout(() => setPasswordCopied(false), 2000);
    } catch {
      // Clipboard unavailable (e.g. non-secure context): the password stays selectable as a fallback.
    }
  }

  function requestDelete(user: AdminUser) {
    if (!requirePassword()) return;
    setError(null);
    setDeleteTarget(user);
  }

  async function confirmDelete() {
    const user = deleteTarget;
    setDeleteTarget(null);
    if (!user) return;
    try {
      await api.adminDeleteUser(user.id, password);
      setUsers((prev) => prev && prev.filter((u) => u.id !== user.id));
    } catch (err) {
      setError(errorMessage(t, err, "admin.deleteFailed"));
    }
  }

  return (
    <main style={{ padding: "1rem", maxWidth: "40rem", margin: "0 auto", width: "100%" }}>
      <h1 style={{ fontSize: "1.3rem" }}>{t("admin.title")}</h1>

      {error && <p className="error-text">{error}</p>}

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("admin.registration")}</h2>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: "1rem" }}>
          <div>
            <div>{t("admin.allowNewAccounts")}</div>
            <p className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.85rem" }}>
              {t("admin.registrationHelp")}
            </p>
          </div>
          <ToggleSwitch
            checked={allowRegistration === true}
            disabled={allowRegistration === null}
            onChange={toggleRegistration}
            label={t("admin.allowNewAccounts")}
          />
        </div>
        {messageSupported && (
          <div className="form-field" style={{ marginTop: "1rem", marginBottom: 0 }}>
            <label htmlFor="admin-server-message">{t("admin.serverMessage")}</label>
            <input
              id="admin-server-message"
              type="text"
              dir="auto"
              value={messageDraft}
              onChange={(e) => {
                setMessageDraft(e.target.value);
                setMessageError(null);
              }}
              aria-invalid={messageError ? true : undefined}
              aria-describedby="admin-server-message-help"
            />
            <p id="admin-server-message-help" className="muted" style={{ margin: "0.2rem 0 0", fontSize: "0.85rem" }}>
              {t("admin.serverMessageHelp")}
            </p>
            {messageError && (
              <p className="error-text" role="alert">
                {messageError}
              </p>
            )}
            <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.5rem" }}>
              <button type="button" className="btn" onClick={() => saveMessage(messageDraft)}>
                {t("action.save")}
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => saveMessage("")}>
                {t("action.clear")}
              </button>
            </div>
          </div>
        )}
      </section>

      <section className="card" style={{ padding: "1rem", marginBottom: "1rem" }}>
        <h2 style={{ fontSize: "1rem", marginTop: 0 }}>{t("admin.users")}</h2>
        {/* One screen, not a submenu (T-221): the console is small, and a second navigation step
            on both clients would buy nothing. The list is simply not fetched until asked for. */}
        {users === null && (
          <button type="button" className="btn" onClick={loadUsers}>
            {t("admin.showUsers")}
          </button>
        )}

        {users !== null && (
          <>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: "1rem",
                marginBottom: "0.5rem",
              }}
            >
              <span className="muted" style={{ fontSize: "0.85rem" }}>
                {t("admin.userCount", { count: users.length })}
              </span>
              <button type="button" className="btn btn-sm" onClick={loadUsers}>
                {t("action.refresh")}
              </button>
            </div>
            <div className="form-field">
              <label htmlFor="admin-password">{t("admin.yourPassword")}</label>
              <input
                id="admin-password"
                type="password"
                ref={passwordRef}
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  if (e.target.value) setPasswordError(null);
                }}
                autoComplete="current-password"
                aria-invalid={passwordError ? true : undefined}
              />
              {passwordError && (
                <p className="error-text" role="alert">
                  {passwordError}
                </p>
              )}
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
                  {t("admin.newPasswordFor", { email: resetResult.email })}
                </p>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <code
                    data-testid="reset-password"
                    style={{ userSelect: "all", fontFamily: "monospace", flex: 1, minWidth: 0, overflowWrap: "anywhere" }}
                  >
                    {resetResult.password}
                  </code>
                  <button type="button" className="btn btn-sm" onClick={copyResetPassword}>
                    {passwordCopied ? t("action.copied") : t("action.copy")}
                  </button>
                </div>
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
                    {user.is_admin && <strong> {t("admin.isAdmin")}</strong>}
                    <span className="muted" style={{ display: "block", fontSize: "0.8rem" }}>
                      {t("admin.sessionCount", { count: user.session_count })}
                    </span>
                  </span>
                  <span style={{ display: "flex", gap: "0.4rem", flexShrink: 0 }}>
                    <button type="button" className="btn" onClick={() => requestReset(user)}>
                      {t("admin.resetPassword")}
                    </button>
                    {/* Admins and your own account can't be deleted here (the server enforces this too). */}
                    {!user.is_admin && user.id !== account?.id && (
                      <button type="button" className="btn btn-danger" onClick={() => requestDelete(user)}>
                        {t("action.delete")}
                      </button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      {resetTarget && (
        <ModalDialog onClose={closeResetDialog} labelledBy="reset-user-title">
          <h2 id="reset-user-title" style={{ marginTop: 0, fontSize: "1.1rem" }}>{t("admin.resetUserTitle")}</h2>
          <p>{t("admin.resetUserBody", { email: resetTarget.email })}</p>
          <div style={{ display: "flex", gap: "0.5rem", justifyContent: "flex-end", marginTop: "0.5rem" }}>
            <button type="button" className="btn btn-secondary" onClick={closeResetDialog}>
              {t("action.cancel")}
            </button>
            <button type="button" className="btn" onClick={confirmReset}>
              {t("action.reset")}
            </button>
          </div>
        </ModalDialog>
      )}

      {deleteTarget && (
        <ModalDialog onClose={closeDeleteDialog} labelledBy="delete-user-title">
          <h2 id="delete-user-title" style={{ marginTop: 0, fontSize: "1.1rem" }}>{t("admin.deleteUserTitle")}</h2>
          <p>
            {t("admin.deleteUserBody", { email: deleteTarget.email })}
          </p>
          <div style={{ display: "flex", gap: "0.5rem", justifyContent: "flex-end", marginTop: "0.5rem" }}>
            <button type="button" className="btn btn-secondary" onClick={closeDeleteDialog}>
              {t("action.cancel")}
            </button>
            <button type="button" className="btn btn-danger" onClick={confirmDelete}>
              {t("admin.deleteUserConfirm", { email: deleteTarget.email })}
            </button>
          </div>
        </ModalDialog>
      )}
    </main>
  );
}
