import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import * as api from "../api/client";
import { ApiError } from "../api/client";
import { allowRegistration, appBasename } from "../lib/appConfig";
import { useT } from "../i18n";
import LanguagePicker from "../components/LanguagePicker";

const apkUrl = () => `${appBasename()}/shoppinglist.apk`;

export default function LoginPage() {
  const t = useT();
  const { login, register, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [apkAvailable, setApkAvailable] = useState(false);
  // Start from the startup-baked meta flag, then refresh with the EFFECTIVE value so an admin's
  // live registration toggle is reflected without a redeploy (T-107). Falls back to the meta value
  // if the fetch fails (offline / old server).
  const [registrationAllowed, setRegistrationAllowed] = useState(allowRegistration());

  useEffect(() => {
    let cancelled = false;
    api
      .getRegistrationStatus()
      .then((s) => { if (!cancelled) setRegistrationAllowed(s.allow_registration); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  // Show the app-download link only when this server actually serves the APK (T-59) — the SPA is
  // static, so probe rather than render a link that might 404 on an operator without the artifact.
  useEffect(() => {
    if (typeof fetch === "undefined") return;
    fetch(apkUrl(), { method: "HEAD" })
      .then((resp) => setApkAvailable(resp.ok))
      .catch(() => {});
  }, []);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (mode === "login") {
        await login(email, password);
      } else {
        await register(email, password);
      }
      // Return to wherever the user was headed before the redirect (e.g. /redeem?token=...),
      // falling back to the overview (T-43). Rebuild the string so the query survives.
      const from = (location.state as { from?: { pathname: string; search: string } } | null)?.from;
      navigate(from ? `${from.pathname}${from.search}` : "/", { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(t("login.error.generic"));
      }
    }
  }

  return (
    <main
      style={{
        minHeight: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "1rem",
      }}
    >
      <form onSubmit={handleSubmit} className="card" style={{ padding: "2rem", width: "100%", maxWidth: "22rem" }}>
        <h1 style={{ fontSize: "1.4rem", marginTop: 0 }}>{t("app.title")}</h1>
        <div className="form-field">
          <label htmlFor="email">{t("login.email")}</label>
          <input
            id="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>
        <div className="form-field">
          <label htmlFor="password">{t("login.password")}</label>
          <input
            id="password"
            type="password"
            autoComplete={mode === "login" ? "current-password" : "new-password"}
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        {error && (
          <p className="error-text" role="alert">
            {error}
          </p>
        )}
        <button type="submit" className="btn" disabled={loading} style={{ width: "100%", marginTop: "0.5rem" }}>
          {mode === "login" ? t("login.submit") : t("login.register")}
        </button>
        <button
          type="button"
          disabled={!registrationAllowed}
          onClick={() => setMode(mode === "login" ? "register" : "login")}
          className="btn-secondary btn"
          style={{ width: "100%", marginTop: "0.5rem", background: "transparent", border: "none" }}
        >
          {mode === "login" ? t("login.toggleToRegister") : t("login.toggleToLogin")}
        </button>
        {/* Before login, deliberately: the chooser has to be reachable without an account
            (T-127), which is also why the preference is device-local. */}
        <div style={{ marginTop: "1rem" }}>
          <LanguagePicker id="login-language" />
        </div>
        {!registrationAllowed && (
          <p className="muted" style={{ textAlign: "center", marginTop: "0.25rem", marginBottom: 0, fontSize: "0.85rem" }}>
            {t("login.registrationDisabled")}
          </p>
        )}
        {apkAvailable && (
          <p className="muted" style={{ textAlign: "center", marginTop: "0.75rem", marginBottom: 0, fontSize: "0.85rem" }}>
            <a href={apkUrl()} style={{ color: "var(--color-accent)" }}>
              {t("login.getAndroidApp")}
            </a>
          </p>
        )}
      </form>
    </main>
  );
}
