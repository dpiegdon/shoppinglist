import { useEffect, useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/client";
import { allowRegistration, appBasename } from "../lib/appConfig";

const apkUrl = () => `${appBasename()}/shoppinglist.apk`;

export default function LoginPage() {
  const { login, register, loading } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [apkAvailable, setApkAvailable] = useState(false);

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
        setError("Something went wrong. Please try again.");
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
        <h1 style={{ fontSize: "1.4rem", marginTop: 0 }}>Shopping List</h1>
        <div className="form-field">
          <label htmlFor="email">Email</label>
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
          <label htmlFor="password">Password</label>
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
          {mode === "login" ? "Log in" : "Create account"}
        </button>
        <button
          type="button"
          disabled={!allowRegistration()}
          onClick={() => setMode(mode === "login" ? "register" : "login")}
          className="btn-secondary btn"
          style={{ width: "100%", marginTop: "0.5rem", background: "transparent", border: "none" }}
        >
          {mode === "login" ? "Need an account? Register" : "Have an account? Log in"}
        </button>
        {!allowRegistration() && (
          <p className="muted" style={{ textAlign: "center", marginTop: "0.25rem", marginBottom: 0, fontSize: "0.85rem" }}>
            Registration is disabled on this server.
          </p>
        )}
        {apkAvailable && (
          <p className="muted" style={{ textAlign: "center", marginTop: "0.75rem", marginBottom: 0, fontSize: "0.85rem" }}>
            <a href={apkUrl()} style={{ color: "var(--color-accent)" }}>
              Get the Android app
            </a>
          </p>
        )}
      </form>
    </main>
  );
}
